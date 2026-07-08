package com.ai.guardian.data.remote

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.room.withTransaction
import com.ai.guardian.data.AppDatabase
import com.ai.guardian.data.entity.AppLockEntity
import com.ai.guardian.data.remote.models.RemoteAppModel
import com.ai.guardian.services.GuardianAccessibilityService
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class DeviceSyncManager(private val context: Context) {
    private val firestore = FirebaseFirestore.getInstance()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val sharedPrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "device_pairing_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val syncSessionMutex = Mutex()
    private val commandProcessorMutex = Mutex()
    var currentSessionId: String? = null
        private set

    suspend fun isChildDevice(): Boolean {
        val db = AppDatabase.getDatabase(context)
        val pairedDevices = db.pairedDeviceDao().getAllPairedDevicesSynchronous()
        // If there's at least one Parent above us, we are acting as a Child device
        return pairedDevices.any { it.isParentDevice }
    }

    // Lazily generates or retrieves UUID and pairing code
    val deviceUuid: String
        get() {
            var uuid = sharedPrefs.getString("device_uuid", null)
            if (uuid == null) {
                uuid = UUID.randomUUID().toString()
                sharedPrefs.edit().putString("device_uuid", uuid).apply()
            }
            return uuid
        }

    var pairCode: String
        get() {
            var code = sharedPrefs.getString("pair_code", null)
            if (code == null) {
                code = generatePairCode()
                sharedPrefs.edit().putString("pair_code", code).apply()
            }
            return code
        }
        private set(value) {
            sharedPrefs.edit().putString("pair_code", value).apply()
        }

    fun regeneratePairCode(): String {
        val newCode = generatePairCode()
        pairCode = newCode
        return newCode
    }

    private fun generatePairCode(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..6).map { chars.random() }.joinToString("")
    }

    // =========================================================================
    // CHILD-SPECIFIC OPERATIONS (Used when running in Child mode)
    // =========================================================================
    suspend fun executeFullSync(requestId: String? = null) {
        if (!isChildDevice()) {
            Log.d("DeviceSyncManager", "executeFullSync aborted: device is not a CHILD.")
            return
        }
        
        syncSessionMutex.withLock {
            val sessionId = UUID.randomUUID().toString()
            currentSessionId = sessionId
            val startedAt = System.currentTimeMillis()
            val uuid = deviceUuid
            try {
                Log.d("DeviceSyncManager", "Execute full sync started [Session: $sessionId] for device: $uuid (Request: $requestId)")
                // Update device info
                val deviceName = android.os.Build.MODEL ?: "Unknown Device"
                val deviceData = mapOf("deviceName" to deviceName, "lastSyncTimestamp" to startedAt)
                firestore.collection("devices").document(uuid).set(deviceData, com.google.firebase.firestore.SetOptions.merge())

            val packageManager = context.packageManager
            val db = AppDatabase.getDatabase(context)
            val lockedApps = db.appLockDao().getAllApps()

            // Query all launchable apps
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = packageManager.queryIntentActivities(intent, 0)
            val allAppsInfo = resolveInfos.map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }
            Log.d("DeviceSyncManager", "Installed app count: ${allAppsInfo.size}, Protected count: ${lockedApps.count { it.isProtected }}")
            
            val lockedAppsMap = lockedApps.associateBy { it.packageName }

            val appsCollection = firestore.collection("devices").document(uuid).collection("apps")
            
            val chunkedApps = allAppsInfo.chunked(400)
            Log.d("DeviceSyncManager", "Batch upload started for ${allAppsInfo.size} apps in ${chunkedApps.size} chunks...")
            
            val batchTasks = mutableListOf<com.google.android.gms.tasks.Task<Void>>()
            
            for (chunk in chunkedApps) {
                val batch = firestore.batch()
                for (info in chunk) {
                    val pkg = info.packageName
                    val appName = try { packageManager.getApplicationLabel(info).toString() } catch (e: Exception) { pkg }
                    val isProtected = lockedAppsMap[pkg]?.isProtected ?: false
    
                    val remoteApp = RemoteAppModel(
                        packageName = pkg,
                        appName = appName,
                        isLocked = isProtected,
                        updatedBy = "child"
                    )
                    val docRef = appsCollection.document(pkg.replace(".", "_"))
                    batch.set(docRef, remoteApp)
                }
                batchTasks.add(batch.commit())
            }
            
            com.google.android.gms.tasks.Tasks.whenAllComplete(batchTasks).addOnCompleteListener { task ->
                val allCompletedTasks = task.result
                val failedTasks = allCompletedTasks.filter { !it.isSuccessful }
                
                if (failedTasks.isEmpty()) {
                    Log.d("DeviceSyncManager", "Batch upload success for all applications")
                    
                    // Increment appListVersion and protectionVersion, mark request COMPLETED
                    val metadataBatch = firestore.batch()
                    val rootDocRef = firestore.collection("devices").document(uuid)
                    metadataBatch.update(rootDocRef, "appListVersion", com.google.firebase.firestore.FieldValue.increment(1))
                    metadataBatch.update(rootDocRef, "protectionVersion", com.google.firebase.firestore.FieldValue.increment(1))
                    
                    if (requestId != null) {
                        val reqRef = rootDocRef.collection("syncRequests").document(requestId)
                        metadataBatch.update(reqRef, "status", com.ai.guardian.data.remote.models.SyncStatus.COMPLETED.name)
                    }
                    
                    metadataBatch.commit().addOnSuccessListener {
                        Log.d("DeviceSyncManager", "Successfully updated metadata and marked request completed.")
                        triggerHeartbeat()
                    }
                } else {
                    Log.e("DeviceSyncManager", "Batch upload finished with partial failures. ${failedTasks.size} batches failed.")
                    // Report failed request so it can be retried if needed, but don't crash.
                    // Successful batches are already in Firestore and won't be re-uploaded if the system determines they match locally later.
                    if (requestId != null) {
                        val reqRef = firestore.collection("devices").document(uuid).collection("syncRequests").document(requestId)
                        reqRef.update("status", com.ai.guardian.data.remote.models.SyncStatus.FAILED.name)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DeviceSyncManager", "Error executing full sync", e)
            if (requestId != null) {
                firestore.collection("devices").document(uuid)
                    .collection("syncRequests").document(requestId)
                    .update("status", com.ai.guardian.data.remote.models.SyncStatus.FAILED.name)
            }
        } finally {
            val completedAt = System.currentTimeMillis()
            Log.d("DeviceSyncManager", "Execute full sync completed [Session: $currentSessionId] at $completedAt")
            currentSessionId = null
        }
        }
    }

    suspend fun syncSingleAppChange(packageName: String, isRemoved: Boolean, packageManager: android.content.pm.PackageManager) {
        if (!isChildDevice()) {
            Log.d("DeviceSyncManager", "[Sync] syncSingleAppChange aborted: device is not a CHILD.")
            return
        }
        val uuid = deviceUuid
        val db = AppDatabase.getDatabase(context)
        try {
            val rootDocRef = firestore.collection("devices").document(uuid)
            val appsCollection = rootDocRef.collection("apps")
            val docRef = appsCollection.document(packageName.replace(".", "_"))

            if (isRemoved) {
                docRef.delete().await()
                rootDocRef.update("appListVersion", com.google.firebase.firestore.FieldValue.increment(1)).await()
                db.appLockDao().deleteAppByPackage(packageName)
                Log.d("DeviceSyncManager", "[Sync] App uninstalled and deleted locally & remotely: $packageName")
            } else {
                val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
                    addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                }
                val resolveInfos = packageManager.queryIntentActivities(intent, 0)
                val info = resolveInfos.find { it.activityInfo.packageName == packageName }?.activityInfo?.applicationInfo
                
                if (info != null) {
                    val appName = try { packageManager.getApplicationLabel(info).toString() } catch (e: Exception) { packageName }
                    val remoteApp = RemoteAppModel(
                        packageName = packageName,
                        appName = appName,
                        isLocked = false,
                        updatedBy = "child"
                    )
                    docRef.set(remoteApp).await()
                    rootDocRef.update("appListVersion", com.google.firebase.firestore.FieldValue.increment(1)).await()
                    
                    val appEntity = com.ai.guardian.data.entity.AppLockEntity(
                        packageName = packageName,
                        appName = appName,
                        isProtected = false,
                        lastServerTimestamp = 0L
                    )
                    db.appLockDao().insertApp(appEntity)
                    Log.d("DeviceSyncManager", "[Sync] App installed and added locally & remotely: $packageName")
                } else {
                    Log.w("DeviceSyncManager", "[Sync] App not found or not launchable: $packageName")
                }
            }
        } catch (e: Exception) {
            Log.e("DeviceSyncManager", "[Sync] Error in syncSingleAppChange", e)
        }
    }

    fun syncLocalAppsToFirestore(apps: List<AppLockEntity>, packageManager: android.content.pm.PackageManager) {
        coroutineScope.launch {
            if (!isChildDevice()) {
                Log.d("DeviceSyncManager", "syncLocalAppsToFirestore aborted: device is not a CHILD.")
                return@launch
            }
            val uuid = deviceUuid
            try {
                // First update the device info
                val deviceName = android.os.Build.MODEL ?: "Unknown Device"
                val deviceData = mapOf(
                    "deviceName" to deviceName,
                    "lastSync" to System.currentTimeMillis()
                )
                firestore.collection("devices").document(uuid).set(deviceData)

                // Now upload all apps to the subcollection
                val appsCollection = firestore.collection("devices").document(uuid).collection("apps")
                val chunkedApps = apps.chunked(400)
                val batchTasks = mutableListOf<com.google.android.gms.tasks.Task<Void>>()
                
                for (chunk in chunkedApps) {
                    val batch = firestore.batch()
                    for (app in chunk) {
                        val appName = try {
                            val info = packageManager.getApplicationInfo(app.packageName, 0)
                            packageManager.getApplicationLabel(info).toString()
                        } catch (e: Exception) {
                            app.packageName
                        }
    
                        val remoteApp = RemoteAppModel(
                            packageName = app.packageName,
                            appName = appName,
                            isLocked = app.isProtected,
                            updatedBy = "child"
                        )
                        
                        val docRef = appsCollection.document(app.packageName.replace(".", "_"))
                        batch.set(docRef, remoteApp)
                    }
                    batchTasks.add(batch.commit())
                }
                
                com.google.android.gms.tasks.Tasks.whenAllComplete(batchTasks).addOnCompleteListener { task ->
                    val failedTasks = task.result.filter { !it.isSuccessful }
                    
                    if (failedTasks.isEmpty()) {
                        // Increment only protectionVersion for local lock toggles
                        firestore.collection("devices").document(uuid)
                            .update("protectionVersion", com.google.firebase.firestore.FieldValue.increment(1))
                            .addOnSuccessListener {
                                android.widget.Toast.makeText(context, "Toggle Sync Success!", android.widget.Toast.LENGTH_SHORT).show()
                                Log.d("DeviceSyncManager", "Successfully synced apps and updated protectionVersion")
                            }
                    } else {
                        android.widget.Toast.makeText(context, "Toggle Sync completed with ${failedTasks.size} partial failures.", android.widget.Toast.LENGTH_LONG).show()
                        Log.e("DeviceSyncManager", "Failed to sync some apps to Firestore. ${failedTasks.size} batches failed.")
                    }
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Local Sync Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                Log.e("DeviceSyncManager", "Error in syncLocalAppsToFirestore", e)
            }
        }
    }

    fun processSyncRequestsSnapshot(snapshot: QuerySnapshot) {
        if (snapshot.isEmpty) return
        
        for (doc in snapshot.documents) {
            val syncRequest = doc.toObject(com.ai.guardian.data.remote.models.SyncRequest::class.java)
            if (syncRequest != null && syncRequest.status == com.ai.guardian.data.remote.models.SyncStatus.PENDING) {
                Log.d("DeviceSyncManager", "Pending sync request detected: ${syncRequest.requestId}")
                
                // Use a transaction to claim the request (prevent duplicate execution)
                firestore.runTransaction { transaction ->
                    val currentDoc = transaction.get(doc.reference)
                    val currentStatus = currentDoc.getString("status")
                    
                    if (currentStatus == com.ai.guardian.data.remote.models.SyncStatus.PENDING.name) {
                        transaction.update(doc.reference, "status", com.ai.guardian.data.remote.models.SyncStatus.PROCESSING.name)
                        true // Transaction success flag
                    } else {
                        false // Already processing or completed
                    }
                }.addOnSuccessListener { claimed ->
                    if (claimed == true) {
                        Log.d("DeviceSyncManager", "Successfully claimed sync request: ${syncRequest.requestId}")
                        // Now execute the full sync
                        coroutineScope.launch {
                            try {
                                executeFullSync(syncRequest.requestId)
                            } catch (e: Exception) {
                                Log.e("DeviceSyncManager", "Execution failed", e)
                                firestore.collection("devices").document(deviceUuid)
                                    .collection("syncRequests").document(syncRequest.requestId)
                                    .update("status", com.ai.guardian.data.remote.models.SyncStatus.FAILED.name)
                            }
                        }
                    }
                }.addOnFailureListener { ex ->
                    Log.e("DeviceSyncManager", "Failed to claim sync request: ${syncRequest.requestId}", ex)
                }
            }
        }
    }

    fun processAppsSnapshot(snapshot: QuerySnapshot) {
        if (snapshot.isEmpty) return

        coroutineScope.launch {
            val db = AppDatabase.getDatabase(context)
            val dao = db.appLockDao()
            
            var changedCount = 0
            
            // Fetch all local apps once for fast lookup
            val localApps = dao.getAllApps().associateBy { it.packageName }
            
            // Fetch all paired devices for validation
            val pairedDao = db.pairedDeviceDao()
            val pairedDevices = pairedDao.getAllPairedDevicesSynchronous().associateBy { it.uuid }
            
            // Iterate over documents
            for (document in snapshot.documents) {
                val remoteApp = document.toObject(RemoteAppModel::class.java) ?: continue
                val localApp = localApps[remoteApp.packageName]
                
                val remoteTime = remoteApp.timestamp?.time ?: 0L
                
                if (localApp != null) {
                    if (remoteApp.updatedBy == "parent") {
                        // 1. Validate Pairing
                        if (!pairedDevices.containsKey(remoteApp.updatedByDeviceId)) {
                            Log.w("DeviceSyncManager", "[Sync] Unauthorized remote change attempt from ${remoteApp.updatedByDeviceId}")
                            continue
                        }
                    }
                    
                    // 2. Handle Simultaneous Updates (Conflict Resolution: Last-Write-Wins using Server Timestamp)
                    if (remoteTime > localApp.lastServerTimestamp) {
                        try {
                            val updatedApp = localApp.copy(
                                isProtected = remoteApp.isLocked,
                                lastServerTimestamp = remoteTime
                            )
                            dao.updateApp(updatedApp)
                            changedCount++
                            Log.d("DeviceSyncManager", "[Room] Updated app ${remoteApp.packageName} to isProtected=${remoteApp.isLocked} (Remote: $remoteTime > Local: ${localApp.lastServerTimestamp})")
                            
                            // Send Local Android Notification if it was a parent update changing the state
                            if (remoteApp.updatedBy == "parent" && localApp.isProtected != remoteApp.isLocked) {
                                showLocalNotification(remoteApp.appName, remoteApp.isLocked)
                            }
                        } catch (e: Exception) {
                            Log.e("DeviceSyncManager", "[Room] Failed to update Room for app ${remoteApp.packageName}.", e)
                        }
                    } else {
                        Log.d("DeviceSyncManager", "[Sync] Ignored stale remote update for ${remoteApp.packageName} (Remote: $remoteTime <= Local: ${localApp.lastServerTimestamp})")
                    }
                } else {
                    // App is in Firestore but not in Room (newly installed or first-time sync)
                    try {
                        val newApp = AppLockEntity(
                            packageName = remoteApp.packageName,
                            appName = remoteApp.appName,
                            isProtected = remoteApp.isLocked,
                            lastServerTimestamp = remoteTime
                        )
                        dao.insertApp(newApp)
                        changedCount++
                        Log.d("DeviceSyncManager", "[Room] Inserted missing app ${remoteApp.packageName} into Room with isProtected=${remoteApp.isLocked}")
                    } catch (e: Exception) {
                        Log.e("DeviceSyncManager", "[Room] Failed to insert remote app ${remoteApp.packageName} into Room", e)
                    }
                }
            }
            
            if (changedCount > 0) {
                Log.d("DeviceSyncManager", "[Sync] Remote sync completed. Changed $changedCount apps in Room.")
                triggerHeartbeat()
            }
        }
    }

    private fun showLocalNotification(appName: String, isLocked: Boolean) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        
        val channelId = "guardian_remote_sync"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "Remote Device Sync",
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(channel)
        }
        
        val status = if (isLocked) "Protected" else "Unprotected"
        
        val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_lock) // Fallback icon
            .setContentTitle("Guardian Remote Update")
            .setContentText("$appName was $status remotely.")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            
        nm.notify(appName.hashCode(), builder.build())
    }

    // =========================================================================
    // PARENT-SPECIFIC OPERATIONS (Used when running in Parent mode)
    // =========================================================================
    suspend fun syncRemoteToggle(remoteDeviceId: String, packageName: String, isProtected: Boolean) {
        try {
            val db = firestore
            val updates = mapOf(
                "isLocked" to isProtected,
                "locked" to isProtected,
                "updatedBy" to "parent",
                "updatedByDeviceId" to deviceUuid,
                "timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            )
            db.collection("devices")
                .document(remoteDeviceId)
                .collection("apps")
                .document(packageName.replace(".", "_"))
                .update(updates)
                .await()
            Log.d("DeviceSyncManager", "[Sync] Successfully pushed remote toggle for $packageName to device $remoteDeviceId")
        } catch (e: Exception) {
            Log.e("DeviceSyncManager", "[Sync] Failed to push remote toggle for $packageName", e)
        }
    }

    fun processCommandsSnapshot(snapshot: QuerySnapshot) {
        if (snapshot.isEmpty) return
        
        coroutineScope.launch {
            commandProcessorMutex.withLock {
                val db = AppDatabase.getDatabase(context)
                val pairedDao = db.pairedDeviceDao()
                val pairedDevices = pairedDao.getAllPairedDevicesSynchronous().associateBy { it.uuid }
                
                val commands = snapshot.documents.mapNotNull { doc ->
                    doc.toObject(com.ai.guardian.data.remote.models.RemoteCommand::class.java)?.copy(commandId = doc.id)
                }
                
                // Sort by priority (desc) then sequence (asc)
                val sortedCommands = commands.sortedWith(compareByDescending<com.ai.guardian.data.remote.models.RemoteCommand> { it.commandPriority }.thenBy { it.commandSequence })
                
                for (command in sortedCommands) {
                    if (command.status != com.ai.guardian.data.remote.models.CommandStatus.PENDING) continue
                    
                    val now = System.currentTimeMillis()
                    val docRef = firestore.collection("devices").document(deviceUuid).collection("commands").document(command.commandId)
                    
                    if (now < command.nextRetryAt) {
                        continue // Not ready for retry
                    }
                    
                    // 1. Claim Transactionally (Transition to PROCESSING)
                    var claimed = false
                    try {
                        claimed = firestore.runTransaction { transaction ->
                            val currentStatus = transaction.get(docRef).getString("status")
                            if (currentStatus == com.ai.guardian.data.remote.models.CommandStatus.PENDING.name) {
                                transaction.update(docRef, "status", com.ai.guardian.data.remote.models.CommandStatus.PROCESSING.name)
                                true
                            } else {
                                false
                            }
                        }.await()
                    } catch (e: Exception) {
                        Log.e("DeviceSyncManager", "Failed to claim command ${command.commandId}", e)
                        continue
                    }
                    
                    if (!claimed) continue
                    
                    // 2. Validate after entering PROCESSING
                    // Identity Verification
                    if (!pairedDevices.containsKey(command.parentDeviceId)) {
                        Log.w("DeviceSyncManager", "[Command] Unauthorized command: Unknown Parent ${command.parentDeviceId}")
                        docRef.update("status", com.ai.guardian.data.remote.models.CommandStatus.FAILED.name, "result", "Unauthorized: Unknown Parent")
                        continue
                    }
                    if (command.childDeviceId != deviceUuid) {
                        Log.w("DeviceSyncManager", "[Command] Command target mismatch: ${command.childDeviceId} != $deviceUuid")
                        docRef.update("status", com.ai.guardian.data.remote.models.CommandStatus.FAILED.name, "result", "Target Mismatch")
                        continue
                    }
                    val pairedParent = pairedDevices[command.parentDeviceId]
                    if (command.authorizationToken != pairedParent?.pairingKey) {
                        Log.w("DeviceSyncManager", "[Command] Unauthorized command: Token mismatch")
                        docRef.update("status", com.ai.guardian.data.remote.models.CommandStatus.FAILED.name, "result", "Unauthorized: Invalid Token")
                        continue
                    }
                    
                    // Nonce check
                    val prefs = context.getSharedPreferences("guardian_nonces", android.content.Context.MODE_PRIVATE)
                    if (prefs.getBoolean(command.nonce, false)) {
                        Log.w("DeviceSyncManager", "[Command] Replay attack detected. Nonce already processed: ${command.nonce}")
                        docRef.update("status", com.ai.guardian.data.remote.models.CommandStatus.FAILED.name, "result", "Replay Attack Detected")
                        continue
                    }
                    
                    // Expiration and Retry Check
                    if (command.expiresAt > 0 && now > command.expiresAt) {
                        Log.w("DeviceSyncManager", "[Command] Command ${command.commandId} expired.")
                        docRef.update("status", com.ai.guardian.data.remote.models.CommandStatus.FAILED.name, "result", "COMMAND_EXPIRED")
                        continue
                    }
                    
                    // Accessibility Check
                    val isAccessibilityRunning = com.ai.guardian.services.GuardianAccessibilityService.isServiceRunning()
                    val requiresAccessibility = listOf(
                        com.ai.guardian.data.remote.models.CommandType.LOCK_DEVICE,
                        com.ai.guardian.data.remote.models.CommandType.UNLOCK_DEVICE,
                        com.ai.guardian.data.remote.models.CommandType.ENABLE_PROTECTION,
                        com.ai.guardian.data.remote.models.CommandType.DISABLE_PROTECTION,
                        com.ai.guardian.data.remote.models.CommandType.PAUSE_PROTECTION,
                        com.ai.guardian.data.remote.models.CommandType.RESUME_PROTECTION,
                        com.ai.guardian.data.remote.models.CommandType.EMERGENCY_LOCK,
                        com.ai.guardian.data.remote.models.CommandType.EMERGENCY_UNLOCK
                    )
                    if (!isAccessibilityRunning && command.commandType in requiresAccessibility) {
                        Log.w("DeviceSyncManager", "[Command] Accessibility disabled, rejecting command ${command.commandType}")
                        docRef.update("status", com.ai.guardian.data.remote.models.CommandStatus.FAILED.name, "result", "ACCESSIBILITY_DISABLED")
                        continue
                    }
                    
                    // 4. Execution
                    Log.d("DeviceSyncManager", "[Command] Executing command: ${command.commandType}")
                    try {
                        val settingsDao = db.deviceSettingsDao()
                        when (command.commandType) {
                            com.ai.guardian.data.remote.models.CommandType.LOCK_DEVICE -> {
                                var newVersion = 1L
                                db.withTransaction {
                                    val currentSettings = settingsDao.getSettings()
                                    if (currentSettings != null) {
                                        newVersion = currentSettings.configurationVersion + 1
                                        val updated = currentSettings.copy(
                                            isRemotelyLocked = true,
                                            configurationVersion = newVersion
                                        )
                                        settingsDao.insertOrUpdateSettings(updated)
                                    }
                                }
                                Log.d("DeviceSyncManager", "[Command] Lock execution succeeded locally. Updating Firestore state/current.")
                                firestore.collection("devices").document(deviceUuid)
                                    .collection("state").document("current")
                                    .set(
                                        mapOf(
                                            "remotelyLocked" to true,
                                            "configurationVersion" to newVersion,
                                            "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                                        ), 
                                        com.google.firebase.firestore.SetOptions.merge()
                                    ).await()
                            }
                            com.ai.guardian.data.remote.models.CommandType.UNLOCK_DEVICE,
                            com.ai.guardian.data.remote.models.CommandType.EMERGENCY_UNLOCK -> {
                                var newVersion = 1L
                                db.withTransaction {
                                    val currentSettings = settingsDao.getSettings()
                                    if (currentSettings != null) {
                                        newVersion = currentSettings.configurationVersion + 1
                                        val updated = currentSettings.copy(
                                            isRemotelyLocked = false,
                                            configurationVersion = newVersion
                                        )
                                        settingsDao.insertOrUpdateSettings(updated)
                                    }
                                }
                                Log.d("DeviceSyncManager", "[Command] Unlock execution succeeded locally. Updating Firestore state/current.")
                                firestore.collection("devices").document(deviceUuid)
                                    .collection("state").document("current")
                                    .set(
                                        mapOf(
                                            "remotelyLocked" to false,
                                            "configurationVersion" to newVersion,
                                            "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                                        ), 
                                        com.google.firebase.firestore.SetOptions.merge()
                                    ).await()
                            }
                            com.ai.guardian.data.remote.models.CommandType.ENABLE_PROTECTION -> {
                                var newVersion = 1L
                                db.withTransaction {
                                    val currentSettings = settingsDao.getSettings()
                                    if (currentSettings != null) {
                                        newVersion = currentSettings.configurationVersion + 1
                                        val updated = currentSettings.copy(
                                            isProtectionEnabled = true,
                                            configurationVersion = newVersion
                                        )
                                        settingsDao.insertOrUpdateSettings(updated)
                                    }
                                }
                                Log.d("DeviceSyncManager", "[Command] Enable protection succeeded locally. Updating Firestore state/current.")
                                firestore.collection("devices").document(deviceUuid)
                                    .collection("state").document("current")
                                    .set(
                                        mapOf(
                                            "protectionEnabled" to true,
                                            "configurationVersion" to newVersion,
                                            "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                                        ), 
                                        com.google.firebase.firestore.SetOptions.merge()
                                    ).await()
                            }
                            com.ai.guardian.data.remote.models.CommandType.DISABLE_PROTECTION -> {
                                var newVersion = 1L
                                db.withTransaction {
                                    val currentSettings = settingsDao.getSettings()
                                    if (currentSettings != null) {
                                        newVersion = currentSettings.configurationVersion + 1
                                        val updated = currentSettings.copy(
                                            isProtectionEnabled = false,
                                            configurationVersion = newVersion
                                        )
                                        settingsDao.insertOrUpdateSettings(updated)
                                    }
                                }
                                Log.d("DeviceSyncManager", "[Command] Disable protection succeeded locally. Updating Firestore state/current.")
                                firestore.collection("devices").document(deviceUuid)
                                    .collection("state").document("current")
                                    .set(
                                        mapOf(
                                            "protectionEnabled" to false,
                                            "configurationVersion" to newVersion,
                                            "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                                        ), 
                                        com.google.firebase.firestore.SetOptions.merge()
                                    ).await()
                            }
                            com.ai.guardian.data.remote.models.CommandType.REQUEST_FULL_SYNC -> {
                                Log.d("DeviceSyncManager", "[Command] Initiating full sync execution.")
                                executeFullSync(null)
                            }
                            com.ai.guardian.data.remote.models.CommandType.EMERGENCY_LOCK -> {
                                var newVersion = 1L
                                db.withTransaction {
                                    val currentSettings = settingsDao.getSettings()
                                    if (currentSettings != null) {
                                        newVersion = currentSettings.configurationVersion + 1
                                        val updated = currentSettings.copy(
                                            isRemotelyLocked = true,
                                            isProtectionEnabled = true,
                                            configurationVersion = newVersion
                                        )
                                        settingsDao.insertOrUpdateSettings(updated)
                                    }
                                }
                                Log.d("DeviceSyncManager", "[Command] Emergency lock succeeded locally. Updating Firestore state/current.")
                                val updates = mapOf(
                                    "remotelyLocked" to true, 
                                    "protectionEnabled" to true,
                                    "configurationVersion" to newVersion,
                                    "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                                )
                                firestore.collection("devices").document(deviceUuid)
                                    .collection("state").document("current")
                                    .set(updates, com.google.firebase.firestore.SetOptions.merge()).await()
                            }
                            com.ai.guardian.data.remote.models.CommandType.REMOVE_PAIRING -> {
                                val pairedDao = db.pairedDeviceDao()
                                val originalSettings = settingsDao.getSettings()
                                
                                 try {
                                     // 1. Delete Child-owned Firestore collections atomically using WriteBatch (except authorizedParents/commands)
                                     val childDocRef = firestore.collection("devices").document(deviceUuid)
                                     val batch = firestore.batch()
                                     
                                     // Apps
                                     val appsSnap = childDocRef.collection("apps").get().await()
                                     for (doc in appsSnap.documents) {
                                         batch.delete(doc.reference)
                                     }
                                     
                                     // Presence
                                     batch.delete(childDocRef.collection("presence").document("current"))
                                     
                                     // State
                                     batch.delete(childDocRef.collection("state").document("current"))
                                     
                                     // SyncRequests
                                     val requestsSnap = childDocRef.collection("syncRequests").get().await()
                                     for (doc in requestsSnap.documents) {
                                         batch.delete(doc.reference)
                                     }
                                     
                                     // approvalRequests
                                     val approvalsSnap = childDocRef.collection("approvalRequests").get().await()
                                     for (doc in approvalsSnap.documents) {
                                         batch.delete(doc.reference)
                                     }

                                     // Delete authorizedParents entry
                                     if (command.parentDeviceId.isNotEmpty()) {
                                         batch.delete(childDocRef.collection("authorizedParents").document(command.parentDeviceId))
                                     }

                                     // Commands (except current unpairing command)
                                     val commandsSnap = childDocRef.collection("commands").get().await()
                                     for (doc in commandsSnap.documents) {
                                         if (doc.id != command.commandId) {
                                             batch.delete(doc.reference)
                                         }
                                     }
                                     
                                     // Commit all deletions atomically
                                     batch.commit().await()
                                     
                                     // 2. Begin Room Transaction (Reset Settings, Delete Parent)
                                     db.withTransaction {
                                         // Reset Settings to default
                                         if (originalSettings != null) {
                                             val updated = originalSettings.copy(
                                                 isProtectionEnabled = false,
                                                 isRemotelyLocked = false,
                                                 configurationVersion = originalSettings.configurationVersion + 1
                                             )
                                             settingsDao.insertOrUpdateSettings(updated)
                                         }
                                         
                                         // Delete Parent from paired_devices
                                         pairedDao.deleteDeviceByUuid(command.parentDeviceId)
                                     } // Commit Room Transaction
                                     
                                      // 3. Update current command to COMPLETED in Firestore (Parent reads this first)
                                      docRef.update(
                                          "status", com.ai.guardian.data.remote.models.CommandStatus.COMPLETED.name,
                                          "result", "Successfully unpaired",
                                          "completedAt", System.currentTimeMillis(),
                                          "processedBy", deviceUuid
                                      ).await()
                                      
                                      // 4. Safely delete authorizedParents (but leave the unpairing command for audit logs)
                                      try {
                                          val authParentsSnap = childDocRef.collection("authorizedParents").get().await()
                                          val finalBatch = firestore.batch()
                                          for (doc in authParentsSnap.documents) {
                                              finalBatch.delete(doc.reference)
                                          }
                                          finalBatch.commit().await()
                                      } catch (ex: Exception) {
                                          Log.e("DeviceSyncManager", "Quietly failed post-unpair final cleanups (to be picked up on startup/heartbeat)", ex)
                                      }
                                     
                                     // 5. Stop SyncEngineManager if no parents remain
                                     val remainingCount = pairedDao.getAllPairedDevicesSynchronous().size
                                     if (remainingCount == 0) {
                                         val app = context.applicationContext as? com.ai.guardian.GuardianApplication
                                         app?.container?.syncEngineManager?.stop()
                                     }
                                 } catch (e: Exception) {
                                     Log.e("DeviceSyncManager", "Cleanup or execution failed after PROCESSING", e)
                                     
                                     // Update command status = FAILED with a failure reason (and never report COMPLETED)
                                     try {
                                         docRef.update(
                                             "status", com.ai.guardian.data.remote.models.CommandStatus.FAILED.name,
                                             "result", "Cleanup failed: ${e.message}",
                                             "failureReason", e.message ?: "Unknown error",
                                             "completedAt", System.currentTimeMillis(),
                                             "processedBy", deviceUuid
                                         ).await()
                                     } catch (ex: Exception) {
                                         Log.e("DeviceSyncManager", "Failed to mark command as FAILED", ex)
                                     }
                                 } finally {
                                     // Save Nonce
                                     if (command.nonce.isNotEmpty()) {
                                         prefs.edit().putBoolean(command.nonce, true).apply()
                                     }
                                 }
                                 return@launch
                             }
                             com.ai.guardian.data.remote.models.CommandType.SET_PIN -> {
                                 val pin = command.payload
                                 if (pin.length < 4 || pin.length > 6 || !pin.all { it.isDigit() }) {
                                     throw IllegalArgumentException("PIN must be 4-6 digits")
                                 }
                                 val pinManager = com.ai.guardian.security.SecurityPinManager(context)
                                 val savedModel = pinManager.savePin(pin)
                                 
                                 db.withTransaction {
                                     val currentSettings = settingsDao.getSettings()
                                     if (currentSettings != null) {
                                         val updated = currentSettings.copy(
                                             securityPinHash = savedModel.encryptedHash,
                                             securityPinIv = savedModel.iv,
                                             securityPinSalt = savedModel.salt,
                                             isPinConfigured = true,
                                             pinVersion = currentSettings.pinVersion + 1,
                                             pinUpdatedAt = System.currentTimeMillis(),
                                             pinResetRequired = false,
                                             configurationVersion = currentSettings.configurationVersion + 1
                                         )
                                         settingsDao.insertOrUpdateSettings(updated)
                                     }
                                 }
                                 Log.d("DeviceSyncManager", "[Command] SET_PIN succeeded locally.")
                                 val container = (context.applicationContext as com.ai.guardian.GuardianApplication).container
                                 container.pinSyncManager.queuePinSync(pin.toCharArray())
                             }
                             com.ai.guardian.data.remote.models.CommandType.CHANGE_PIN -> {
                                 val parts = command.payload.split(":")
                                 if (parts.size != 2) {
                                     throw IllegalArgumentException("Invalid payload format for CHANGE_PIN. Expected oldPin:newPin")
                                 }
                                 val oldPin = parts[0]
                                 val newPin = parts[1]
                                 if (newPin.length < 4 || newPin.length > 6 || !newPin.all { it.isDigit() }) {
                                     throw IllegalArgumentException("New PIN must be 4-6 digits")
                                 }
                                 val currentSettings = settingsDao.getSettings() ?: throw IllegalStateException("No settings found")
                                 if (!currentSettings.isPinConfigured) {
                                     throw IllegalStateException("No PIN currently configured")
                                 }
                                 val pinManager = com.ai.guardian.security.SecurityPinManager(context)
                                 val verified = pinManager.verifyPin(
                                     oldPin,
                                     currentSettings.securityPinHash ?: "",
                                     currentSettings.securityPinIv ?: "",
                                     currentSettings.securityPinSalt ?: ""
                                 )
                                 if (!verified) {
                                     throw IllegalArgumentException("Incorrect old PIN")
                                 }
                                 
                                 val savedModel = pinManager.savePin(newPin)
                                 db.withTransaction {
                                     val updated = currentSettings.copy(
                                         securityPinHash = savedModel.encryptedHash,
                                         securityPinIv = savedModel.iv,
                                         securityPinSalt = savedModel.salt,
                                         isPinConfigured = true,
                                         pinVersion = currentSettings.pinVersion + 1,
                                         pinUpdatedAt = System.currentTimeMillis(),
                                         configurationVersion = currentSettings.configurationVersion + 1
                                     )
                                     settingsDao.insertOrUpdateSettings(updated)
                                 }
                                 Log.d("DeviceSyncManager", "[Command] CHANGE_PIN succeeded locally.")
                                 val container = (context.applicationContext as com.ai.guardian.GuardianApplication).container
                                 container.pinSyncManager.queuePinSync(newPin.toCharArray())
                             }
                             com.ai.guardian.data.remote.models.CommandType.RESET_PIN -> {
                                 db.withTransaction {
                                     val currentSettings = settingsDao.getSettings()
                                     if (currentSettings != null) {
                                         val updated = currentSettings.copy(
                                             securityPinHash = null,
                                             securityPinIv = null,
                                             securityPinSalt = null,
                                             isPinConfigured = false,
                                             pinVersion = currentSettings.pinVersion + 1,
                                             pinUpdatedAt = System.currentTimeMillis(),
                                             pinResetRequired = true,
                                             configurationVersion = currentSettings.configurationVersion + 1
                                         )
                                         settingsDao.insertOrUpdateSettings(updated)
                                     }
                                 }
                                 Log.d("DeviceSyncManager", "[Command] RESET_PIN succeeded locally.")
                             }
                             else -> {
                                 Log.w("DeviceSyncManager", "Unhandled command type: ${command.commandType}")
                             }
                        }
                        
                        // Save Nonce
                        if (command.nonce.isNotEmpty()) {
                            prefs.edit().putBoolean(command.nonce, true).apply()
                        }
                        
                        docRef.update(
                             "status", com.ai.guardian.data.remote.models.CommandStatus.COMPLETED.name,
                             "result", "Success",
                             "completedAt", System.currentTimeMillis(),
                             "processedBy", deviceUuid
                         ).await()
                        triggerHeartbeat()
                    } catch (e: Exception) {
                        Log.e("DeviceSyncManager", "Command execution failed: ${command.commandId}", e)
                        if (command.retryCount < command.maxRetryCount) {
                            val nextRetry = now + (30_000L * (command.retryCount + 1)) // exponential backoff 30s, 60s, 90s...
                            docRef.update(
                                "status", com.ai.guardian.data.remote.models.CommandStatus.PENDING.name,
                                "retryCount", command.retryCount + 1,
                                "nextRetryAt", nextRetry,
                                "result", "Failed: ${e.message}. Retrying..."
                            )
                        } else {
                             docRef.update(
                                 "status", com.ai.guardian.data.remote.models.CommandStatus.FAILED.name,
                                 "result", "Failed after max retries: ${e.message}",
                                 "completedAt", System.currentTimeMillis(),
                                 "processedBy", deviceUuid
                             )
                        }
                    }
                }
            }
        }
    }

    suspend fun cleanUpAllRemoteDataQuietly() {
        val db = AppDatabase.getDatabase(context)
        val pairedDao = db.pairedDeviceDao()
        
        // Guard 1: Verify pairing count immediately
        val initialPairedCount = pairedDao.getAllPairedDevicesSynchronous().size
        if (initialPairedCount > 0) {
            Log.d("DeviceSyncManager", "Startup cleanup aborted: Device has active paired parent(s) ($initialPairedCount).")
            return
        }

        // Guard 2: Settling delay (2.5s) to avoid transient Room initializations or races
        kotlinx.coroutines.delay(2500)

        // Guard 3: Re-verify pairing count is still exactly 0
        val finalPairedCount = pairedDao.getAllPairedDevicesSynchronous().size
        if (finalPairedCount > 0) {
            Log.d("DeviceSyncManager", "Startup cleanup aborted: Device was paired during settling delay.")
            return
        }

        Log.d("DeviceSyncManager", "All guard conditions passed. Executing remote Firestore cleanup...")
        try {
            val uuid = deviceUuid
            val childDocRef = firestore.collection("devices").document(uuid)
            
            // Apps
            try {
                val appsSnap = childDocRef.collection("apps").get().await()
                if (!appsSnap.isEmpty) {
                    val batch = firestore.batch()
                    for (doc in appsSnap.documents) {
                        batch.delete(doc.reference)
                    }
                    batch.commit().await()
                }
            } catch (e: Exception) {
                Log.d("DeviceSyncManager", "Cleanup apps subcollection failed (ignoring): ${e.message}")
            }
            
            // Presence
            try {
                childDocRef.collection("presence").document("current").delete().await()
            } catch (e: Exception) {
                Log.d("DeviceSyncManager", "Cleanup presence document failed (ignoring): ${e.message}")
            }
            
            // State
            try {
                childDocRef.collection("state").document("current").delete().await()
            } catch (e: Exception) {
                Log.d("DeviceSyncManager", "Cleanup state document failed (ignoring): ${e.message}")
            }
            
            // SyncRequests
            try {
                val requestsSnap = childDocRef.collection("syncRequests").get().await()
                if (!requestsSnap.isEmpty) {
                    val batch = firestore.batch()
                    for (doc in requestsSnap.documents) {
                        batch.delete(doc.reference)
                    }
                    batch.commit().await()
                }
            } catch (e: Exception) {
                Log.d("DeviceSyncManager", "Cleanup syncRequests failed (ignoring): ${e.message}")
            }
            
            // authorizedParents
            try {
                val authParentsSnap = childDocRef.collection("authorizedParents").get().await()
                if (!authParentsSnap.isEmpty) {
                    val batch = firestore.batch()
                    for (doc in authParentsSnap.documents) {
                        batch.delete(doc.reference)
                    }
                    batch.commit().await()
                }
            } catch (e: Exception) {
                Log.d("DeviceSyncManager", "Cleanup authorizedParents failed (ignoring): ${e.message}")
            }
            
            // approvalRequests
            try {
                val approvalsSnap = childDocRef.collection("approvalRequests").get().await()
                if (!approvalsSnap.isEmpty) {
                    val batch = firestore.batch()
                    for (doc in approvalsSnap.documents) {
                        batch.delete(doc.reference)
                    }
                    batch.commit().await()
                }
            } catch (e: Exception) {
                Log.d("DeviceSyncManager", "Cleanup approvalRequests failed (ignoring): ${e.message}")
            }

            // Commands
            try {
                val commandsSnap = childDocRef.collection("commands").get().await()
                if (!commandsSnap.isEmpty) {
                    val batch = firestore.batch()
                    for (doc in commandsSnap.documents) {
                        batch.delete(doc.reference)
                    }
                    batch.commit().await()
                }
            } catch (e: Exception) {
                Log.d("DeviceSyncManager", "Cleanup commands failed (ignoring): ${e.message}")
            }

            Log.d("DeviceSyncManager", "Eventually consistent remote cleanup completed successfully for unpaired device: $uuid")
        } catch (e: Exception) {
            Log.e("DeviceSyncManager", "General quiet cleanup error", e)
        }
    }

    private fun triggerHeartbeat() {
        val app = context.applicationContext as? com.ai.guardian.GuardianApplication
        app?.container?.syncEngineManager?.triggerImmediateHeartbeat()
    }
}
