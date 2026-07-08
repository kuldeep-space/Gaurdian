package com.ai.guardian.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ai.guardian.GuardianApplication
import com.ai.guardian.data.entity.AppLockEntity
import com.ai.guardian.data.entity.DeviceSettingsEntity
import com.ai.guardian.data.entity.FaceProfileWithTemplates
import com.ai.guardian.data.entity.RecognitionHistoryEntity
import com.ai.guardian.data.remote.DeviceSyncManager
import com.ai.guardian.ui.theme.ThemeMode
import com.ai.guardian.ui.theme.ThemePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(private val application: Application) : AndroidViewModel(application) {
    private val container        = (application as GuardianApplication).container
    private val themePreferences = ThemePreferences(application)

    private val _settings = MutableStateFlow(DeviceSettingsEntity())
    val settings: StateFlow<DeviceSettingsEntity> = _settings.asStateFlow()

    private val _lockedApps = MutableStateFlow<List<AppLockEntity>>(emptyList())
    val lockedApps: StateFlow<List<AppLockEntity>> = _lockedApps.asStateFlow()

    private val _enrolledFaces = MutableStateFlow<List<FaceProfileWithTemplates>>(emptyList())
    val enrolledFaces: StateFlow<List<FaceProfileWithTemplates>> = _enrolledFaces.asStateFlow()

    private val _hasFaceEnrolled = MutableStateFlow(false)
    val hasFaceEnrolled: StateFlow<Boolean> = _hasFaceEnrolled.asStateFlow()

    private val _recognitionHistory = MutableStateFlow<List<RecognitionHistoryEntity>>(emptyList())
    val recognitionHistory: StateFlow<List<RecognitionHistoryEntity>> = _recognitionHistory.asStateFlow()

    var reenrollProfileId by androidx.compose.runtime.mutableStateOf<Long?>(null)
    var reenrollProfileName by androidx.compose.runtime.mutableStateOf<String?>(null)

    var pinProtectedAction by mutableStateOf<(() -> Unit)?>(null)
    var pinProtectedActionName by mutableStateOf("")

    /** Persisted theme selection — defaults to SYSTEM until DataStore emits. */
    val themeMode: StateFlow<ThemeMode> = themePreferences.themeModeFlow
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.Eagerly,
            initialValue = ThemeMode.SYSTEM
        )

    init {
        // Collect flows to state variables for UI
        viewModelScope.launch {
            container.faceDao.getAllProfilesWithTemplatesFlow().collect { faces ->
                _enrolledFaces.value = faces
                _hasFaceEnrolled.value = faces.isNotEmpty()
            }
        }

        viewModelScope.launch {
            container.recognitionHistoryDao.getAllHistoryFlow().collect { history ->
                _recognitionHistory.value = history
            }
        }

        viewModelScope.launch {
            container.deviceSettingsDao.getSettingsFlow().collect { currentSettings ->
                if (currentSettings != null) {
                    _settings.value = currentSettings
                } else {
                    val defaultSettings = DeviceSettingsEntity()
                    container.deviceSettingsDao.insertOrUpdateSettings(defaultSettings)
                    _settings.value = defaultSettings
                }
            }
        }

        viewModelScope.launch {
            container.appLockRepository.getAllAppsFlow().collect { apps ->
                _lockedApps.value = apps
            }
        }
    }

    fun runWithPinProtection(actionName: String, onApproved: () -> Unit) {
        viewModelScope.launch {
            val s = container.deviceSettingsDao.getSettings()
            if (s == null || !s.isPinConfigured || com.ai.guardian.security.MaintenanceModeManager.isMaintenanceModeActive()) {
                onApproved()
            } else {
                pinProtectedActionName = actionName
                pinProtectedAction = onApproved
            }
        }
    }

    /** Persisted theme choice and immediately updates the StateFlow. */
    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            themePreferences.setThemeMode(mode)
        }
    }

    fun toggleGlobalProtection(enabled: Boolean) {
        val actionText = if (enabled) "enable Guardian protection" else "disable Guardian protection"
        runWithPinProtection(actionText) {
            viewModelScope.launch {
                val settings = container.deviceSettingsDao.getSettings() ?: DeviceSettingsEntity()
                container.deviceSettingsDao.insertOrUpdateSettings(settings.copy(isProtectionEnabled = enabled))
            }
        }
    }

    fun updateShowLockScreenOverlay(show: Boolean) {
        val actionText = if (show) "enable Face Protection overlay" else "disable Face Protection overlay"
        runWithPinProtection(actionText) {
            viewModelScope.launch {
                val settings = container.deviceSettingsDao.getSettings() ?: DeviceSettingsEntity()
                container.deviceSettingsDao.insertOrUpdateSettings(settings.copy(showLockScreenOverlay = show))
            }
        }
    }

    fun updateTrustedAuthDurationMinutes(minutes: Int) {
        val actionText = if (minutes > 0) "enable trusted cache" else "disable trusted cache"
        runWithPinProtection(actionText) {
            viewModelScope.launch {
                val settings = container.deviceSettingsDao.getSettings() ?: DeviceSettingsEntity()
                container.deviceSettingsDao.insertOrUpdateSettings(settings.copy(trustedAuthDurationMinutes = minutes))
            }
        }
    }

    fun updateLivenessDetection(enabled: Boolean) {
        val actionText = if (enabled) "enable liveness detection" else "disable liveness detection"
        runWithPinProtection(actionText) {
            viewModelScope.launch {
                val settings = container.deviceSettingsDao.getSettings() ?: DeviceSettingsEntity()
                container.deviceSettingsDao.insertOrUpdateSettings(
                    settings.copy(
                        isLivenessDetectionEnabled = enabled,
                        configurationVersion = settings.configurationVersion + 1
                    )
                )
            }
        }
    }

    fun updateMatchingThreshold(threshold: Float) {
        runWithPinProtection("modify matching threshold") {
            viewModelScope.launch {
                val settings = container.deviceSettingsDao.getSettings() ?: DeviceSettingsEntity()
                container.deviceSettingsDao.insertOrUpdateSettings(settings.copy(matchingThreshold = threshold))
            }
        }
    }

    fun toggleAppLock(app: AppLockEntity) {
        viewModelScope.launch {
            container.appLockRepository.insertApp(app)
        }
    }

    fun deleteFaceById(id: Long) {
        runWithPinProtection("delete face profile") {
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    container.faceDao.deleteProfileById(id)
                }
            }
        }
    }

    fun renameFace(id: Long, name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                container.faceDao.renameProfile(id, name)
            }
        }
    }

    fun updateAvatarColor(id: Long, color: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                container.faceDao.updateAvatarColor(id, color)
            }
        }
    }

    fun deleteAllFaces() {
        runWithPinProtection("delete all face profiles") {
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    container.faceDao.deleteAllProfiles()
                }
            }
        }
    }

    fun deleteAllHistory() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                container.recognitionHistoryDao.deleteAllHistory()
            }
        }
    }

    fun setInitialChildPin(pin: String, context: android.content.Context) {
        viewModelScope.launch {
            if (pin.length in 4..6 && pin.all { it.isDigit() }) {
                val pinManager = com.ai.guardian.security.SecurityPinManager(context)
                val savedModel = pinManager.savePin(pin)
                val currentSettings = container.deviceSettingsDao.getSettings() ?: com.ai.guardian.data.entity.DeviceSettingsEntity()
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
                container.deviceSettingsDao.insertOrUpdateSettings(updated)
                
                // Sync E2EE PIN payload to parent
                container.pinSyncManager.queuePinSync(pin.toCharArray())
            }
        }
    }
}
