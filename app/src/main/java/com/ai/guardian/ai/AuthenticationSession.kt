package com.ai.guardian.ai

import kotlinx.coroutines.Job
import android.os.SystemClock

class AuthenticationSession {
    var lightingState: LightingState = LightingState.NORMAL
    var emaLuminance: Float = -1f // -1 indicates uninitialized
    var currentExposureIndex: Int = 0
    var lastExposureUpdateTime: Long = 0L
    var lastBrightnessSampleTime: Long = 0L
    
    var isScreenBrightened: Boolean = false
    var originalScreenBrightness: Float = -1f
    var hasDetectedFace: Boolean = false
    
    var secondInferenceConsumed: Boolean = false
    var timeoutJob: Job? = null
    
    // For warm-up and hysteresis
    var consecutiveLightingStateMatches = 0
    var pendingLightingState: LightingState = LightingState.NORMAL
    
    var framesAnalyzed: Int = 0
    val sessionStartTime: Long = SystemClock.elapsedRealtime()

    fun updateEma(luminance: Int) {
        if (emaLuminance < 0f) {
            emaLuminance = luminance.toFloat() // Initialize
        } else {
            val alpha = FaceRecognitionConfig.EMA_ALPHA
            emaLuminance = alpha * luminance + (1f - alpha) * emaLuminance
        }
    }

    fun destroy() {
        timeoutJob?.cancel()
        timeoutJob = null
    }
}
