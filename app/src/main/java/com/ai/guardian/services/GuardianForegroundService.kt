package com.ai.guardian.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

class GuardianForegroundService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    
    private var temporarilyUnlockedPackages = mutableMapOf<String, Long>()

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("GuardianAI_Phase1", "[Init] GuardianForegroundService onCreate() called. PID=${android.os.Process.myPid()}")
        android.util.Log.d("GuardianAI_Debug", "[FS] onCreate() called. PID=${android.os.Process.myPid()}")
        
        com.ai.guardian.utils.BatteryOptimizationUtil.checkAndLogOptimizationStatus(this)
        
        startForegroundService()
        
        val container = (applicationContext as com.ai.guardian.GuardianApplication).container
        container.syncEngineManager.start()
        
        // Phase 2: Fire process restart audit event and apply Device Owner policies
        container.tamperDetectionManager.log(
            com.ai.guardian.security.AuditEvent.PROCESS_STARTED,
            "GuardianForegroundService started. PID=${android.os.Process.myPid()}"
        )
        container.devicePolicyController.applyAllPolicies()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        android.util.Log.d("GuardianAI_Debug", "[FS] onStartCommand() action=$action")
        
        val container = (applicationContext as com.ai.guardian.GuardianApplication).container
        container.syncEngineManager.start()
        
        if (action == ACTION_PACKAGE_CHANGED) {
            val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return START_STICKY
            handlePackageChanged(packageName)
        } else if (action == ACTION_WHITELIST_PACKAGE) {
            val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return START_STICKY
            // Delegate whitelist management to GuardianAccessibilityService
            GuardianAccessibilityService.whitelistPackage(packageName)
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        android.util.Log.e("GuardianAI_Debug", "[FS] onTaskRemoved() called! PID=${android.os.Process.myPid()}.")

        if (com.ai.guardian.utils.OemRestrictionDetector.isOemRestricted()) {
            android.util.Log.d("GuardianAI_Debug", "[FS] Aggressive OEM detected. Scheduling AlarmManager fallback restart in 5s.")
            val restartIntent = Intent(this, GuardianForegroundService::class.java)
            val pendingIntent = android.app.PendingIntent.getService(
                this,
                REQUEST_CODE_SERVICE_RESTART,
                restartIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            alarmManager.set(
                android.app.AlarmManager.ELAPSED_REALTIME,
                android.os.SystemClock.elapsedRealtime() + 5000L,
                pendingIntent
            )
        } else {
            android.util.Log.d("GuardianAI_Debug", "[FS] Non-restrictive AOSP/Pixel/Samsung device. Relying on START_STICKY for service recovery.")
        }
    }

    private fun handlePackageChanged(packageName: String) {
        if (packageName == "com.ai.guardian") {
            android.util.Log.d("GuardianAI_Debug", "[ForegroundService] Ignoring own app package: $packageName")
            return
        }

        serviceScope.launch {
            val container = (applicationContext as com.ai.guardian.GuardianApplication).container
            val isGlobalProtectionOn = container.deviceSettingsDao.getSettings()?.isProtectionEnabled ?: false
            if (!isGlobalProtectionOn) {
                android.util.Log.d("GuardianAI_Debug", "[ForegroundService] Global protection is disabled. Ignoring $packageName")
                return@launch
            }

            val isAppProtected = container.appLockDao.isAppProtected(packageName) ?: false
            
            if (isAppProtected) {
                // Check whitelist on both local map and accessibility service companion object
                val isWhitelisted = GuardianAccessibilityService.isPackageWhitelisted(packageName) || 
                                    (temporarilyUnlockedPackages[packageName]?.let { System.currentTimeMillis() < it } ?: false)
                if (isWhitelisted) {
                    android.util.Log.d("GuardianAI_Debug", "[ForegroundService] $packageName is whitelisted. Ignoring.")
                    return@launch
                }

                android.util.Log.d("GuardianAI_Debug", "[ForegroundService] Launching AppLockActivity for $packageName")
                // Launch the Invisible AppLockActivity
                val intent = Intent(applicationContext, com.ai.guardian.ui.screens.AppLockActivity::class.java).apply {
                    putExtra("EXTRA_PACKAGE_NAME", packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }
                try {
                    if (!com.ai.guardian.services.AppLockLaunchManager.isLaunching.compareAndSet(false, true)) {
                        return@launch
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

    private fun startForegroundService() {
        val channelId = "guardian_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Guardian Background Protection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Guardian running in the background to protect your apps"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Guardian is Active")
            .setContentText("Your device is protected.")
            .setSmallIcon(android.R.drawable.ic_secure)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        android.util.Log.e("GuardianAI_Debug", "[FS] onDestroy() called! PID=${android.os.Process.myPid()}")
        super.onDestroy()
        val container = (applicationContext as com.ai.guardian.GuardianApplication).container
        container.syncEngineManager.stop()
        serviceScope.cancel()
    }

    companion object {
        const val ACTION_PACKAGE_CHANGED = "ACTION_PACKAGE_CHANGED"
        const val EXTRA_PACKAGE_NAME = "EXTRA_PACKAGE_NAME"
        const val ACTION_WHITELIST_PACKAGE = "ACTION_WHITELIST_PACKAGE"
        private const val REQUEST_CODE_SERVICE_RESTART = 1001
    }
}
