package com.ai.guardian.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ai.guardian.GuardianApplication
import com.ai.guardian.data.remote.models.RemoteAppModel
import com.ai.guardian.data.remote.models.ApprovalRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteDeviceViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as GuardianApplication).container.remoteDeviceRepository
    
    private val _deviceUuid = MutableStateFlow<String?>(null)
    private val _isLockingInProgress = MutableStateFlow(false)
    private var lockTimeoutJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            _deviceUuid.filterNotNull().flatMapLatest { uuid ->
                repository.getPendingLockCommands(uuid)
            }.collect { remotePending ->
                if (!remotePending) {
                    _isLockingInProgress.value = false
                    lockTimeoutJob?.cancel()
                }
            }
        }
    }

    val remoteApps: StateFlow<List<RemoteAppModel>> = _deviceUuid
        .filterNotNull()
        .distinctUntilChanged()
        .flatMapLatest { uuid ->
            repository.getRemoteApps(uuid)
                .catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val remotePresence: StateFlow<Map<String, Any>?> = _deviceUuid
        .filterNotNull()
        .distinctUntilChanged()
        .flatMapLatest { uuid ->
            repository.getDevicePresence(uuid)
                .catch { emit(null) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val remoteState: StateFlow<Map<String, Any>?> = _deviceUuid
        .filterNotNull()
        .distinctUntilChanged()
        .flatMapLatest { uuid ->
            repository.getDeviceState(uuid)
                .catch { emit(null) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val pendingApprovals: StateFlow<List<ApprovalRequest>> = _deviceUuid
        .filterNotNull()
        .distinctUntilChanged()
        .flatMapLatest { uuid ->
            repository.getPendingApprovalRequests(uuid)
                .catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isLockCommandPending: StateFlow<Boolean> = combine(
        _deviceUuid.filterNotNull().distinctUntilChanged().flatMapLatest { uuid ->
            repository.getPendingLockCommands(uuid)
                .catch { emit(false) }
        },
        _isLockingInProgress
    ) { remotePending, localLocking ->
        remotePending || localLocking
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun loadDeviceData(deviceUuid: String) {
        _deviceUuid.value = deviceUuid
    }

    fun toggleAppLock(deviceUuid: String, packageName: String, isLocked: Boolean) {
        viewModelScope.launch {
            repository.toggleRemoteAppLock(deviceUuid, packageName, isLocked)
        }
    }

    fun sendRemoteCommand(deviceUuid: String, commandType: com.ai.guardian.data.remote.models.CommandType, payload: String = "") {
        viewModelScope.launch {
            if (commandType in listOf(
                    com.ai.guardian.data.remote.models.CommandType.LOCK_DEVICE,
                    com.ai.guardian.data.remote.models.CommandType.UNLOCK_DEVICE,
                    com.ai.guardian.data.remote.models.CommandType.EMERGENCY_LOCK,
                    com.ai.guardian.data.remote.models.CommandType.EMERGENCY_UNLOCK,
                    com.ai.guardian.data.remote.models.CommandType.ENABLE_PROTECTION,
                    com.ai.guardian.data.remote.models.CommandType.DISABLE_PROTECTION,
                    com.ai.guardian.data.remote.models.CommandType.SET_PIN,
                    com.ai.guardian.data.remote.models.CommandType.CHANGE_PIN,
                    com.ai.guardian.data.remote.models.CommandType.RESET_PIN
                )) {
                _isLockingInProgress.value = true
                lockTimeoutJob?.cancel()
                lockTimeoutJob = launch {
                    kotlinx.coroutines.delay(30000L) // 30s timeout guard
                    android.util.Log.w("RemoteDeviceVM", "[Command] Lock/unlock/PIN command timed out after 30s. Clearing loading state.")
                    _isLockingInProgress.value = false
                }
            }
            try {
                repository.sendRemoteCommand(deviceUuid, commandType, payload)
            } catch (e: Exception) {
                android.util.Log.e("RemoteDeviceVM", "[Command] Failed to send remote command", e)
                _isLockingInProgress.value = false
                lockTimeoutJob?.cancel()
            }
        }
    }

    fun approveRequest(request: com.ai.guardian.data.remote.models.ApprovalRequest) {
        viewModelScope.launch {
            try {
                repository.approveRequest(request)
            } catch (e: Exception) {
                android.util.Log.e("RemoteDeviceVM", "Failed to approve request", e)
            }
        }
    }

    fun rejectRequest(childUuid: String, requestId: String) {
        viewModelScope.launch {
            try {
                repository.rejectRequest(childUuid, requestId)
            } catch (e: Exception) {
                android.util.Log.e("RemoteDeviceVM", "Failed to reject request", e)
            }
        }
    }

    fun decryptChildPin(state: Map<String, Any>?): String? {
        if (state == null) return null
        val childUuid = _deviceUuid.value ?: return null
        val app = getApplication<com.ai.guardian.GuardianApplication>()
        val parentUuid = app.container.deviceSyncManager.deviceUuid
        
        val decryptedChars = com.ai.guardian.security.PinSyncManager.getInstance(app)
            .decryptReceivedPayload(
                state = state,
                parentKeyManager = app.container.parentKeyManager,
                currentParentId = parentUuid,
                currentChildId = childUuid,
                currentPairId = parentUuid
            ) ?: return null
            
        val pinString = String(decryptedChars)
        // Immediately zero-out the decrypted character array for security
        decryptedChars.fill('\u0000')
        return pinString
    }
}
