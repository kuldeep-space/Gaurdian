package com.ai.guardian.services

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.channels.BufferOverflow

@OptIn(kotlinx.coroutines.FlowPreview::class)
class GuardianAccessibilityService : AccessibilityService() {

    // SupervisorJob: if verificationJob crashes, it does not cancel serviceScope.
    // This allows startPeriodicVerification() to create a fresh child job without
    // destroying the parent scope. With plain Job(), one exception in a child
    // propagates upward and kills the entire scope — the loop can never restart.
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var verificationJob: Job? = null

    private var currentPackageName: String = ""
    private var lastForegroundPackage: String = ""
    private val windowEventFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var homePackages = setOf<String>()

    private fun updateHomePackages() {
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
        val resolveInfos = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        homePackages = resolveInfos.map { it.activityInfo.packageName }.toSet()
        if (com.ai.guardian.BuildConfig.DEBUG) {
            android.util.Log.d("GuardianAI_Debug", "[AS] Discovered Home packages: $homePackages")
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            android.util.Log.d("GuardianAI_Debug", "[AS] screenReceiver.onReceive() action=${intent?.action}")
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                android.util.Log.d("GuardianAI_Debug", "[AS] SCREEN_OFF received. Resetting foreground package states and pausing polling.")
                currentPackageName = ""
                lastForegroundPackage = ""
                verificationJob?.cancel()
            } else if (intent?.action == Intent.ACTION_SCREEN_ON) {
                android.util.Log.d("GuardianAI_Debug", "[AS] SCREEN_ON received. Resuming periodic verification.")
                startPeriodicVerification()
            }
        }
    }

    companion object {
        @Volatile
        private var instance: GuardianAccessibilityService? = null

        fun getInstance(): GuardianAccessibilityService? = instance

        @Volatile
        private var globalTrustedAuthExpiryTimestamp: Long = 0L

        fun reportSuccessfulAuthentication() {
            val randomDurationMs = kotlin.random.Random.nextLong(60_000L, 180_000L) // 1 to 3 minutes
            globalTrustedAuthExpiryTimestamp = android.os.SystemClock.elapsedRealtime() + randomDurationMs
            if (com.ai.guardian.BuildConfig.DEBUG) {
                val min = randomDurationMs / 60000
                val sec = (randomDurationMs % 60000) / 1000
                android.util.Log.d("GuardianAI_Debug", "[SmartCache] Authentication Success. Random timeout generated. Global trusted session established. Expires in $min min $sec sec")
            }
        }

        fun invalidateTrustedSession() {
            globalTrustedAuthExpiryTimestamp = 0L
            resetWhitelist()
            if (com.ai.guardian.BuildConfig.DEBUG) {
                android.util.Log.d("GuardianAI_Debug", "[SmartCache] Trusted session invalidated")
            }
        }

        // Thread-safe map for package whitelist: packageName -> expiryTimestampMs
        private val temporarilyUnlockedPackages = java.util.concurrent.ConcurrentHashMap<String, Long>()

        fun whitelistPackage(packageName: String, durationMs: Long = 24 * 60 * 60 * 1000L) {
            val expiryTs = System.currentTimeMillis() + durationMs
            temporarilyUnlockedPackages[packageName] = expiryTs
            android.util.Log.d("GuardianAI_Debug", "[Whitelist] ADD: $packageName expiresAt=$expiryTs (in ${durationMs}ms) PID=${android.os.Process.myPid()}")
        }

        fun isPackageWhitelisted(packageName: String): Boolean {
            val expiry = temporarilyUnlockedPackages[packageName] ?: run {
                android.util.Log.v("GuardianAI_Debug", "[Whitelist] CHECK: $packageName — NOT FOUND (no entry)")
                return false
            }
            val now = System.currentTimeMillis()
            val isWhitelisted = now < expiry
            val ttlMs = expiry - now
            if (isWhitelisted) {
                android.util.Log.d("GuardianAI_Debug", "[Whitelist] CHECK: $packageName — VALID (TTL=${ttlMs}ms remaining)")
            } else {
                android.util.Log.d("GuardianAI_Debug", "[Whitelist] CHECK: $packageName — EXPIRED (${-ttlMs}ms ago). Removing.")
                temporarilyUnlockedPackages.remove(packageName)
            }
            return isWhitelisted
        }

        fun clearWhitelistForPackage(packageName: String) {
            val removed = temporarilyUnlockedPackages.remove(packageName)
            if (removed != null) {
                android.util.Log.d("GuardianAI_Debug", "[Whitelist] REMOVE: $packageName (was expiring at $removed) PID=${android.os.Process.myPid()}")
            } else {
                android.util.Log.d("GuardianAI_Debug", "[Whitelist] REMOVE: $packageName — was not in whitelist (already cleared or never added)")
            }
        }

        fun resetWhitelist() {
            temporarilyUnlockedPackages.clear()
            android.util.Log.d("GuardianAI_Debug", "[Accessibility] Reset entire whitelist")
        }

        fun lockDeviceScreen(context: Context) {
            // 1. Try Accessibility performGlobalAction first (requires API 28+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val service = getInstance()
                if (service != null) {
                    val success = service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                    if (success) {
                        android.util.Log.d("GuardianAI_Debug", "[Accessibility] Screen locked successfully via Accessibility API.")
                        return
                    }
                }
            }

            // 2. Fallback to DevicePolicyManager (requires Device Admin to be active)
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, GuardianDeviceAdmin::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                try {
                    dpm.lockNow()
                    android.util.Log.d("GuardianAI_Debug", "[Accessibility] Screen locked successfully via DevicePolicyManager.")
                } catch (e: Exception) {
                    android.util.Log.e("GuardianAI_Debug", "[Accessibility] Error locking screen via DevicePolicyManager", e)
                }
            } else {
                android.util.Log.w("GuardianAI_Debug", "[Accessibility] Device admin not active, cannot lock screen.")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        updateHomePackages()
        android.util.Log.d("GuardianAI_Debug", "[AS] onCreate() called. PID=${android.os.Process.myPid()}")
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(screenReceiver, screenFilter)
        }
        android.util.Log.d("GuardianAI_Debug", "[AS] Receivers registered. Android SDK=${android.os.Build.VERSION.SDK_INT}")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        android.util.Log.d("GuardianAI_Debug", "[AS] onServiceConnected() called. PID=${android.os.Process.myPid()}")
        
        // Reset current session state and clear stale whitelist entries
        resetWhitelist()
        currentPackageName = ""

        // Ensure the foreground service is running when accessibility is granted
        val intent = Intent(this, GuardianForegroundService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        // Start the lightweight periodic verification loop
        startPeriodicVerification()
        
        serviceScope.launch {
            windowEventFlow
                .debounce(200)
                .collect {
                    handlePackageChangeCheck()
                }
        }
        android.util.Log.d("GuardianAI_Debug", "[AS] Periodic verification loop started.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        val eventPkg = event.packageName?.toString() ?: "null"
        android.util.Log.v("GuardianAI_Debug", "[AS] Event: type=$type pkg=$eventPkg")

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            
            windowEventFlow.tryEmit(Unit)
        }
    }

    private fun startPeriodicVerification() {
        verificationJob?.cancel()
        verificationJob = serviceScope.launch {
            android.util.Log.d("GuardianAI_Debug", "[AS] verificationJob started. isActive=$isActive PID=${android.os.Process.myPid()}")
            while (isActive) {
                delay(1500) // Lightweight fallback interval
                // Log occasionally to prove the loop is alive without flooding logcat
                if (System.currentTimeMillis() % 6000 < 1500) {
                    android.util.Log.v("GuardianAI_Debug", "[AS] verificationJob alive. PID=${android.os.Process.myPid()}")
                }
                handlePackageChangeCheck()
                checkRescanTimer()
            }
            // This line executes only if the loop exits normally (isActive became false).
            // With SupervisorJob, an exception would skip straight to the catch handler
            // on the job itself, not here.
            android.util.Log.e("GuardianAI_Debug", "[AS] verificationJob loop exited normally (isActive=false). This is unexpected.")
        }
        // invokeOnCompletion fires when the job finishes for ANY reason:
        // cancellation, exception, or normal completion.
        // This is our definitive log of WHY monitoring stopped.
        verificationJob?.invokeOnCompletion { cause ->
            when {
                cause == null -> android.util.Log.e(
                    "GuardianAI_Debug",
                    "[AS] verificationJob COMPLETED normally. Should never happen unless scope cancelled."
                )
                cause is kotlinx.coroutines.CancellationException -> {
                    if (com.ai.guardian.BuildConfig.DEBUG) {
                        android.util.Log.w("GuardianAI_Debug", "[AS] verificationJob CANCELLED. Cause: ${cause.message} PID=${android.os.Process.myPid()}")
                    }
                }
                else -> {
                    if (com.ai.guardian.BuildConfig.DEBUG) {
                        android.util.Log.e("GuardianAI_Debug", "[AS] verificationJob CRASHED with exception: ${cause::class.simpleName}: ${cause.message} PID=${android.os.Process.myPid()}")
                    }
                    // Auto-restart loop to prevent silent failure
                    serviceScope.launch {
                        delay(3000)
                        if (com.ai.guardian.BuildConfig.DEBUG) {
                            android.util.Log.d("GuardianAI_Debug", "[AS] verificationJob restarting after crash")
                        }
                        startPeriodicVerification()
                    }
                }
            }
        }
        if (com.ai.guardian.BuildConfig.DEBUG) {
            android.util.Log.d("GuardianAI_Debug", "[AS] verificationJob registered. isActive=${verificationJob?.isActive}")
        }
    }

    private suspend fun checkRescanTimer() {
        if (currentPackageName.isEmpty() || currentPackageName == "com.ai.guardian") return
        
        val container = (applicationContext as com.ai.guardian.GuardianApplication).container
        val settings = container.deviceSettingsDao.getSettings()
        val isGlobalProtectionOn = settings?.isProtectionEnabled ?: false
        if (!isGlobalProtectionOn) return

        val isAppProtected = container.appLockDao.isAppProtected(currentPackageName) ?: false
        if (isAppProtected) {
            val isSmartCacheEnabled = (settings?.trustedAuthDurationMinutes ?: 1) > 0
            if (isSmartCacheEnabled && globalTrustedAuthExpiryTimestamp > 0 && android.os.SystemClock.elapsedRealtime() > globalTrustedAuthExpiryTimestamp) {
                if (com.ai.guardian.BuildConfig.DEBUG) {
                    android.util.Log.d("GuardianAI_Debug", "[Rescan] Random timeout expired for $currentPackageName! Forcing re-authentication.")
                }
                
                // Reset timer and invalidate session
                invalidateTrustedSession()
                
                // Clear whitelist to force lock
                clearWhitelistForPackage(currentPackageName)
                
                // Launch lock screen silently
                val showOverlay = settings?.showLockScreenOverlay ?: true
                withContext(Dispatchers.Main) {
                    val intent = Intent(applicationContext, com.ai.guardian.ui.screens.AppLockActivity::class.java).apply {
                        putExtra("EXTRA_PACKAGE_NAME", currentPackageName)
                        putExtra("EXTRA_SHOW_OVERLAY", showOverlay)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    }
                    try {
                        if (!com.ai.guardian.services.AppLockLaunchManager.isLaunching.compareAndSet(false, true)) {
                            return@withContext
                        }
                        com.ai.guardian.services.AppLockLaunchManager.scheduleLaunchTimeout()
                        startActivity(intent)
                    } catch (t: Throwable) {
                        com.ai.guardian.services.AppLockLaunchManager.reset()
                        if (com.ai.guardian.BuildConfig.DEBUG) {
                            android.util.Log.e("GuardianAI_Debug", "Failed to launch AppLockActivity", t)
                        }
                    }
                }
            }
        }
    }

    private fun handlePackageChangeCheck() {
        val detectedPackage = getForegroundPackageName()
        if (detectedPackage.isNullOrEmpty()) {
            if (currentPackageName.isNotEmpty() && currentPackageName != "com.ai.guardian") {
                clearWhitelistForPackage(currentPackageName)
            }
            currentPackageName = ""
            return
        }

        if (detectedPackage != currentPackageName) {
            lastForegroundPackage = currentPackageName
            val prevPackage = lastForegroundPackage

            // Full authentication state snapshot on every package transition.
            // This single log line captures everything needed to diagnose stale sessions.
            val whitelistSnapshot = temporarilyUnlockedPackages.entries.joinToString(", ") {
                val ttl = it.value - System.currentTimeMillis()
                "${it.key}(TTL=${ttl}ms)"
            }.ifEmpty { "<empty>" }

            android.util.Log.d(
                "GuardianAI_Debug",
                "[AuthState] PACKAGE CHANGE DETECTED" +
                "\n  lastForegroundPackage   = $prevPackage" +
                "\n  currentForegroundPackage = $detectedPackage" +
                "\n  whitelist               = $whitelistSnapshot" +
                "\n  verificationJob.isActive= ${verificationJob?.isActive}" +
                "\n  serviceScope.isActive   = ${serviceScope.isActive}" +
                "\n  PID                     = ${android.os.Process.myPid()}"
            )

            resetSessionIfNeeded(prevPackage, detectedPackage)

            if (detectedPackage.isNullOrEmpty() || detectedPackage in homePackages) {
                currentPackageName = ""
                if (com.ai.guardian.BuildConfig.DEBUG) {
                    android.util.Log.d("GuardianAI_Debug", "[AS] Home/Recents/Null detected. Resetting currentPackageName to empty.")
                }
            } else {
                currentPackageName = detectedPackage
                checkAndLockPackage(detectedPackage)
            }
        }
    }

    private fun getForegroundPackageName(): String? {
        // 1. Try rootInActiveWindow first
        val rootNode = rootInActiveWindow
        if (rootNode != null) {
            val pkg = rootNode.packageName?.toString()
            if (!pkg.isNullOrEmpty()) {
                android.util.Log.v("GuardianAI_Debug", "[AS] getForeground via rootInActiveWindow: $pkg")
                return pkg
            }
        }

        // 2. Fallback to querying windows (requires flagRetrieveInteractiveWindows)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            try {
                val activeWindow = windows.find { it.isActive } ?: windows.find { it.isFocused }
                val pkg = activeWindow?.root?.packageName?.toString()
                if (!pkg.isNullOrEmpty()) {
                    android.util.Log.v("GuardianAI_Debug", "[AS] getForeground via windows fallback: $pkg")
                    return pkg
                }
            } catch (e: Exception) {
                android.util.Log.w("GuardianAI_Debug", "[AS] getForeground windows fallback error: ${e.message}")
            }
        }

        android.util.Log.w("GuardianAI_Debug", "[AS] getForeground: could not detect foreground package")
        return null
    }

    private fun resetSessionIfNeeded(previousPackage: String, newPackage: String) {
        android.util.Log.d("GuardianAI_Debug", "[AS] resetSessionIfNeeded: prev=$previousPackage new=$newPackage")

        // Never reset when our own lock screen appears — we're in the middle of authentication.
        if (newPackage == "com.ai.guardian") {
            android.util.Log.d("GuardianAI_Debug", "[AS] resetSession: skipping (our own UI is coming up)")
            return
        }

        // When a protected app leaves foreground (transitions to launcher or any other app),
        // immediately clear its whitelist entry. This is the replacement for the deprecated
        // ACTION_CLOSE_SYSTEM_DIALOGS broadcast which no longer fires on Android 12+.
        //
        // Example: com.whatsapp → com.android.launcher3
        //   previousPackage = com.whatsapp  ← clear this
        //   newPackage      = com.android.launcher3
        //
        // We do NOT clear if previousPackage is "com.ai.guardian" because that transition
        // means authentication just completed and we're returning to the target app.
        if (previousPackage.isNotEmpty() &&
            previousPackage != newPackage &&
            previousPackage != "com.ai.guardian"
        ) {
            android.util.Log.d("GuardianAI_Debug", "[AS] resetSession: clearing whitelist for $previousPackage (left foreground)")
            clearWhitelistForPackage(previousPackage)

            if (newPackage in homePackages) {
                if (com.ai.guardian.BuildConfig.DEBUG) {
                    android.util.Log.d("GuardianAI_Debug", "[AS] Home Launcher / Recents detected. Invalidating trusted session.")
                }
                invalidateTrustedSession()
            } else if (newPackage == "com.android.systemui") {
                if (com.ai.guardian.BuildConfig.DEBUG) {
                    android.util.Log.d("GuardianAI_Debug", "[AS] SystemUI (Notification/Volume/Settings) detected. Preserving trusted session.")
                }
            }
        }
    }

    private fun checkAndLockPackage(packageName: String) {
        if (packageName == "com.ai.guardian") {
            return
        }

        // Check if the package is whitelisted/session is active
        val whitelisted = isPackageWhitelisted(packageName)
        android.util.Log.d("GuardianAI_Debug", "[AS] checkAndLockPackage: $packageName whitelisted=$whitelisted currentWhitelistKeys=${temporarilyUnlockedPackages.keys}")
        if (whitelisted) {
            android.util.Log.d("GuardianAI_Debug", "[AS] $packageName is whitelisted. Skipping lock.")
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            val container = (applicationContext as com.ai.guardian.GuardianApplication).container
            val settings = container.deviceSettingsDao.getSettings()
            
            val isGlobalProtectionOn = settings?.isProtectionEnabled ?: false
            if (!isGlobalProtectionOn) {
                android.util.Log.d("GuardianAI_Debug", "[Accessibility] Global protection is disabled. Ignoring $packageName")
                return@launch
            }

            val isAppProtected = container.appLockDao.isAppProtected(packageName) ?: false
            if (isAppProtected) {
                // Check Smart Re-authentication Cache
                val isSmartCacheEnabled = (settings?.trustedAuthDurationMinutes ?: 1) > 0
                if (isSmartCacheEnabled && android.os.SystemClock.elapsedRealtime() < globalTrustedAuthExpiryTimestamp) {
                    if (com.ai.guardian.BuildConfig.DEBUG) {
                        android.util.Log.d("GuardianAI_Debug", "[SmartCache] Trusted session restored! Skipping face scan for $packageName")
                    }
                    whitelistPackage(packageName)
                    return@launch
                }

                if (com.ai.guardian.BuildConfig.DEBUG) {
                    android.util.Log.d("GuardianAI_Debug", "[Accessibility] Lock launched for $packageName")
                }
                val showOverlay = settings?.showLockScreenOverlay ?: true
                withContext(Dispatchers.Main) {
                    val intent = Intent(applicationContext, com.ai.guardian.ui.screens.AppLockActivity::class.java).apply {
                        putExtra("EXTRA_PACKAGE_NAME", packageName)
                        putExtra("EXTRA_SHOW_OVERLAY", showOverlay)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    }
                    try {
                        if (!com.ai.guardian.services.AppLockLaunchManager.isLaunching.compareAndSet(false, true)) {
                            return@withContext
                        }
                        com.ai.guardian.services.AppLockLaunchManager.scheduleLaunchTimeout()
                        startActivity(intent)
                    } catch (t: Throwable) {
                        com.ai.guardian.services.AppLockLaunchManager.reset()
                        if (com.ai.guardian.BuildConfig.DEBUG) {
                            android.util.Log.e("GuardianAI_Debug", "Failed to launch AppLockActivity", t)
                        }
                    }
                }
            }
        }
    }

    override fun onInterrupt() {
        android.util.Log.w("GuardianAI_Debug", "[AS] onInterrupt() called")
    }

    override fun onDestroy() {
        android.util.Log.e("GuardianAI_Debug", "[AS] onDestroy() called! PID=${android.os.Process.myPid()} — accessibility service is being destroyed!")
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            android.util.Log.w("GuardianAI_Debug", "[AS] screenReceiver already unregistered: ${e.message}")
        }
        serviceScope.cancel()
    }
}
