package com.ai.guardian.services

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

object AppLockLaunchManager {
    val isLaunching = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        if (isLaunching.compareAndSet(true, false)) {
            android.util.Log.w("GuardianAI_Debug", "[Lock] Launch safety net timeout fired. Resetting guard flag.")
        }
    }

    fun scheduleLaunchTimeout() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(timeoutRunnable, com.ai.guardian.ai.FaceRecognitionConfig.APP_LOCK_LAUNCH_TIMEOUT_MS)
    }

    fun cancelLaunchTimeout() {
        handler.removeCallbacksAndMessages(null)
    }

    fun reset() {
        cancelLaunchTimeout()
        isLaunching.set(false)
    }
}
