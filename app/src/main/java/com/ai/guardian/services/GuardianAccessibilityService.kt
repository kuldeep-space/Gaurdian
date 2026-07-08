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
    private var currentClassName: String = ""
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
                android.util.Log.d("GuardianAI_Debug", "[AS] SCREEN_OFF received. Resetting foreground package states, invalidating trusted session, and pausing polling.")
                currentPackageName = ""
                lastForegroundPackage = ""
                invalidateTrustedSession()
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

            // 2. Fallback removed because Device Admin was removed.
            android.util.Log.w("GuardianAI_Debug", "[Accessibility] Cannot lock screen: API level < 28 and Device Admin removed.")
        }

        fun reloadProtectedApps() {
            // A remote device changed the protection state.
            // By clearing the whitelist, any app that was just protected remotely
            // will instantly be locked by the periodic verification loop (1.5s).
            val service = getInstance()
            if (service != null) {
                resetWhitelist()
                android.util.Log.d("GuardianAI_Debug", "[AS] reloadProtectedApps: Whitelist cleared for immediate remote lock enforcement.")
            }
        }
        
        fun isServiceRunning(): Boolean = getInstance() != null
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(object : android.content.ContextWrapper(base) {
            override fun getSystemService(name: String): Any? {
                if (Context.WINDOW_SERVICE == name) {
                    try {
                        return super.getSystemService(name)
                    } catch (e: Exception) {
                        android.util.Log.e("GuardianAI_Debug", "Intercepted OEM strict mode crash for WindowManager", e)
                        return null
                    }
                }
                return super.getSystemService(name)
            }
        })
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
        android.util.Log.d("GuardianAI_Phase1", "[Init] GuardianAccessibilityService onServiceConnected() called. PID=${android.os.Process.myPid()}")
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

        // Security Observers: Invalidate session when settings or app locks are changed in Room (either locally or remotely synced)
        serviceScope.launch {
            val db = com.ai.guardian.data.AppDatabase.getDatabase(this@GuardianAccessibilityService)
            var isInitialSettings = true
            db.deviceSettingsDao().getSettingsFlow().collect {
                if (isInitialSettings) {
                    isInitialSettings = false
                } else {
                    android.util.Log.d("GuardianAI_Debug", "[Security] Settings modified. Invalidating trusted session.")
                    invalidateTrustedSession()
                }
            }
        }
        serviceScope.launch {
            val db = com.ai.guardian.data.AppDatabase.getDatabase(this@GuardianAccessibilityService)
            var isInitialAppLock = true
            db.appLockDao().getAllAppsFlow().collect {
                if (isInitialAppLock) {
                    isInitialAppLock = false
                } else {
                    android.util.Log.d("GuardianAI_Debug", "[Security] App lock rules modified. Invalidating trusted session.")
                    invalidateTrustedSession()
                }
            }
        }
        serviceScope.launch {
            val db = com.ai.guardian.data.AppDatabase.getDatabase(this@GuardianAccessibilityService)
            var isInitialPaired = true
            db.pairedDeviceDao().getAllPairedDevices().collect {
                if (isInitialPaired) {
                    isInitialPaired = false
                } else {
                    android.util.Log.d("GuardianAI_Debug", "[Security] Paired devices modified. Invalidating trusted session.")
                    invalidateTrustedSession()
                }
            }
        }
        serviceScope.launch {
            val db = com.ai.guardian.data.AppDatabase.getDatabase(this@GuardianAccessibilityService)
            var isInitialFaces = true
            db.faceDao().getAllProfilesWithTemplatesFlow().collect {
                if (isInitialFaces) {
                    isInitialFaces = false
                } else {
                    android.util.Log.d("GuardianAI_Debug", "[Security] Face profiles modified. Invalidating trusted session.")
                    invalidateTrustedSession()
                }
            }
        }

        android.util.Log.d("GuardianAI_Debug", "[AS] Periodic verification loop started.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        val eventPkg = event.packageName?.toString() ?: "null"
        
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cls = event.className?.toString()
            if (!cls.isNullOrEmpty()) {
                currentClassName = cls
                android.util.Log.v("GuardianAI_Debug", "[AS] Active ClassName updated: $currentClassName")
            }
        }

        android.util.Log.v("GuardianAI_Debug", "[AS] Event: type=$type pkg=$eventPkg cls=${event.className}")

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
        } else {
            // Package hasn't changed. But we must continuously check for sensitive screens (like going to App Info
            // or an Uninstall dialog popping up) as long as it's not our own app or the home screen.
            if (!detectedPackage.isNullOrEmpty() && detectedPackage !in homePackages && detectedPackage != "com.ai.guardian") {
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

        if (previousPackage.isNotEmpty() && previousPackage != newPackage) {
            // Only clear the whitelist for the app we just left (not Guardian itself)
            if (previousPackage != "com.ai.guardian") {
                android.util.Log.d("GuardianAI_Debug", "[AS] resetSession: clearing whitelist for $previousPackage (left foreground)")
                clearWhitelistForPackage(previousPackage)
            }

            // If the user goes to the Home screen or Recents, always invalidate the trusted session
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

    private fun isSensitiveSettingsScreen(packageName: String): Pair<Boolean, String> {
        val cls = currentClassName.lowercase()
        // 1. Fast Path: Class Name Check
        if (cls.contains("deviceadmin") || cls.contains("device_admin")) return Pair(true, "access Device Administrator settings")
        if (cls.contains("accessibility") && !cls.contains("shortcut")) return Pair(true, "access Accessibility settings")
        if (cls.contains("installedappdetails") || cls.contains("appinfo") || cls.contains("applicationdetails") || cls.contains("appmemoryusage")) return Pair(true, "access App Details/Uninstall")

        // 2. Robust Path: Node Text Scan (for OEMs like Vivo)
        var sensitiveFound = false
        var reason = ""
        try {
            val root = rootInActiveWindow ?: return Pair(false, "")
            val queue = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
            queue.add(root)
            
            var nodesChecked = 0
            while (queue.isNotEmpty() && nodesChecked < 100) { // Limit depth to prevent lag
                val node = queue.poll()
                if (node == null) continue
                nodesChecked++
                
                val text = node.text?.toString()?.lowercase() ?: node.contentDescription?.toString()?.lowercase()
                if (text != null) {
                    if (text == "device admin apps" || text == "device administrators") { sensitiveFound = true; reason = "access Device Admin apps"; break }
                    if (text == "uninstall" || text == "deactivate" || text == "force stop") { sensitiveFound = true; reason = "uninstall or modify apps"; break }
                    // We avoid blindly matching "accessibility" text to prevent false positives on main settings menu,
                    // relying on the class name for the actual Accessibility submenu.
                }
                
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child != null) queue.add(child)
                }
                try { node.recycle() } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            android.util.Log.e("GuardianAI_Debug", "[AS] isSensitiveSettingsScreen error: ${e.message}")
        }
        return Pair(sensitiveFound, reason)
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

            // ── Settings / Device Admin / Global Uninstall Interception ───────────────
            // We run the sensitive screen scanner for ALL apps (since uninstall popups can
            // originate from Launchers, Play Store, or OEM specific security apps like Vivo's iManager).
            val isPinConfigured = settings?.isPinConfigured ?: false
            val maintenanceActive = com.ai.guardian.security.MaintenanceModeManager.isMaintenanceModeActive()
            
            if (isPinConfigured && !maintenanceActive) {
                android.util.Log.d("GuardianAI_Debug", "[LOCK_TRACE] Before isSensitiveSettingsScreen: package=$packageName, currentClassName=$currentClassName")
                val (isSensitive, reason) = isSensitiveSettingsScreen(packageName)
                android.util.Log.d("GuardianAI_Debug", "[LOCK_TRACE] After isSensitiveSettingsScreen: reason=$reason, result=$isSensitive")
                if (isSensitive) {
                    android.util.Log.d("GuardianAI_Debug", "[PinGate] Sensitive Settings detected: $reason. Launching PinVerificationActivity.")
                    withContext(Dispatchers.Main) {
                        val intent = Intent(applicationContext, com.ai.guardian.ui.screens.PinVerificationActivity::class.java).apply {
                            putExtra("EXTRA_ACTION_NAME", reason)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        }
                        try {
                            startActivity(intent)
                        } catch (t: Throwable) {
                            android.util.Log.e("GuardianAI_Debug", "[PinGate] Failed to launch PinVerificationActivity", t)
                        }
                    }
                    return@launch
                }
            }

            // 1. Emergency Remote Lock Check
            val isRemotelyLocked = settings?.isRemotelyLocked ?: false
            if (isRemotelyLocked) {
                android.util.Log.d("GuardianAI_Debug", "[Accessibility] Device is remotely locked. Intercepting $packageName")
                val showOverlay = settings?.showLockScreenOverlay ?: true
                withContext(Dispatchers.Main) {
                    val intent = Intent(applicationContext, com.ai.guardian.ui.screens.AppLockActivity::class.java).apply {
                        putExtra("EXTRA_PACKAGE_NAME", packageName)
                        putExtra("EXTRA_SHOW_OVERLAY", showOverlay)
                        putExtra("EXTRA_IS_REMOTE_LOCK", true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    }
                    try {
                        val currentVal = com.ai.guardian.services.AppLockLaunchManager.isLaunching.get()
                        val res = com.ai.guardian.services.AppLockLaunchManager.isLaunching.compareAndSet(false, true)
                        android.util.Log.d("GuardianAI_Debug", "[LOCK_TRACE] Before remote compareAndSet: current=$currentVal, result=$res")
                        if (!res) {
                            return@withContext
                        }
                        com.ai.guardian.services.AppLockLaunchManager.scheduleLaunchTimeout()
                        startActivity(intent)
                    } catch (t: Throwable) {
                        com.ai.guardian.services.AppLockLaunchManager.reset()
                        if (com.ai.guardian.BuildConfig.DEBUG) {
                            android.util.Log.e("GuardianAI_Debug", "Failed to launch AppLockActivity for remote lock", t)
                        }
                    }
                }
                return@launch
            }

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
                        val currentVal = com.ai.guardian.services.AppLockLaunchManager.isLaunching.get()
                        val res = com.ai.guardian.services.AppLockLaunchManager.isLaunching.compareAndSet(false, true)
                        android.util.Log.d("GuardianAI_Debug", "[LOCK_TRACE] Before standard compareAndSet: current=$currentVal, result=$res")
                        if (!res) {
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
