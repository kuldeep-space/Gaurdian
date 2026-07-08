package com.ai.guardian.data.remote

import android.content.Context
import android.util.Log
import com.ai.guardian.data.dao.PairedDeviceDao
import com.ai.guardian.data.remote.models.SyncStatus
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.tasks.await

enum class SyncEngineState {
    STOPPED,
    STARTING,
    RUNNING,
    RECOVERING,
    STOPPING
}

class SyncEngineManager(
    private val context: Context,
    val deviceSyncManager: DeviceSyncManager,
    private val pairedDeviceDao: PairedDeviceDao
) {
    private val firestore = FirebaseFirestore.getInstance()
    private val authManager = FirebaseAuthManager.getInstance(context)

    private val _engineState = MutableStateFlow(SyncEngineState.STOPPED)
    val engineState: StateFlow<SyncEngineState> = _engineState.asStateFlow()

    private var engineScope: CoroutineScope? = null
    private var appsListener: ListenerRegistration? = null
    private var requestsListener: ListenerRegistration? = null
    private var commandsListener: ListenerRegistration? = null
    private var heartbeatJob: kotlinx.coroutines.Job? = null

    // Metrics
    var activeListenerCount: Int = 0
        private set
    var lastSyncTimestamp: Long = 0L
        private set
    var recoveryCount: Int = 0
        private set

    @Synchronized
    fun start() {
        if (_engineState.value != SyncEngineState.STOPPED) {
            Log.d("SyncEngineManager", "Engine is already ${_engineState.value}")
            return
        }

        _engineState.value = SyncEngineState.STARTING
        Log.d("SyncEngineManager", "SyncEngineManager starting...")

        // Trigger E2EE PIN sync on startup/sync engine start
        com.ai.guardian.security.PinSyncManager.getInstance(context).triggerSync()

        engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        
        // Health check / Observation loop
        engineScope?.launch {
            combine(
                authManager.authState,
                pairedDeviceDao.getAllPairedDevices()
            ) { authState, pairedDevices ->
                Pair(authState, pairedDevices)
            }.collect { (authState, pairedDevices) ->
                if ((authState == AuthStatus.AUTHENTICATED || authState == AuthStatus.FAILED || authState == AuthStatus.NETWORK_UNAVAILABLE) && pairedDevices.isNotEmpty()) {
                    if (_engineState.value != SyncEngineState.RUNNING) {
                        attachListeners()
                        _engineState.value = SyncEngineState.RUNNING
                    }
                } else {
                    if (_engineState.value == SyncEngineState.RUNNING) {
                        Log.d("SyncEngineManager", "Requirements not met. Detaching listeners. Auth=$authState, Paired=${pairedDevices.size}")
                        detachListeners()
                        _engineState.value = SyncEngineState.RECOVERING
                        recoveryCount++
                    }
                    if (pairedDevices.isEmpty()) {
                        stop()
                        // Run background cleanup asynchronously
                        CoroutineScope(Dispatchers.IO).launch {
                            deviceSyncManager.cleanUpAllRemoteDataQuietly()
                        }
                    }
                }
            }
        }

        // Observe local settings changes and immediately replicate them to Firestore (Immediate change-driven propagation flow)
        engineScope?.launch {
            val db = com.ai.guardian.data.AppDatabase.getDatabase(context)
            val settingsDao = db.deviceSettingsDao()
            val uuid = deviceSyncManager.deviceUuid
            
            var lastProtectionEnabled: Boolean? = null
            var lastRemotelyLocked: Boolean? = null
            var lastLivenessEnabled: Boolean? = null
            var lastConfigVersion: Long? = null
            var lastPinConfigured: Boolean? = null
            var lastPinVersion: Int? = null
            var lastPinUpdatedAt: Long? = null
            var lastPinResetRequired: Boolean? = null
            
            settingsDao.getSettingsFlow().collect { settings ->
                if (settings != null) {
                    val isChild = deviceSyncManager.isChildDevice()
                    val hasParents = pairedDeviceDao.getAllPairedDevicesSynchronous().isNotEmpty()
                    if (isChild && hasParents && isNetworkAvailable(context)) {
                        val protectionChanged = settings.isProtectionEnabled != lastProtectionEnabled
                        val lockedChanged = settings.isRemotelyLocked != lastRemotelyLocked
                        val livenessChanged = settings.isLivenessDetectionEnabled != lastLivenessEnabled
                        val versionChanged = settings.configurationVersion != lastConfigVersion
                        val pinConfiguredChanged = settings.isPinConfigured != lastPinConfigured
                        val pinVersionChanged = settings.pinVersion != lastPinVersion
                        val pinUpdatedChanged = settings.pinUpdatedAt != lastPinUpdatedAt
                        val pinResetChanged = settings.pinResetRequired != lastPinResetRequired
                        
                        if (protectionChanged || lockedChanged || livenessChanged || versionChanged ||
                            pinConfiguredChanged || pinVersionChanged || pinUpdatedChanged || pinResetChanged) {
                            try {
                                val stateData = mapOf(
                                    "protectionEnabled" to settings.isProtectionEnabled,
                                    "remotelyLocked" to settings.isRemotelyLocked,
                                    "configurationVersion" to settings.configurationVersion,
                                    "accessibilityEnabled" to com.ai.guardian.services.GuardianAccessibilityService.isServiceRunning(),
                                    "livenessEnabled" to settings.isLivenessDetectionEnabled,
                                    "pinConfigured" to settings.isPinConfigured,
                                    "pinVersion" to settings.pinVersion,
                                    "pinUpdatedAt" to settings.pinUpdatedAt,
                                    "pinResetRequired" to settings.pinResetRequired,
                                    "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                                )
                                firestore.collection("devices").document(uuid)
                                    .collection("state").document("current")
                                    .set(stateData, com.google.firebase.firestore.SetOptions.merge()).await()
                                
                                // Update cache on successful write completion
                                lastProtectionEnabled = settings.isProtectionEnabled
                                lastRemotelyLocked = settings.isRemotelyLocked
                                lastLivenessEnabled = settings.isLivenessDetectionEnabled
                                lastConfigVersion = settings.configurationVersion
                                lastPinConfigured = settings.isPinConfigured
                                lastPinVersion = settings.pinVersion
                                lastPinUpdatedAt = settings.pinUpdatedAt
                                lastPinResetRequired = settings.pinResetRequired
                                Log.d("SyncEngineManager", "Immediate change-driven local settings sync to Firestore: $stateData")
                            } catch (e: Exception) {
                                Log.e("SyncEngineManager", "Failed to immediately sync settings to Firestore", e)
                            }
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        if (_engineState.value == SyncEngineState.STOPPED) return
        
        _engineState.value = SyncEngineState.STOPPING
        Log.d("SyncEngineManager", "SyncEngineManager stopping...")
        
        detachListeners()
        engineScope?.cancel()
        engineScope = null
        
        _engineState.value = SyncEngineState.STOPPED
    }

    private suspend fun attachListeners() {
        if (appsListener != null || requestsListener != null) return

        val uuid = deviceSyncManager.deviceUuid
        val deviceDoc = firestore.collection("devices").document(uuid)

        Log.d("SyncEngineManager", "Attaching Firestore listeners for $uuid")

        // 1. Sync Requests Listener
        requestsListener = deviceDoc.collection("syncRequests")
            .whereEqualTo("status", SyncStatus.PENDING.name)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e("SyncEngineManager", "Sync requests listener failed.", e)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    lastSyncTimestamp = System.currentTimeMillis()
                    deviceSyncManager.processSyncRequestsSnapshot(snapshot)
                }
            }

        val isChild = deviceSyncManager.isChildDevice()
        
        // 2. Apps Protection Listener (CHILD ONLY)
        if (isChild) {
            appsListener = deviceDoc.collection("apps")
                .addSnapshotListener { snapshot, e ->
                    if (e != null) {
                        Log.e("SyncEngineManager", "Apps listener failed.", e)
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        lastSyncTimestamp = System.currentTimeMillis()
                        if (snapshot.isEmpty) {
                            Log.d("SyncEngineManager", "Automatic Bootstrap Recovery triggered. Remote apps collection is empty.")
                            engineScope?.launch {
                                try {
                                    deviceSyncManager.executeFullSync(null)
                                } catch (ex: Exception) {
                                    Log.e("SyncEngineManager", "Failed to execute bootstrap sync", ex)
                                }
                            }
                        } else {
                            deviceSyncManager.processAppsSnapshot(snapshot)
                        }
                    }
                }
            
            // 3. Remote Commands Listener (CHILD ONLY)
            commandsListener = deviceDoc.collection("commands")
                .whereEqualTo("status", com.ai.guardian.data.remote.models.CommandStatus.PENDING.name)
                .addSnapshotListener { snapshot, e ->
                    if (e != null) {
                        Log.e("SyncEngineManager", "Commands listener failed.", e)
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        lastSyncTimestamp = System.currentTimeMillis()
                        deviceSyncManager.processCommandsSnapshot(snapshot)
                    }
                }
                
            activeListenerCount = 3
            startHeartbeat()
        } else {
            activeListenerCount = 1
        }
    }

    fun triggerImmediateHeartbeat() {
        if (_engineState.value == SyncEngineState.RUNNING) {
            startHeartbeat()
        }
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val net = cm?.activeNetwork ?: return false
            val cap = cm.getNetworkCapabilities(net) ?: return false
            cap.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                    cap.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    cap.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (e: Exception) {
            false
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = engineScope?.launch {
            val uuid = deviceSyncManager.deviceUuid
            val deviceDoc = firestore.collection("devices").document(uuid)
            
            // PackageInfo caching to avoid PackageManager queries in high frequency loops
            val packageInfoCache = try {
                context.packageManager.getPackageInfo(context.packageName, 0)
            } catch (e: Exception) {
                null
            }

            while (engineScope?.isActive == true) {
                val isAccessibilityRunning = com.ai.guardian.services.GuardianAccessibilityService.isServiceRunning()
                val pairedParents = pairedDeviceDao.getAllPairedDevicesSynchronous()
                val hasNetwork = isNetworkAvailable(context)
                val isChild = deviceSyncManager.isChildDevice()

                if (isChild && pairedParents.isNotEmpty() && hasNetwork) {
                    try {
                        val db = com.ai.guardian.data.AppDatabase.getDatabase(context)
                        val settingsDao = db.deviceSettingsDao()
                        val settings = settingsDao.getSettings()
                        
                        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                        val batteryLevel = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                        
                        val appVersion = packageInfoCache?.versionName ?: "Unknown"
                        
                        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                        val isActive = powerManager.isInteractive
                        
                        val presenceData = mapOf(
                            "lastSeen" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                            "isOnline" to true,
                            "batteryLevel" to batteryLevel
                        )
                        
                        val stateData = mapOf(
                            "appVersion" to appVersion,
                            "protectionEnabled" to (settings?.isProtectionEnabled ?: false),
                            "remotelyLocked" to (settings?.isRemotelyLocked ?: false),
                            "configurationVersion" to (settings?.configurationVersion ?: 1L),
                            "accessibilityEnabled" to isAccessibilityRunning,
                            "livenessEnabled" to (settings?.isLivenessDetectionEnabled ?: false),
                            // Security PIN metadata (hash/iv/salt are NEVER synced — only status)
                            "pinConfigured" to (settings?.isPinConfigured ?: false),
                            "pinVersion" to (settings?.pinVersion ?: 0),
                            "pinUpdatedAt" to (settings?.pinUpdatedAt ?: 0L),
                            "pinResetRequired" to (settings?.pinResetRequired ?: false),
                            "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                        )
                        
                        deviceDoc.collection("presence").document("current").set(presenceData, com.google.firebase.firestore.SetOptions.merge())
                        deviceDoc.collection("state").document("current").set(stateData, com.google.firebase.firestore.SetOptions.merge())
                        
                        // Trigger E2EE PIN sync retry
                        com.ai.guardian.security.PinSyncManager.getInstance(context).triggerSync()
                        
                        // 1. Clean up old completed/failed commands (older than 10 minutes)
                        try {
                            val cutoffTime = System.currentTimeMillis() - 600000L
                            val oldCommands = deviceDoc.collection("commands")
                                .whereLessThan("completedAt", cutoffTime)
                                .get().await()
                            if (!oldCommands.isEmpty) {
                                val cleanBatch = firestore.batch()
                                for (doc in oldCommands.documents) {
                                    cleanBatch.delete(doc.reference)
                                }
                                cleanBatch.commit().await()
                                Log.d("SyncEngineManager", "Cleaned up ${oldCommands.size()} expired audit commands.")
                            }
                        } catch (ex: Exception) {
                            Log.e("SyncEngineManager", "Failed to clean up expired commands", ex)
                        }
                        
                        Log.d("SyncEngineManager", "Heartbeat sent. Active: $isActive, Accessibility: $isAccessibilityRunning")
                    } catch (e: Exception) {
                        Log.e("SyncEngineManager", "Failed to send heartbeat", e)
                    }
                } else {
                    Log.d("SyncEngineManager", "Skipping heartbeat: Parents:${pairedParents.size}, Access:$isAccessibilityRunning, Network:$hasNetwork")
                }
                
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                val delayTime = if (powerManager.isInteractive) 60_000L else 300_000L // 1 min active, 5 min idle
                kotlinx.coroutines.delay(delayTime)
            }
        }
    }

    private fun detachListeners() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        appsListener?.remove()
        appsListener = null
        requestsListener?.remove()
        requestsListener = null
        commandsListener?.remove()
        commandsListener = null
        activeListenerCount = 0
        Log.d("SyncEngineManager", "Firestore listeners detached.")
    }

    // --- Public Command API ---

    fun requestInitialSync() {
        engineScope?.launch {
            Log.d("SyncEngineManager", "Command received: requestInitialSync")
            deviceSyncManager.executeFullSync(null)
        }
    }

    fun requestFullSync() {
        engineScope?.launch {
            Log.d("SyncEngineManager", "Command received: requestFullSync")
            deviceSyncManager.executeFullSync(null)
        }
    }

    fun requestRemoteToggle(remoteDeviceId: String, packageName: String, isProtected: Boolean) {
        engineScope?.launch {
            Log.d("SyncEngineManager", "Command received: requestRemoteToggle for $packageName on $remoteDeviceId")
            // Parent toggling child's app
            deviceSyncManager.syncRemoteToggle(remoteDeviceId, packageName, isProtected)
        }
    }

    fun requestPackageAdded(packageName: String) {
        engineScope?.launch {
            Log.d("SyncEngineManager", "Command received: requestPackageAdded for $packageName")
            deviceSyncManager.syncSingleAppChange(packageName, false, context.packageManager)
        }
    }

    fun requestPackageRemoved(packageName: String) {
        engineScope?.launch {
            Log.d("SyncEngineManager", "Command received: requestPackageRemoved for $packageName")
            deviceSyncManager.syncSingleAppChange(packageName, true, context.packageManager)
        }
    }


    fun requestLocalAppSync(apps: List<com.ai.guardian.data.entity.AppLockEntity>) {
        engineScope?.launch {
            Log.d("SyncEngineManager", "Command received: requestLocalAppSync")
            deviceSyncManager.syncLocalAppsToFirestore(apps, context.packageManager)
        }
    }

    fun removePairing(deviceId: String) {
        engineScope?.launch {
            Log.d("SyncEngineManager", "Command received: removePairing for $deviceId")
            pairedDeviceDao.deleteDeviceByUuid(deviceId)
            
            try {
                // Cleanup pending requests in Firebase
                val dbRef = firestore.collection("devices")
                    .document(deviceId)
                    .collection("syncRequests")
                
                // We only delete requests originated by US
                dbRef.whereEqualTo("requesterId", deviceSyncManager.deviceUuid)
                     .get()
                     .addOnSuccessListener { snapshot ->
                         val batch = firestore.batch()
                         for (doc in snapshot.documents) {
                             batch.delete(doc.reference)
                         }
                         batch.commit()
                     }
            } catch (e: Exception) {
                Log.e("SyncEngineManager", "Failed to cleanup remote pairing data", e)
            }
        }
    }
}
