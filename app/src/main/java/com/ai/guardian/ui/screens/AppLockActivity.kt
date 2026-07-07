package com.ai.guardian.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ai.guardian.GuardianApplication
import com.ai.guardian.ai.*
import com.ai.guardian.data.entity.FaceProfileEntity
import com.ai.guardian.data.entity.RecognitionHistoryEntity
import com.ai.guardian.services.GuardianForegroundService
import com.ai.guardian.ui.theme.GuardianAITheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AppLockActivity : ComponentActivity() {
    enum class SessionLifecycleState {
        INITIALIZING,
        RUNNING,
        CLEANING_UP,
        DESTROYED
    }

    private val cleanedUp = java.util.concurrent.atomic.AtomicBoolean(false)
    private val sessionInitialized = java.util.concurrent.atomic.AtomicBoolean(false)
    private val isProcessingRef = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var sessionState = SessionLifecycleState.INITIALIZING

    private var isCameraBound = false
    private var activeSession: AuthenticationSession? = null
    private var isUnlockedRef: Boolean = false
    private var packageNameRef: String = ""

    private var engine: FaceBiometricEngine? = null
    private var cameraExecutor: ExecutorService? = null
    private var camera: Camera? = null

    private fun transitionToState(newState: SessionLifecycleState): Boolean {
        synchronized(this) {
            val current = sessionState
            val isValid = when (current) {
                SessionLifecycleState.INITIALIZING -> newState == SessionLifecycleState.RUNNING || newState == SessionLifecycleState.CLEANING_UP
                SessionLifecycleState.RUNNING -> newState == SessionLifecycleState.CLEANING_UP
                SessionLifecycleState.CLEANING_UP -> newState == SessionLifecycleState.DESTROYED
                SessionLifecycleState.DESTROYED -> false
            }
            if (isValid) {
                sessionState = newState
                android.util.Log.d("GuardianAI_Debug", "[Session] Transitioned state: $current -> $newState")
                return true
            } else {
                if (com.ai.guardian.BuildConfig.DEBUG) {
                    android.util.Log.w("GuardianAI_Debug", "[Session] REJECTED invalid transition: $current -> $newState")
                }
                return false
            }
        }
    }

    fun performCleanup(shouldLock: Boolean, logReason: String) {
        if (!cleanedUp.compareAndSet(false, true)) return

        transitionToState(SessionLifecycleState.CLEANING_UP)
        android.util.Log.d("GuardianAI_Debug", "[Lock] performCleanup() reason=$logReason shouldLock=$shouldLock")

        // 1. Reset the launch coordinator
        com.ai.guardian.services.AppLockLaunchManager.reset()

        // 2. Destroy session timeout and resources
        activeSession?.let { sess ->
            try {
                sess.destroy()
            } catch (e: Exception) {}
        }

        // 3. Restore exposure/brightness if needed
        try {
            if (activeSession?.currentExposureIndex != 0) {
                camera?.cameraControl?.setExposureCompensationIndex(0)
            }
        } catch (e: Exception) {}

        try {
            if (activeSession?.isScreenBrightened == true && activeSession?.originalScreenBrightness ?: -1f >= 0f) {
                val attrs = window.attributes
                attrs.screenBrightness = activeSession?.originalScreenBrightness ?: -1f
                window.attributes = attrs
            }
        } catch (e: Exception) {}

        // 4. Unbind CameraX only if bound
        if (isCameraBound) {
            try {
                val cameraProvider = ProcessCameraProvider.getInstance(this).get()
                cameraProvider.unbindAll()
            } catch (e: Exception) {}
            isCameraBound = false
        }

        // 5. Shutdown camera executor asynchronously off main thread
        cameraExecutor?.let { executor ->
            val localExecutor = executor
            java.lang.Thread {
                localExecutor.shutdown()
                try {
                    if (!localExecutor.awaitTermination(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        localExecutor.shutdownNow()
                    }
                } catch (ie: InterruptedException) {
                    localExecutor.shutdownNow()
                    java.lang.Thread.currentThread().interrupt()
                }
            }.start()
        }
        cameraExecutor = null

        // 6. Close engine
        try {
            engine?.close()
        } catch (e: Exception) {}
        engine = null

        // 7. Log history asynchronously
        if (shouldLock && !isUnlockedRef) {
            val pkg = packageNameRef
            val applicationCtx = applicationContext
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                try {
                    val container = (applicationCtx as com.ai.guardian.GuardianApplication).container
                    container.recognitionHistoryDao.insertHistory(
                        RecognitionHistoryEntity(
                            profileId = null,
                            protectedAppPackage = pkg,
                            timestamp = System.currentTimeMillis(),
                            authResult = false,
                            failureReason = logReason,
                            similarityScore = null,
                            recognitionTimeMs = null,
                            deviceOrientation = 0,
                            recognitionType = "APP_UNLOCK"
                        )
                    )
                } catch (e: Exception) {}
            }

            com.ai.guardian.services.GuardianAccessibilityService.lockDeviceScreen(this)
        }

        transitionToState(SessionLifecycleState.DESTROYED)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.ai.guardian.services.AppLockLaunchManager.cancelLaunchTimeout()
        super.onCreate(savedInstanceState)
        if (!sessionInitialized.compareAndSet(false, true)) {
            android.util.Log.w("GuardianAI_Debug", "[Lock] Activity session already initialized. Skipping recreate.")
            finish()
            return
        }
        val packageName = intent.getStringExtra("EXTRA_PACKAGE_NAME") ?: "Unknown App"
        val showOverlay = intent.getBooleanExtra("EXTRA_SHOW_OVERLAY", true)
        android.util.Log.d("GuardianAI_Debug", "[Lock] onCreate() pkg=$packageName showOverlay=$showOverlay")
        
        if (!showOverlay) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }

        engine = FaceBiometricEngine(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        setContent {
            GuardianAITheme {
                InvisibleLockScreen(packageName = packageName, engine = engine, initialShowOverlay = showOverlay)
            }
        }
    }

    override fun onDestroy() {
        performCleanup(shouldLock = false, logReason = "Activity destroyed")
        super.onDestroy()
    }

    @Composable
    fun InvisibleLockScreen(packageName: String, engine: FaceBiometricEngine?, initialShowOverlay: Boolean) {
        var showIntruderBlock by remember { mutableStateOf(false) }
        var showDebugLogs by remember { mutableStateOf(false) }
        var hasProfiles by remember { mutableStateOf(false) }
        var guidanceText by remember { mutableStateOf("") }
        val context = this@AppLockActivity
        val coroutineScope = rememberCoroutineScope()
        
        var debugLogs by remember { mutableStateOf(listOf<String>("[System] Initiating Guardian Lock...")) }

        var isUnlocked by remember { mutableStateOf(false) }
        var isScreenLocked by remember { mutableStateOf(false) }
        var showLockScreenOverlay by remember { mutableStateOf(initialShowOverlay) }

        // Session State
        val session = remember { AuthenticationSession() }
        var capabilityManager: CameraCapabilityManager? by remember { mutableStateOf(null) }
        var authState by remember { mutableStateOf(AuthenticationState.INITIALIZING) }
        
        // Second inference state
        var pendingBestScore = 0f
        var pendingBestProfile: FaceProfileEntity? = null

        LaunchedEffect(session) {
            activeSession = session
        }
        LaunchedEffect(isUnlocked) {
            isUnlockedRef = isUnlocked
        }
        LaunchedEffect(packageName) {
            packageNameRef = packageName
        }

        fun addLog(msg: String) {
            android.util.Log.d("GuardianAI_Debug", msg)
            debugLogs = (debugLogs + msg).takeLast(6)
        }

        fun cleanupAndSecure(shouldLock: Boolean, logReason: String, logType: String = "SECURITY") {
            if (cleanedUp.get()) return
            
            authState = AuthenticationState.CLEANUP
            if (shouldLock && !isUnlocked) {
                isScreenLocked = true
                addLog("[Security] $logReason. Locking.")
            }
            performCleanup(shouldLock, logReason)
            finish()
        }

        fun resetTimeout(state: LightingState) {
            session.timeoutJob?.cancel()
            val timeoutMs = RecognitionPolicyManager.getTimeoutMs(state)
            session.timeoutJob = coroutineScope.launch {
                delay(timeoutMs)
                cleanupAndSecure(shouldLock = true, logReason = "Face scan timed out", logType = "TIMEOUT")
            }
        }

        LaunchedEffect(Unit) {
            transitionToState(SessionLifecycleState.RUNNING)
            resetTimeout(LightingState.NORMAL)

            try {
                val dao = (application as GuardianApplication).container.faceDao

                val profiles = withContext(Dispatchers.IO) {
                    dao.getAllProfilesWithTemplates()
                }
                if (profiles.isNotEmpty()) {
                    engine?.loadTemplates(profiles)
                    hasProfiles = true
                    addLog("[System] Loaded ${profiles.size} profile(s)")
                } else {
                    addLog("[System] WARNING: No profiles found!")
                    cleanupAndSecure(shouldLock = true, logReason = "No profiles enrolled")
                    return@LaunchedEffect
                }
            } catch (e: Exception) {
                cleanupAndSecure(shouldLock = true, logReason = "Database failure")
                return@LaunchedEffect
            }

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && engine != null && cameraExecutor != null) {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

                cameraProviderFuture.addListener({
                    try {
                        val cameraProvider = cameraProviderFuture.get()
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setTargetResolution(android.util.Size(640, 480))
                            .build()

                        imageAnalysis.setAnalyzer(cameraExecutor!!) { imageProxy: ImageProxy ->
                            if (context.isProcessingRef.getAndSet(true) || !hasProfiles || isUnlocked || isScreenLocked || context.cleanedUp.get()) {
                                imageProxy.close()
                                context.isProcessingRef.set(false)
                                return@setAnalyzer
                            }

                            coroutineScope.launch {
                                try {
                                    val currentEngine = engine
                                    if (currentEngine == null || context.cleanedUp.get()) return@launch

                                    // 1. WARM-UP Phase
                                    session.framesAnalyzed++
                                    val timeSinceStart = SystemClock.elapsedRealtime() - session.sessionStartTime
                                    if (session.framesAnalyzed <= FaceRecognitionConfig.WARMUP_FRAMES_TO_SKIP && timeSinceStart < FaceRecognitionConfig.MAXIMUM_WARMUP_TIME_MS) {
                                        authState = AuthenticationState.WARMING_UP
                                        return@launch
                                    }

                                    // 2. BRIGHTNESS_ANALYSIS Phase
                                    if (RecognitionPolicyManager.shouldSampleBrightness(session.lastBrightnessSampleTime)) {
                                        authState = AuthenticationState.BRIGHTNESS_ANALYSIS
                                        if (!session.hasDetectedFace) {
                                            val luminance = BrightnessEstimator.estimateLuminance(imageProxy)
                                            session.updateEma(luminance)
                                        }
                                        session.lastBrightnessSampleTime = SystemClock.elapsedRealtime()
                                        
                                        val proposedState = RecognitionPolicyManager.determineLightingState(session.emaLuminance)
                                        if (proposedState == session.pendingLightingState) {
                                            session.consecutiveLightingStateMatches++
                                            if (session.consecutiveLightingStateMatches >= FaceRecognitionConfig.STABLE_STATE_COUNT_REQUIRED) {
                                                if (session.lightingState != proposedState) {
                                                    session.lightingState = proposedState
                                                    resetTimeout(session.lightingState)
                                                }
                                            }
                                        } else {
                                            session.pendingLightingState = proposedState
                                            session.consecutiveLightingStateMatches = 1
                                        }
                                    }

                                    // 3. APPLY POLICIES (Screen Brightness & Camera Exposure)
                                    withContext(Dispatchers.Main) {
                                        if (RecognitionPolicyManager.shouldIncreaseScreenBrightness(session.lightingState)) {
                                            if (!session.isScreenBrightened) {
                                                val attrs = window.attributes
                                                session.originalScreenBrightness = attrs.screenBrightness
                                                if (attrs.screenBrightness != 1.0f) {
                                                    attrs.screenBrightness = 1.0f
                                                    window.attributes = attrs
                                                }
                                                session.isScreenBrightened = true
                                            }
                                        }
                                    }

                                    capabilityManager?.let { cap ->
                                        val targetExposure = if (session.isScreenBrightened) 0 else RecognitionPolicyManager.calculateTargetExposureIndex(session.lightingState, cap)
                                        if (RecognitionPolicyManager.shouldUpdateExposure(targetExposure, session.currentExposureIndex, session.lastExposureUpdateTime)) {
                                            camera?.cameraControl?.setExposureCompensationIndex(targetExposure)
                                            session.currentExposureIndex = targetExposure
                                            session.lastExposureUpdateTime = SystemClock.elapsedRealtime()
                                        }
                                    }

                                    // 4. FACE_SEARCHING & RECOGNIZING
                                    if (authState != AuthenticationState.GUIDANCE) {
                                        authState = AuthenticationState.FACE_SEARCHING
                                    }
                                    val result = currentEngine.analyzeFrame(imageProxy, session.lightingState)
                                    
                                    if (result.faceLuminance != null) {
                                        session.hasDetectedFace = true
                                        session.updateEma(result.faceLuminance!!)
                                    }
                                    
                                    if (isUnlocked || isScreenLocked || context.cleanedUp.get()) return@launch

                                    when (result) {
                                        is VerificationResult.Success -> {
                                            authState = AuthenticationState.RECOGNIZING
                                            addLog("[AI] Face detected.")
                                            
                                            val matchResult = currentEngine.matchAgainstCache(result.embedding)

                                            if (matchResult != null) {
                                                val score = matchResult.second
                                                val profile = matchResult.first

                                                val needsSecondInference = RecognitionPolicyManager.shouldRunSecondInference(
                                                    session.lightingState, score, session.secondInferenceConsumed, true
                                                )

                                                if (needsSecondInference) {
                                                    authState = AuthenticationState.SECOND_INFERENCE
                                                    session.secondInferenceConsumed = true
                                                    pendingBestScore = score
                                                    pendingBestProfile = profile
                                                    addLog("[AI] Borderline score ($score). Triggering second inference.")
                                                    return@launch
                                                }

                                                // Final Decision
                                                val finalScore = maxOf(score, pendingBestScore)
                                                val finalProfile = if (finalScore == pendingBestScore && pendingBestProfile != null) pendingBestProfile else profile

                                                if (finalScore >= FaceRecognitionConfig.MATCH_THRESHOLD) {
                                                    authState = AuthenticationState.SUCCESS
                                                    addLog("[AI] Match! Score: $finalScore. Unlocking.")
                                                    isUnlocked = true
                                                    
                                                    withContext(Dispatchers.Main) {
                                                        com.ai.guardian.services.GuardianAccessibilityService.reportSuccessfulAuthentication()
                                                        com.ai.guardian.services.GuardianAccessibilityService.whitelistPackage(packageName)
                                                        val whitelistIntent = Intent(context, GuardianForegroundService::class.java).apply {
                                                            action = GuardianForegroundService.ACTION_WHITELIST_PACKAGE
                                                            putExtra(GuardianForegroundService.EXTRA_PACKAGE_NAME, packageName)
                                                        }
                                                        ContextCompat.startForegroundService(context, whitelistIntent)
                                                        cleanupAndSecure(shouldLock = false, logReason = "Unlocked")
                                                    }
                                                } else {
                                                    authState = AuthenticationState.FAILED
                                                    cleanupAndSecure(shouldLock = true, logReason = "Match failed on second inference", logType = "UNAUTHORIZED_ACCESS")
                                                }
                                            } else {
                                                // Handle no match but we might have a pending score from first inference that is borderline?
                                                // If we had a pending score but this frame gives no match at all, we should still evaluate the pending score.
                                                if (pendingBestScore >= FaceRecognitionConfig.MATCH_THRESHOLD) {
                                                    // This case shouldn't happen because pending score is by definition borderline (so it could be just below threshold, or just above).
                                                    // If pending score was above threshold but within margin, we could use it.
                                                }
                                                authState = AuthenticationState.FAILED
                                                cleanupAndSecure(shouldLock = true, logReason = "Unknown face detected", logType = "UNAUTHORIZED_ACCESS")
                                            }
                                        }
                                        is VerificationResult.MultipleFaces -> {
                                            session.timeoutJob?.cancel()
                                            addLog("Only one face should be visible.")
                                        }
                                        is VerificationResult.PoorQuality -> {
                                            resetTimeout(session.lightingState)
                                            val msg = when (result.quality) {
                                                com.ai.guardian.ai.FaceQuality.TOO_FAR -> "Move closer"
                                                com.ai.guardian.ai.FaceQuality.TOO_CLOSE -> "Move back"
                                                com.ai.guardian.ai.FaceQuality.NOT_STRAIGHT -> "Straighten your head"
                                                com.ai.guardian.ai.FaceQuality.EYES_CLOSED -> "Open eyes"
                                                com.ai.guardian.ai.FaceQuality.INVALID_AREA -> "Invalid image area"
                                                else -> "Poor quality"
                                            }
                                            addLog("Align Face: $msg")
                                        }
                                        is VerificationResult.NoFace -> {
                                            // Passive scanning
                                        }
                                        is VerificationResult.Guidance -> {
                                            if (guidanceText != result.message) {
                                                guidanceText = result.message
                                            }
                                            if (authState != AuthenticationState.GUIDANCE) {
                                                authState = AuthenticationState.GUIDANCE
                                            }
                                        }
                                        is VerificationResult.Error -> {
                                            android.util.Log.e("GuardianAI_Debug", "[Lock] Analyzer error: ${result.reason}")
                                        }
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("GuardianAI_Debug", "[Lock] Error during frame analysis", e)
                                    cleanupAndSecure(shouldLock = true, logReason = "Fatal analyzer error")
                                } finally {
                                    imageProxy.close()
                                    context.isProcessingRef.set(false)
                                }
                            }
                        }

                        cameraProvider.unbindAll()
                        context.camera = cameraProvider.bindToLifecycle(context, CameraSelector.DEFAULT_FRONT_CAMERA, imageAnalysis)
                        context.isCameraBound = true
                        capabilityManager = CameraCapabilityManager(context.camera!!)
                        
                        android.util.Log.d("GuardianAI_Debug", "[Camera] bindToLifecycle() SUCCESS")
                    } catch (e: Exception) {
                        addLog("[System] Camera error: ${e.message}")
                        cleanupAndSecure(shouldLock = true, logReason = "Camera initialization failed")
                    }
                }, ContextCompat.getMainExecutor(context))
            } else {
                cleanupAndSecure(shouldLock = true, logReason = "Camera permission missing")
            }
        }

        // Intruder block overlay
        if (showIntruderBlock) {
            Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Text("Access Denied", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("This app is protected.", color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        } else if (!showLockScreenOverlay) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                // Completely transparent UI. Authentication runs silently in the background.
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .pointerInput(Unit) {
                                detectTapGestures(onLongPress = { showDebugLogs = !showDebugLogs })
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Secured",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        "Guardian AI",
                        color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold, lineHeight = 28.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        if (authState == AuthenticationState.GUIDANCE && guidanceText.isNotEmpty()) guidanceText 
                        else if (session.lightingState == LightingState.VERY_DARK) "Lighting Too Dark" 
                        else "Verifying identity...",
                        color = if (authState == AuthenticationState.GUIDANCE || session.lightingState == LightingState.VERY_DARK) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, 
                        fontSize = 14.sp, lineHeight = 20.sp
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    LinearProgressIndicator(
                        color      = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        modifier   = Modifier
                            .width(100.dp)
                            .height(3.dp)
                            .clip(CircleShape)
                    )
                }

                if (showDebugLogs) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
                            .padding(24.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Security Engine", color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(
                                    "Close",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                        .clickable { showDebugLogs = false }
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("State: $authState", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            Text("Lighting: ${session.lightingState}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            Text("Exposure: ${session.currentExposureIndex}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            debugLogs.forEach { log ->
                                Text(log, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        performCleanup(shouldLock = false, logReason = "Back pressed")
        finish()
    }
}
