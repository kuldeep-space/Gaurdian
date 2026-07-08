package com.ai.guardian.data.remote

import android.util.Log
import com.ai.guardian.data.dao.PairedDeviceDao
import com.ai.guardian.data.entity.PairedDeviceEntity
import com.ai.guardian.data.remote.models.SyncRequest
import com.ai.guardian.data.remote.models.SyncRequestType
import com.ai.guardian.data.remote.models.SyncStatus
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

import android.content.Context

class PairingManager(
    private val context: Context,
    private val deviceSyncManager: DeviceSyncManager,
    private val pairedDeviceDao: PairedDeviceDao,
    private val syncEngineManager: SyncEngineManager
) {
    private val firestore = FirebaseFirestore.getInstance()
    private var pairingScope: CoroutineScope? = null
    private var pairingListener: ListenerRegistration? = null
    
    private val _isPairingModeActive = MutableStateFlow(false)
    val isPairingModeActive: StateFlow<Boolean> = _isPairingModeActive.asStateFlow()

    fun startPairingMode(timeoutMillis: Long = 600000) {
        stopPairingMode()
        
        Log.d("PairingManager", "Starting Pairing Mode. Generating new pair code.")
        deviceSyncManager.regeneratePairCode()
        
        pairingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        _isPairingModeActive.value = true
        
        // Timeout Coroutine
        pairingScope?.launch {
            delay(timeoutMillis)
            Log.d("PairingManager", "Pairing Mode timeout expired.")
            stopPairingMode()
        }
        
        val uuid = deviceSyncManager.deviceUuid
        val requestsRef = firestore.collection("devices").document(uuid).collection("syncRequests")
        
        pairingListener = requestsRef
            .whereEqualTo("status", SyncStatus.PENDING.name)
            .whereEqualTo("type", SyncRequestType.INITIAL_SYNC.name)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e("PairingManager", "Pairing listener error", e)
                    return@addSnapshotListener
                }
                
                if (snapshot == null || snapshot.isEmpty) return@addSnapshotListener
                
                pairingScope?.launch {
                    val currentPairCode = deviceSyncManager.pairCode
                    val localDevices = pairedDeviceDao.getAllPairedDevicesSynchronous().map { it.uuid }
                    
                    for (doc in snapshot.documents) {
                        val request = doc.toObject(SyncRequest::class.java) ?: continue
                        val docRef = doc.reference
                        
                        // 1. Validate Expiration (10 mins)
                        val now = System.currentTimeMillis()
                        val isExpired = (now - request.createdAt) > 600000
                        
                        // 2. Validate Duplicate
                        val isDuplicate = localDevices.contains(request.requesterId)
                        
                        // 3. Validate Pair Code
                        val isCodeValid = request.pairCode == currentPairCode
                        
                        if (isExpired || isDuplicate || !isCodeValid) {
                            Log.w("PairingManager", "Rejecting request ${request.requestId}. Expired:$isExpired Dup:$isDuplicate CodeMatch:$isCodeValid")
                            docRef.update("status", SyncStatus.FAILED.name)
                            continue
                        }
                        
                        Log.d("PairingManager", "Pairing Request Validated! Completing pairing atomically.")
                        
                        try {
                            // 1. Claim request by updating Firestore to PROCESSING
                            docRef.update("status", SyncStatus.PROCESSING.name).await()
                            
                            // 2. Insert Parent into Room (this device is child, parent is inserted)
                            val entity = PairedDeviceEntity(
                                uuid = request.requesterId,
                                deviceName = request.requesterName.takeIf { it.isNotBlank() } ?: "Parent Device",
                                pairingKey = request.pairCode,
                                isParentDevice = true,
                                parentPublicKeyId = request.keyId,
                                parentPublicKeyPem = request.publicKeyPem,
                                parentKeyVersion = request.keyVersion,
                                parentKeyCreatedAt = request.createdAt,
                                parentKeyExpiresAt = null
                            )
                            pairedDeviceDao.insertPairedDevice(entity)
                            
                            // 3. Create authorizedParents document in Firestore
                            firestore.collection("devices")
                                .document(deviceSyncManager.deviceUuid)
                                .collection("authorizedParents")
                                .document(request.requesterId)
                                .set(mapOf("addedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()))
                                .await()

                            // 4. Invalidate pair code
                            deviceSyncManager.regeneratePairCode()
                                                        
                            // 5. Start SyncEngineManager
                            syncEngineManager.start()
                             
                            // 6. Bootstrap Recovery (trigger immediate heartbeat presence/state sync)
                            syncEngineManager.triggerImmediateHeartbeat()

                            // 7. Trigger E2EE PIN sync
                            com.ai.guardian.security.PinSyncManager.getInstance(context).triggerSync()

                            // 8. Update Firestore status to COMPLETED
                            docRef.update("status", SyncStatus.COMPLETED.name).await()
                            
                            Log.d("PairingManager", "Pairing Request completed successfully in Firestore.")
                            stopPairingMode()
                            break
                        } catch (ex: Exception) {
                            Log.e("PairingManager", "Failed during pairing sequence, rolling back.", ex)
                            // Roll back local DB insert
                            pairedDeviceDao.deleteDeviceByUuid(request.requesterId)
                            // Roll back Firestore authorizedParent entry under NonCancellable
                            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                try {
                                    firestore.collection("devices")
                                        .document(deviceSyncManager.deviceUuid)
                                        .collection("authorizedParents")
                                        .document(request.requesterId)
                                        .delete()
                                        .await()
                                } catch (e: Exception) {
                                    Log.e("PairingManager", "Failed to delete authorizedParent entry on rollback", e)
                                }
                                try {
                                    docRef.update("status", SyncStatus.FAILED.name).await()
                                } catch (e: Exception) {
                                    Log.e("PairingManager", "Failed to set status to FAILED on rollback", e)
                                }
                            }
                            if (ex is kotlinx.coroutines.CancellationException) {
                                throw ex
                            }
                        }
                    }
                }
            }
    }
    
    fun stopPairingMode() {
        if (_isPairingModeActive.value) {
            Log.d("PairingManager", "Stopping Pairing Mode.")
        }
        _isPairingModeActive.value = false
        pairingListener?.remove()
        pairingListener = null
        pairingScope?.cancel()
        pairingScope = null
    }
}
