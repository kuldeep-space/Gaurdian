package com.ai.guardian.di

import android.content.Context
import com.ai.guardian.data.AppDatabase
import com.ai.guardian.data.dao.AppLockDao
import com.ai.guardian.data.dao.DeviceSettingsDao
import com.ai.guardian.data.dao.FaceDao
import com.ai.guardian.data.dao.RecognitionHistoryDao

interface AppContainer {
    val faceDao: FaceDao
    val deviceSettingsDao: DeviceSettingsDao
    val appLockDao: AppLockDao
    val recognitionHistoryDao: RecognitionHistoryDao
    val pairedDeviceDao: com.ai.guardian.data.dao.PairedDeviceDao
    val auditEventDao: com.ai.guardian.data.dao.AuditEventDao
    val approvalRequestDao: com.ai.guardian.data.dao.ApprovalRequestDao
    val deviceSyncManager: com.ai.guardian.data.remote.DeviceSyncManager
    val syncEngineManager: com.ai.guardian.data.remote.SyncEngineManager
    val remoteDeviceRepository: com.ai.guardian.data.remote.RemoteDeviceRepository
    val appLockRepository: com.ai.guardian.data.repository.AppLockRepository
    val pairingManager: com.ai.guardian.data.remote.PairingManager
    val tamperDetectionManager: com.ai.guardian.security.TamperDetectionManager
    val parentSecretKeyManager: com.ai.guardian.security.ParentSecretKeyManager
    val devicePolicyController: com.ai.guardian.security.DevicePolicyController
    val parentKeyManager: com.ai.guardian.security.ParentKeyManager
    val pinSyncManager: com.ai.guardian.security.PinSyncManager
    val protectedActionGate: com.ai.guardian.security.ProtectedActionGate
}

class DefaultAppContainer(private val context: Context) : AppContainer {
    private val database by lazy { AppDatabase.getDatabase(context) }

    override val faceDao: FaceDao by lazy { database.faceDao() }
    override val deviceSettingsDao: DeviceSettingsDao by lazy { database.deviceSettingsDao() }
    override val appLockDao: AppLockDao by lazy { database.appLockDao() }
    override val recognitionHistoryDao: RecognitionHistoryDao by lazy { database.recognitionHistoryDao() }
    override val pairedDeviceDao: com.ai.guardian.data.dao.PairedDeviceDao by lazy { database.pairedDeviceDao() }
    override val auditEventDao: com.ai.guardian.data.dao.AuditEventDao by lazy { database.auditEventDao() }
    override val approvalRequestDao: com.ai.guardian.data.dao.ApprovalRequestDao by lazy { database.approvalRequestDao() }

    override val deviceSyncManager: com.ai.guardian.data.remote.DeviceSyncManager by lazy {
        com.ai.guardian.data.remote.DeviceSyncManager(context)
    }

    override val syncEngineManager: com.ai.guardian.data.remote.SyncEngineManager by lazy {
        com.ai.guardian.data.remote.SyncEngineManager(context, deviceSyncManager, pairedDeviceDao)
    }

    override val remoteDeviceRepository: com.ai.guardian.data.remote.RemoteDeviceRepository by lazy {
        com.ai.guardian.data.remote.RemoteDeviceRepository(deviceSyncManager, pairedDeviceDao, parentSecretKeyManager)
    }

    override val appLockRepository: com.ai.guardian.data.repository.AppLockRepository by lazy {
        com.ai.guardian.data.repository.AppLockRepository(appLockDao, syncEngineManager)
    }

    override val pairingManager: com.ai.guardian.data.remote.PairingManager by lazy {
        com.ai.guardian.data.remote.PairingManager(context, deviceSyncManager, pairedDeviceDao, syncEngineManager)
    }

    override val tamperDetectionManager: com.ai.guardian.security.TamperDetectionManager by lazy {
        com.ai.guardian.security.TamperDetectionManager.getInstance(context)
    }

    override val parentSecretKeyManager: com.ai.guardian.security.ParentSecretKeyManager by lazy {
        com.ai.guardian.security.ParentSecretKeyManager(context)
    }

    override val devicePolicyController: com.ai.guardian.security.DevicePolicyController by lazy {
        com.ai.guardian.security.DevicePolicyController(context)
    }

    override val parentKeyManager: com.ai.guardian.security.ParentKeyManager by lazy {
        com.ai.guardian.security.ParentKeyManager(context)
    }

    override val pinSyncManager: com.ai.guardian.security.PinSyncManager by lazy {
        com.ai.guardian.security.PinSyncManager.getInstance(context)
    }

    override val protectedActionGate: com.ai.guardian.security.ProtectedActionGate by lazy {
        com.ai.guardian.security.ProtectedActionGate(
            context = context,
            childDeviceUuid = deviceSyncManager.deviceUuid,
            keyManager = parentSecretKeyManager,
            validator = com.ai.guardian.security.AuthorizationTokenValidator(parentSecretKeyManager)
        )
    }
}
