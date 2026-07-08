package com.ai.guardian.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ai.guardian.data.AppDatabase
import com.ai.guardian.data.entity.PairedDeviceEntity
import com.ai.guardian.data.remote.DeviceSyncManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DeviceViewModel(private val application: Application) : AndroidViewModel(application) {
    private val container = (application as com.ai.guardian.GuardianApplication).container
    private val pairedDeviceDao = container.pairedDeviceDao
    private val activeUnpairListeners = java.util.Collections.synchronizedList(mutableListOf<com.google.firebase.firestore.ListenerRegistration>())
    
    val syncManager = container.deviceSyncManager
    val pairingManager = container.pairingManager
    
    val pairedDevices: StateFlow<List<PairedDeviceEntity>> = pairedDeviceDao.getAllPairedDevices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        
    val isPairingModeActive = pairingManager.isPairingModeActive
    
    init {
        viewModelScope.launch {
            recoverPendingUnpairCommands()
        }
    }
        
    fun startPairingMode() {
        if (!isPairingModeActive.value) {
            pairingManager.startPairingMode()
        }
    }
    
    fun stopPairingMode() {
        pairingManager.stopPairingMode()
    }

    fun pairNewDevice(uuid: String, deviceName: String, pairCode: String, onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                // Here we would typically verify the pair code with Firestore.
                // However, the rule says "Save pairing inside Firestore" - but wait!
                // To keep it simple and serverless:
                // Device A generates QR code containing UUID, Name, and Key.
                // Device B scans it, and saves Device A to its Room DB as a Paired Device.
                // It also writes a document to Firestore: devices/{myUuid}/pairings/{A_Uuid} = true (if we want two-way).
                // Or simply, when Device B wants to control Device A, it just writes to devices/{A_Uuid}/apps/...
                // Since there are no strict Security Rules enforced yet, we just trust the pairing key.
                
                if (uuid.isBlank() || pairCode.isBlank()) {
                    onComplete(false, "Invalid pairing data.")
                    return@launch
                }
                if (uuid == syncManager.deviceUuid) {
                    onComplete(false, "Cannot pair with yourself.")
                    return@launch
                }
                
                // This device is the PARENT scanning the Child's QR.
                // isParentDevice=false means "the device we're storing is our Child, not our Parent"
                val entity = PairedDeviceEntity(
                    uuid = uuid,
                    deviceName = deviceName,
                    pairingKey = pairCode,
                    isParentDevice = false
                )
                pairedDeviceDao.insertPairedDevice(entity)
                
                // Write our device ID into the Child's `authorizedParents` subcollection 
                // so the server/rules officially recognize us as an authorized Parent.
                val authDbRef = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    .collection("devices")
                    .document(uuid)
                    .collection("authorizedParents")
                    .document(syncManager.deviceUuid)
                authDbRef.set(mapOf("addedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()))
                
                val parentKeyManager = com.ai.guardian.security.ParentKeyManager(application)
                val keyId = java.util.UUID.randomUUID().toString()
                val publicKeyPem = parentKeyManager.exportPublicKeyPem(keyId)

                // Create a SyncRequest to trigger the Child device
                val requestId = java.util.UUID.randomUUID().toString()
                val syncRequest = com.ai.guardian.data.remote.models.SyncRequest(
                    requestId = requestId,
                    requesterId = syncManager.deviceUuid,
                    requesterName = android.os.Build.MODEL ?: "Parent Device",
                    pairCode = pairCode,
                    type = com.ai.guardian.data.remote.models.SyncRequestType.INITIAL_SYNC,
                    createdAt = System.currentTimeMillis(),
                    status = com.ai.guardian.data.remote.models.SyncStatus.PENDING,
                    publicKeyPem = publicKeyPem,
                    keyId = keyId,
                    keyVersion = 1
                )
                
                val dbRef = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    .collection("devices")
                    .document(uuid)
                    .collection("syncRequests")
                    .document(requestId)
                
                dbRef.set(syncRequest)
                
                // We call onComplete immediately without waiting for Firebase success to allow local pairing
                // even if the network is currently slow or offline. Firebase will sync it automatically.
                onComplete(true, "Paired successfully with $deviceName")
            } catch (e: Exception) {
                onComplete(false, "Error: ${e.message}")
            }
        }
    }
    
    fun removePairedDevice(device: PairedDeviceEntity) {
        viewModelScope.launch {
            try {
                val parentUuid = syncManager.deviceUuid
                val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                
                // 1. Send REMOVE_PAIRING command to Child's command queue
                val docRef = firestore.collection("devices")
                    .document(device.uuid)
                    .collection("commands")
                    .document()
                    
                val command = com.ai.guardian.data.remote.models.RemoteCommand(
                    commandId = docRef.id,
                    commandType = com.ai.guardian.data.remote.models.CommandType.REMOVE_PAIRING,
                    parentDeviceId = parentUuid,
                    childDeviceId = device.uuid,
                    createdAt = System.currentTimeMillis(),
                    expiresAt = System.currentTimeMillis() + 600000,
                    nonce = java.util.UUID.randomUUID().toString(),
                    status = com.ai.guardian.data.remote.models.CommandStatus.PENDING,
                    authorizationToken = device.pairingKey,
                    commandSequence = System.currentTimeMillis(),
                    commandPriority = 100 // High priority emergency level for immediate execution
                )
                
                // 2. Wait for successful Firestore write
                docRef.set(command).addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        var isCompletedOrFailed = false
                        var listenerRegistration: com.google.firebase.firestore.ListenerRegistration? = null
                        
                        // Start 15s timeout coroutine
                        val timeoutJob = viewModelScope.launch {
                            kotlinx.coroutines.delay(15000)
                            if (!isCompletedOrFailed) {
                                isCompletedOrFailed = true
                                listenerRegistration?.let {
                                    it.remove()
                                    activeUnpairListeners.remove(it)
                                }
                                android.util.Log.e("DeviceViewModel", "Unpair operation timed out.")
                                android.widget.Toast.makeText(application, "Unpairing timed out. Check child connection and retry.", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                        
                        // Attach snapshot listener
                        listenerRegistration = docRef.addSnapshotListener { snapshot, e ->
                            if (e != null) {
                                android.util.Log.e("DeviceViewModel", "Snapshot listener error: ", e)
                                if (e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                                    // Permission denied means Child has successfully unpaired and deleted authorizedParents entry!
                                    // Treat this as successful unpairing completion.
                                    isCompletedOrFailed = true
                                    listenerRegistration?.let {
                                        it.remove()
                                        activeUnpairListeners.remove(it)
                                    }
                                    timeoutJob.cancel()
                                    viewModelScope.launch {
                                        pairedDeviceDao.deleteDeviceByUuid(device.uuid)
                                        android.widget.Toast.makeText(application, "Successfully unpaired device.", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                                return@addSnapshotListener
                            }
                            if (snapshot != null && !isCompletedOrFailed) {
                                if (!snapshot.exists()) {
                                    // Document deleted means Child completed cleanup and deleted the command document!
                                    // Treat this as successful unpairing completion.
                                    isCompletedOrFailed = true
                                    listenerRegistration?.let {
                                        it.remove()
                                        activeUnpairListeners.remove(it)
                                    }
                                    timeoutJob.cancel()
                                    viewModelScope.launch {
                                        pairedDeviceDao.deleteDeviceByUuid(device.uuid)
                                        android.widget.Toast.makeText(application, "Successfully unpaired device.", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                    return@addSnapshotListener
                                }
                                
                                val statusStr = snapshot.getString("status")
                                val result = snapshot.getString("result") ?: ""
                                if (statusStr == com.ai.guardian.data.remote.models.CommandStatus.COMPLETED.name) {
                                    isCompletedOrFailed = true
                                    listenerRegistration?.let {
                                        it.remove()
                                        activeUnpairListeners.remove(it)
                                    }
                                    timeoutJob.cancel()
                                    
                                    // Success: Delete local pairing and clear caches
                                    viewModelScope.launch {
                                        pairedDeviceDao.deleteDeviceByUuid(device.uuid)
                                        android.widget.Toast.makeText(application, "Successfully unpaired device.", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                } else if (statusStr == com.ai.guardian.data.remote.models.CommandStatus.FAILED.name) {
                                    isCompletedOrFailed = true
                                    listenerRegistration?.let {
                                        it.remove()
                                        activeUnpairListeners.remove(it)
                                    }
                                    timeoutJob.cancel()
                                    
                                    // Failed: Show error, keep pairing, allow retry
                                    android.widget.Toast.makeText(application, "Unpairing failed: $result", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        listenerRegistration?.let { activeUnpairListeners.add(it) }
                    } else {
                        android.util.Log.e("DeviceViewModel", "Failed to send REMOVE_PAIRING command", task.exception)
                        android.widget.Toast.makeText(application, "Failed to initiate unpairing. Check connection.", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("DeviceViewModel", "Failed to remove paired device", e)
            }
        }
    }
    
    fun regenerateMyPairCode(): String {
        return syncManager.regeneratePairCode()
    }

    private suspend fun recoverPendingUnpairCommands() {
        val parentUuid = syncManager.deviceUuid
        val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        val devices = pairedDeviceDao.getAllPairedDevicesSynchronous()
        val childDevices = devices.filter { !it.isParentDevice }
        
        for (child in childDevices) {
            try {
                val queryTask = firestore.collection("devices")
                    .document(child.uuid)
                    .collection("commands")
                    .whereEqualTo("commandType", com.ai.guardian.data.remote.models.CommandType.REMOVE_PAIRING.name)
                    .whereEqualTo("parentDeviceId", parentUuid)
                    .get()
                
                val snapshot = com.google.android.gms.tasks.Tasks.await(queryTask)
                for (doc in snapshot.documents) {
                    val statusStr = doc.getString("status") ?: ""
                    val docRef = doc.reference
                    val commandId = doc.id
                    val expiresAt = doc.getLong("expiresAt") ?: 0L
                    
                    if (statusStr == com.ai.guardian.data.remote.models.CommandStatus.COMPLETED.name) {
                        // Already completed! Clean up Room pairing immediately
                        pairedDeviceDao.deleteDeviceByUuid(child.uuid)
                        android.util.Log.d("DeviceViewModel", "Recovered unpair: Deleted pairing for child: ${child.uuid}")
                    } else if (statusStr == com.ai.guardian.data.remote.models.CommandStatus.PENDING.name || 
                               statusStr == com.ai.guardian.data.remote.models.CommandStatus.PROCESSING.name) {
                        // Pending or Processing: Resume waiting
                        resumeUnpairAcknowledgement(child, docRef, expiresAt)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("DeviceViewModel", "Failed to recover unpair for child: ${child.uuid}", e)
            }
        }
    }

    private fun resumeUnpairAcknowledgement(
        device: PairedDeviceEntity,
        docRef: com.google.firebase.firestore.DocumentReference,
        expiresAt: Long
    ) {
        var isCompletedOrFailed = false
        var listenerRegistration: com.google.firebase.firestore.ListenerRegistration? = null
        
        val now = System.currentTimeMillis()
        val remainingTimeout = (expiresAt - now).coerceIn(0L, 15000L) // Limit to max 15 seconds remaining
        
        // Start timeout job
        val timeoutJob = viewModelScope.launch {
            kotlinx.coroutines.delay(remainingTimeout)
            if (!isCompletedOrFailed) {
                isCompletedOrFailed = true
                listenerRegistration?.let {
                    it.remove()
                    activeUnpairListeners.remove(it)
                }
                android.util.Log.e("DeviceViewModel", "Recovered unpair timed out for device ${device.uuid}")
            }
        }
        
        listenerRegistration = docRef.addSnapshotListener { snapshot, e ->
            if (e != null) {
                android.util.Log.e("DeviceViewModel", "Recovered snapshot listener error: ", e)
                if (e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    isCompletedOrFailed = true
                    listenerRegistration?.let {
                        it.remove()
                        activeUnpairListeners.remove(it)
                    }
                    timeoutJob.cancel()
                    viewModelScope.launch {
                        pairedDeviceDao.deleteDeviceByUuid(device.uuid)
                        android.util.Log.d("DeviceViewModel", "Recovered unpair completed via PERMISSION_DENIED for device ${device.uuid}")
                    }
                }
                return@addSnapshotListener
            }
            if (snapshot != null && !isCompletedOrFailed) {
                if (!snapshot.exists()) {
                    isCompletedOrFailed = true
                    listenerRegistration?.let {
                        it.remove()
                        activeUnpairListeners.remove(it)
                    }
                    timeoutJob.cancel()
                    viewModelScope.launch {
                        pairedDeviceDao.deleteDeviceByUuid(device.uuid)
                        android.util.Log.d("DeviceViewModel", "Recovered unpair completed via DELETION for device ${device.uuid}")
                    }
                    return@addSnapshotListener
                }
                
                val statusStr = snapshot.getString("status")
                val result = snapshot.getString("result") ?: ""
                if (statusStr == com.ai.guardian.data.remote.models.CommandStatus.COMPLETED.name) {
                    isCompletedOrFailed = true
                    listenerRegistration?.let {
                        it.remove()
                        activeUnpairListeners.remove(it)
                    }
                    timeoutJob.cancel()
                    
                    viewModelScope.launch {
                        pairedDeviceDao.deleteDeviceByUuid(device.uuid)
                        android.util.Log.d("DeviceViewModel", "Recovered unpair COMPLETED for device ${device.uuid}")
                    }
                } else if (statusStr == com.ai.guardian.data.remote.models.CommandStatus.FAILED.name) {
                    isCompletedOrFailed = true
                    listenerRegistration?.let {
                        it.remove()
                        activeUnpairListeners.remove(it)
                    }
                    timeoutJob.cancel()
                    android.util.Log.e("DeviceViewModel", "Recovered unpair FAILED for device ${device.uuid}: $result")
                }
            }
        }
        listenerRegistration?.let { activeUnpairListeners.add(it) }
    }

    override fun onCleared() {
        super.onCleared()
        synchronized(activeUnpairListeners) {
            for (listener in activeUnpairListeners) {
                listener.remove()
            }
            activeUnpairListeners.clear()
        }
    }
}
