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
import kotlin.math.sqrt

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
            engine?.resetWarmup() // Reset state for the next session
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
            engine?.resetWarmup()
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
                        android.util.Log.d("GuardianAI_Phase1", "[Init] CameraX ProcessCameraProvider ready.")
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setTargetResolution(android.util.Size(640, 480))
                            .build()

                        imageAnalysis.setAnalyzer(cameraExecutor!!) { imageProxy: ImageProxy ->
                            android.util.Log.d("GuardianAI_Phase2", "[Camera] ImageAnalysis received frame. Thread=${java.lang.Thread.currentThread().name}, Timestamp=${imageProxy.imageInfo.timestamp}, Rotation=${imageProxy.imageInfo.rotationDegrees}")
                            if (context.isProcessingRef.getAndSet(true) || !hasProfiles || isUnlocked || isScreenLocked || context.cleanedUp.get()) {
                                android.util.Log.d("GuardianAI_Phase2", "[Camera] Frame DROPPED (Throttling). ImageProxy close()")
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
                                    // Guard: only enter Main thread context when a change is actually needed.
                                    // Previously withContext(Dispatchers.Main) ran every frame even when already brightened.
                                    if (RecognitionPolicyManager.shouldIncreaseScreenBrightness(session.lightingState) && !session.isScreenBrightened) {
                                        withContext(Dispatchers.Main) {
                                            val attrs = window.attributes
                                            session.originalScreenBrightness = attrs.screenBrightness
                                            if (attrs.screenBrightness != 1.0f) {
                                                attrs.screenBrightness = 1.0f
                                                window.attributes = attrs
                                            }
                                            session.isScreenBrightened = true
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
                                            session.inferenceCount++
                                            authState = AuthenticationState.RECOGNIZING
                                            
                                            val matchResult = currentEngine.matchAgainstCache(result.embedding)

                                            if (matchResult != null) {
                                                val score = matchResult.second
                                                val profile = matchResult.first

                                                // Fast path: high-confidence match — unlock immediately without buffering.
                                                // Avoids any wait on bright-light or perfectly aligned frames.
                                                if (score >= FaceRecognitionConfig.MATCH_THRESHOLD + FaceRecognitionConfig.EMBEDDING_FAST_UNLOCK_MARGIN) {
                                                    session.embeddingBuffer.clear()
                                                    authState = AuthenticationState.SUCCESS
                                                    val duration = SystemClock.elapsedRealtime() - session.sessionStartTime
                                                    addLog("[DEBUG_REPORT] ===============================")
                                                    addLog("[DEBUG_REPORT] Auth Duration: ${duration}ms")
                                                    addLog("[DEBUG_REPORT] Inferences: ${session.inferenceCount} | Accepted: ${session.inferenceCount}")
                                                    addLog("[DEBUG_REPORT] Qual: ${result.qualityScore} | Blur: ${result.blurScore} | Luma: ${result.faceLuminance}")
                                                    addLog("[DEBUG_REPORT] Similarity Score: $score (Fast)")
                                                    addLog("[DEBUG_REPORT] ===============================")
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
                                                    return@launch
                                                }

                                                // Borderline match: buffer embedding and wait for a 2nd frame.
                                                // Averaging 2 embeddings cancels zero-mean sensor noise, improving
                                                // cosine similarity stability in low-light without security loss.
                                                session.embeddingBuffer.add(result.embedding)
                                                if (session.embeddingBuffer.size < FaceRecognitionConfig.EMBEDDING_RING_BUFFER_SIZE) {
                                                    authState = AuthenticationState.SECOND_INFERENCE
                                                    return@launch
                                                }

                                                // Buffer full: compute averaged embedding and re-match.
                                                val averaged = averageAndNormalize(session.embeddingBuffer.toList())
                                                session.embeddingBuffer.clear()
                                                val avgMatch = currentEngine.matchAgainstCache(averaged)

                                                if (avgMatch != null && avgMatch.second >= FaceRecognitionConfig.MATCH_THRESHOLD) {
                                                    authState = AuthenticationState.SUCCESS
                                                    val duration = SystemClock.elapsedRealtime() - session.sessionStartTime
                                                    addLog("[DEBUG_REPORT] ===============================")
                                                    addLog("[DEBUG_REPORT] Auth Duration: ${duration}ms")
                                                    addLog("[DEBUG_REPORT] Inferences: ${session.inferenceCount} | Accepted: ${session.inferenceCount}")
                                                    addLog("[DEBUG_REPORT] Qual: ${result.qualityScore} | Blur: ${result.blurScore} | Luma: ${result.faceLuminance}")
                                                    addLog("[DEBUG_REPORT] Similarity Score: ${avgMatch.second} (Avg)")
                                                    addLog("[DEBUG_REPORT] ===============================")
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
                                                    addLog("[AI] Match failed after embedding average. Retrying...")
                                                }
                                            } else {
                                                // No match: clear buffer and reject.
                                                session.embeddingBuffer.clear()
                                                addLog("[AI] Unknown face detected. Retrying...")
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
                                            // Hysteresis: only update guidance text if at least GUIDANCE_UPDATE_MIN_MS
                                            // has elapsed since the last change. Prevents rapid flicker between
                                            // messages when Guidance results arrive at camera frame rate.
                                            val now = System.currentTimeMillis()
                                            if (now - session.lastGuidanceUpdateTime >= FaceRecognitionConfig.GUIDANCE_UPDATE_MIN_MS) {
                                                if (guidanceText != result.message) {
                                                    guidanceText = result.message
                                                }
                                                session.lastGuidanceUpdateTime = now
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
                                    android.util.Log.d("GuardianAI_Phase2", "[Camera] Executor finished block. ImageProxy close()")
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

    /**
     * Computes the element-wise arithmetic mean of a list of embeddings and L2-normalizes the result.
     * Used by the 2-frame ring buffer to cancel zero-mean sensor noise in low-light frames.
     * Cost: O(N × D) float additions where N=2 frames and D=192 dimensions = 384 ops. Negligible.
     */
    private fun averageAndNormalize(embeddings: List<FloatArray>): FloatArray {
        if (embeddings.isEmpty()) return FloatArray(192)
        val dim = embeddings[0].size
        val avg = FloatArray(dim)
        for (emb in embeddings) {
            for (i in emb.indices) {
                avg[i] += emb[i]
            }
        }
        val n = embeddings.size.toFloat()
        for (i in avg.indices) {
            avg[i] /= n
        }
        // L2 normalization: ensures the averaged vector rests on the unit hypersphere
        // for correct cosine similarity comparison against the stored template.
        var sum = 0f
        for (v in avg) sum += v * v
        val norm = sqrt(sum)
        if (norm > 0f) {
            for (i in avg.indices) avg[i] /= norm
        }
        return avg
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

