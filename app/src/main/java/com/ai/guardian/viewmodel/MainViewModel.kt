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

class MainViewModel(application: Application) : AndroidViewModel(application) {
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

    /** Persisted theme selection — defaults to SYSTEM until DataStore emits. */
    val themeMode: StateFlow<ThemeMode> = themePreferences.themeModeFlow
        .stateIn(
            scope         = viewModelScope,
            started       = SharingStarted.Eagerly,
            initialValue  = ThemeMode.SYSTEM
        )

    init {
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
            container.appLockDao.getAllAppsFlow().collect { apps ->
                _lockedApps.value = apps
            }
        }
    }

    /** Persists theme choice and immediately updates the StateFlow. */
    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            themePreferences.setThemeMode(mode)
        }
    }

    fun toggleGlobalProtection(enabled: Boolean) {
        viewModelScope.launch {
            val settings = container.deviceSettingsDao.getSettings() ?: DeviceSettingsEntity()
            container.deviceSettingsDao.insertOrUpdateSettings(settings.copy(isProtectionEnabled = enabled))
        }
    }

    fun updateShowLockScreenOverlay(show: Boolean) {
        viewModelScope.launch {
            val settings = container.deviceSettingsDao.getSettings() ?: DeviceSettingsEntity()
            container.deviceSettingsDao.insertOrUpdateSettings(settings.copy(showLockScreenOverlay = show))
        }
    }

    fun updateTrustedAuthDurationMinutes(minutes: Int) {
        viewModelScope.launch {
            val settings = container.deviceSettingsDao.getSettings() ?: DeviceSettingsEntity()
            container.deviceSettingsDao.insertOrUpdateSettings(settings.copy(trustedAuthDurationMinutes = minutes))
        }
    }

    fun updateMatchingThreshold(threshold: Float) {
        viewModelScope.launch {
            val settings = container.deviceSettingsDao.getSettings() ?: DeviceSettingsEntity()
            container.deviceSettingsDao.insertOrUpdateSettings(settings.copy(matchingThreshold = threshold))
        }
    }

    fun toggleAppLock(app: AppLockEntity) {
        viewModelScope.launch {
            // Simply insert the app entity which will REPLACE on conflict.
            container.appLockDao.insertApp(app)
        }
    }

    fun deleteFaceById(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                container.faceDao.deleteProfileById(id)
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
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                container.faceDao.deleteAllProfiles()
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
}
