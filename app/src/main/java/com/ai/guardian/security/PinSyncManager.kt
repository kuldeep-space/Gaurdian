package com.ai.guardian.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ai.guardian.data.AppDatabase
import com.ai.guardian.data.entity.PairedDeviceEntity
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates End-to-End Encrypted (E2EE) PIN Sync between Child and Parent.
 * Manages the offline retry queue, key rotation, and replay/metadata checks.
 */
class PinSyncManager(private val context: Context) {

    private val firestore = FirebaseFirestore.getInstance()
    private val db = AppDatabase.getDatabase(context)
    private val syncScope = CoroutineScope(Dispatchers.IO)
    private val isSyncing = AtomicBoolean(false)

    // Bounded LRU cache of size 100 for replay protection on the Parent side
    private val processedPayloadIds = object : java.util.LinkedHashMap<String, Boolean>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
            return size > 100
        }
    }

    // In-memory cache for Parent device's last decrypted/processed updatedAt timestamp
    @Volatile
    private var lastProcessedTimestamp: Long = 0L

    private val securePrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Enqueues a PIN change from the Child device to be securely synced to the Parent.
     * Overwrites any existing unsynced PIN payload to ensure only the latest is active.
     */
    fun queuePinSync(pin: CharArray) {
        syncScope.launch {
            try {
                val parent = db.pairedDeviceDao().getParentDevice()
                if (parent == null) {
                    Log.w(TAG, "No paired Parent device found. Skipping PIN sync queue.")
                    return@launch
                }

                val parentKeyPem = parent.parentPublicKeyPem
                val keyId = parent.parentPublicKeyId
                if (parentKeyPem.isNullOrEmpty() || keyId.isNullOrEmpty()) {
                    Log.w(TAG, "Parent public key not available. PIN sync will retry once key is received.")
                    return@launch
                }

                val container = (context.applicationContext as com.ai.guardian.GuardianApplication).container
                val childDeviceId = container.deviceSyncManager.deviceUuid
                val pairId = parent.uuid // pairing session ID is the parent's UUID

                // Encrypt using Hybrid Cryptography
                val payload = PinSyncCryptography.encryptPin(
                    pin = pin,
                    parentPublicKeyPem = parentKeyPem,
                    keyId = keyId,
                    keyVersion = parent.parentKeyVersion,
                    pairId = pairId,
                    parentDeviceId = parent.uuid,
                    childDeviceId = childDeviceId
                )

                // Save to persistent queue (EncryptedSharedPreferences)
                savePayloadToQueue(payload)
                Log.d(TAG, "E2EE PIN payload successfully queued locally.")

                // Trigger immediate sync attempt
                triggerSync()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to queue PIN sync payload", e)
            } finally {
                PinSyncCryptography.clearPin(pin)
            }
        }
    }

    /**
     * Triggers execution of the offline retry queue.
     */
    fun triggerSync() {
        if (!isNetworkAvailable()) {
            Log.d(TAG, "Network unavailable. PIN sync will retry once connection is restored.")
            return
        }

        if (isSyncing.compareAndSet(false, true)) {
            syncScope.launch {
                try {
                    processQueue()
                } finally {
                    isSyncing.set(false)
                }
            }
        }
    }

    /**
     * Atomic upload processor of the persistent queue.
     */
    private suspend fun processQueue() = withContext(Dispatchers.IO) {
        val queue = getQueue()
        if (queue.isEmpty()) return@withContext

        val container = (context.applicationContext as com.ai.guardian.GuardianApplication).container
        val childDeviceId = container.deviceSyncManager.deviceUuid

        for (item in queue) {
            val payload = item.payload
            val now = System.currentTimeMillis()

            if (now < item.nextRetryTime) continue

            try {
                val stateData = mapOf(
                    "payloadId" to payload.payloadId,
                    "payloadVersion" to payload.payloadVersion,
                    "algorithmVersion" to payload.algorithmVersion,
                    "encryptedPin" to payload.encryptedPin,
                    "encryptedAesKey" to payload.encryptedAesKey,
                    "algorithm" to payload.algorithm,
                    "keyId" to payload.keyId,
                    "keyVersion" to payload.keyVersion,
                    "nonce" to payload.nonce,
                    "updatedAt" to payload.updatedAt,
                    "metadata" to mapOf(
                        "pairId" to payload.pairId,
                        "parentDeviceId" to payload.parentDeviceId,
                        "childDeviceId" to payload.childDeviceId
                    )
                )

                firestore.collection("devices").document(childDeviceId)
                    .collection("state").document("current")
                    .set(stateData, com.google.firebase.firestore.SetOptions.merge()).await()

                Log.d(TAG, "E2EE PIN payload successfully synced to Firestore.")
                removePayloadFromQueue(payload.payloadId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upload E2EE PIN payload", e)
                val backoffMultiplier = Math.pow(2.0, item.retryCount.toDouble()).toLong()
                val nextRetryDelay = Math.min(300000L, 5000L * backoffMultiplier) // Cap backoff at 5 mins

                updateQueueItem(
                    item.copy(
                        retryCount = item.retryCount + 1,
                        nextRetryTime = now + nextRetryDelay,
                        lastError = e.message
                    )
                )
            }
        }
    }

    /**
     * Decrypts a received status snapshot on the Parent device.
     * Performs strict metadata validation, timestamp verification, and replay checks.
     */
    fun decryptReceivedPayload(
        state: Map<String, Any>?,
        parentKeyManager: ParentKeyManager,
        currentParentId: String,
        currentChildId: String,
        currentPairId: String
    ): CharArray? {
        if (state == null) return null

        try {
            val payloadId = state["payloadId"] as? String ?: return null
            val payloadVersion = (state["payloadVersion"] as? Number)?.toInt() ?: return null
            val algorithmVersion = (state["algorithmVersion"] as? Number)?.toInt() ?: return null
            val encryptedPin = state["encryptedPin"] as? String ?: return null
            val encryptedAesKey = state["encryptedAesKey"] as? String ?: return null
            val algorithm = state["algorithm"] as? String ?: return null
            val keyId = state["keyId"] as? String ?: return null
            val keyVersion = (state["keyVersion"] as? Number)?.toInt() ?: return null
            val nonce = state["nonce"] as? String ?: return null
            val updatedAt = (state["updatedAt"] as? Number)?.toLong() ?: return null

            val metadata = state["metadata"] as? Map<*, *> ?: return null
            val pairId = metadata["pairId"] as? String ?: return null
            val parentDeviceId = metadata["parentDeviceId"] as? String ?: return null
            val childDeviceId = metadata["childDeviceId"] as? String ?: return null

            // 1. Validation Checks
            if (payloadVersion != 1 || algorithmVersion != 1) {
                Log.e(TAG, "Unsupported payload/algorithm version: payload=$payloadVersion, algorithm=$algorithmVersion")
                return null
            }

            // 2. Metadata / Pairing Matching
            if (pairId != currentPairId || parentDeviceId != currentParentId || childDeviceId != currentChildId) {
                Log.e(TAG, "Pairing metadata mismatch. Aborting decryption.")
                return null
            }

            // 3. Replay & Stale Payload Rejection
            synchronized(processedPayloadIds) {
                if (processedPayloadIds.containsKey(payloadId)) {
                    return null
                }
                if (updatedAt <= lastProcessedTimestamp) {
                    Log.w(TAG, "Stale payload timestamp detected ($updatedAt <= $lastProcessedTimestamp). Ignoring.")
                    return null
                }
                processedPayloadIds[payloadId] = true
                lastProcessedTimestamp = updatedAt
            }

            // 4. Retrieve RSA Private Key Entry
            val privateKeyEntry = parentKeyManager.getPrivateKeyEntry(keyId)
            if (privateKeyEntry == null) {
                Log.e(TAG, "Decryption key entry missing for keyId: $keyId")
                return null
            }

            // 5. Decrypt
            val payload = EncryptedPayload(
                payloadId = payloadId,
                payloadVersion = payloadVersion,
                algorithmVersion = algorithmVersion,
                encryptedPin = encryptedPin,
                encryptedAesKey = encryptedAesKey,
                algorithm = algorithm,
                keyId = keyId,
                keyVersion = keyVersion,
                nonce = nonce,
                updatedAt = updatedAt,
                pairId = pairId,
                parentDeviceId = parentDeviceId,
                childDeviceId = childDeviceId
            )

            return PinSyncCryptography.decryptPin(payload, privateKeyEntry)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed during received payload processing", e)
            return null
        }
    }

    // --- Persistent Queue Helpers ---

    private fun getQueue(): List<QueueItem> {
        val jsonStr = securePrefs.getString(PREF_KEY_QUEUE, "[]") ?: "[]"
        val array = JSONArray(jsonStr)
        val result = mutableListOf<QueueItem>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            result.add(
                QueueItem(
                    payload = jsonToPayload(obj.getString("payload")),
                    retryCount = obj.optInt("retryCount", 0),
                    nextRetryTime = obj.optLong("nextRetryTime", 0L),
                    lastError = obj.optString("lastError", null)
                )
            )
        }
        return result
    }

    private fun savePayloadToQueue(payload: EncryptedPayload) {
        val queue = getQueue().toMutableList()
        // Replace matching payload if exists, or add new
        queue.removeAll { it.payload.payloadId == payload.payloadId }
        queue.add(QueueItem(payload = payload))
        saveQueue(queue)
    }

    private fun removePayloadFromQueue(payloadId: String) {
        val queue = getQueue().toMutableList()
        queue.removeAll { it.payload.payloadId == payloadId }
        saveQueue(queue)
    }

    private fun updateQueueItem(updatedItem: QueueItem) {
        val queue = getQueue().toMutableList()
        val index = queue.indexOfFirst { it.payload.payloadId == updatedItem.payload.payloadId }
        if (index != -1) {
            queue[index] = updatedItem
            saveQueue(queue)
        }
    }

    private fun saveQueue(queue: List<QueueItem>) {
        val array = JSONArray()
        for (item in queue) {
            val obj = JSONObject()
            obj.put("payload", payloadToJson(item.payload))
            obj.put("retryCount", item.retryCount)
            obj.put("nextRetryTime", item.nextRetryTime)
            obj.put("lastError", item.lastError)
            array.put(obj)
        }
        securePrefs.edit().putString(PREF_KEY_QUEUE, array.toString()).apply()
    }

    private fun payloadToJson(p: EncryptedPayload): String {
        val obj = JSONObject()
        obj.put("payloadId", p.payloadId)
        obj.put("payloadVersion", p.payloadVersion)
        obj.put("algorithmVersion", p.algorithmVersion)
        obj.put("encryptedPin", p.encryptedPin)
        obj.put("encryptedAesKey", p.encryptedAesKey)
        obj.put("algorithm", p.algorithm)
        obj.put("keyId", p.keyId)
        obj.put("keyVersion", p.keyVersion)
        obj.put("nonce", p.nonce)
        obj.put("updatedAt", p.updatedAt)
        obj.put("pairId", p.pairId)
        obj.put("parentDeviceId", p.parentDeviceId)
        obj.put("childDeviceId", p.childDeviceId)
        return obj.toString()
    }

    private fun jsonToPayload(json: String): EncryptedPayload {
        val obj = JSONObject(json)
        return EncryptedPayload(
            payloadId = obj.getString("payloadId"),
            payloadVersion = obj.getInt("payloadVersion"),
            algorithmVersion = obj.getInt("algorithmVersion"),
            encryptedPin = obj.getString("encryptedPin"),
            encryptedAesKey = obj.getString("encryptedAesKey"),
            algorithm = obj.getString("algorithm"),
            keyId = obj.getString("keyId"),
            keyVersion = obj.getInt("keyVersion"),
            nonce = obj.getString("nonce"),
            updatedAt = obj.getLong("updatedAt"),
            pairId = obj.getString("pairId"),
            parentDeviceId = obj.getString("parentDeviceId"),
            childDeviceId = obj.getString("childDeviceId")
        )
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val activeNetwork = cm?.activeNetworkInfo
        return activeNetwork != null && activeNetwork.isConnected
    }

    data class QueueItem(
        val payload: EncryptedPayload,
        val retryCount: Int = 0,
        val nextRetryTime: Long = 0L,
        val lastError: String? = null
    )

    companion object {
        private const val TAG = "PinSyncManager"
        private const val SECURE_PREFS_NAME = "guardian_pinsync_secure_prefs"
        private const val PREF_KEY_QUEUE = "pending_pin_sync_queue"

        @Volatile
        private var INSTANCE: PinSyncManager? = null

        fun getInstance(context: Context): PinSyncManager {
            return INSTANCE ?: synchronized(this) {
                val instance = PinSyncManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
