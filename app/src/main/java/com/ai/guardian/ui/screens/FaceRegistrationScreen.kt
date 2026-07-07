package com.ai.guardian.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.ai.guardian.ai.FaceBiometricEngine
import com.ai.guardian.ai.FaceRecognitionConfig
import com.ai.guardian.ai.VerificationResult
import com.ai.guardian.R
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale

import com.ai.guardian.ui.theme.*
import com.ai.guardian.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

// ─── Pose instruction data ────────────────────────────────────────────────────

private data class PoseGuide(val stepLabel: String, val poseName: String, val detail: String, val imageResId: Int?)

private fun poseGuideFor(step: Int) = when (step) {
    0    -> PoseGuide("Step 1 of 5", "Look Straight", "Face the camera directly. Hold your phone steady.", R.drawable.img_pose_front)
    1    -> PoseGuide("Step 2 of 5", "Turn Left",     "Turn your face left about 30–45°. Hold still.", R.drawable.img_pose_left)
    2    -> PoseGuide("Step 3 of 5", "Turn Right",    "Turn your face right about 30–45°. Hold still.", R.drawable.img_pose_right)
    3    -> PoseGuide("Step 4 of 5", "Look Up",       "Tilt your head up slightly.", R.drawable.img_pose_top)
    4    -> PoseGuide("Step 5 of 5", "Look Down",     "Tilt your head down slightly.", R.drawable.img_pose_bottom)
    5    -> PoseGuide("Verifying",   "Look Straight", "Look straight at the camera to confirm.", R.drawable.img_pose_front)
    6    -> PoseGuide("Saving",      "Please Wait",   "Your biometric profile is being saved securely.", null)
    else -> PoseGuide("Timed Out",   "Try Again",     "The pose timed out. Tap Retry to start over.", null)
}

// ─── Root screen ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun FaceRegistrationScreen(
    viewModel: MainViewModel,
    onRegistrationSuccess: () -> Unit,
    onBack: () -> Unit
) {
    var currentStep      by remember { mutableIntStateOf(1) }
    var registeringName  by remember { mutableStateOf("") }
    var showCancelDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (viewModel.reenrollProfileId != null) {
            registeringName = viewModel.reenrollProfileName ?: "Reenrolled Profile"
            currentStep = 2
        }
    }

    // Intercept system back gesture during capture so user gets the dialog
    BackHandler(enabled = currentStep == 2) { showCancelDialog = true }

    // ── Cancel confirmation dialog ────────────────────────────────────────────
    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            containerColor   = MaterialTheme.colorScheme.surfaceVariant,
            shape            = RoundedCornerShape(20.dp),
            title = {
                Text(
                    "Cancel Face Enrollment?",
                    color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp
                )
            },
            text = {
                Text(
                    "Your current progress will be lost.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { showCancelDialog = false },
                    shape   = RoundedCornerShape(10.dp),
                    colors  = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Continue Enrollment", color = MaterialTheme.colorScheme.background, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false; onBack() }) {
                    Text("Exit", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Top bar ───────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (currentStep != 3) {
                IconButton(
                    onClick = {
                        when (currentStep) {
                            1 -> onBack()
                            2 -> showCancelDialog = true
                        }
                    }
                ) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(22.dp)
                    )
                }
            } else {
                Spacer(Modifier.width(48.dp))
            }
            Text(
                text = when (currentStep) {
                    1    -> "Create Profile"
                    2    -> "Scan Your Face"
                    else -> "Enrollment Complete"
                },
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground, lineHeight = 26.sp,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(48.dp))
        }



        // ── Content ───────────────────────────────────────────────────────────
        AnimatedContent(
            targetState  = currentStep,
            transitionSpec = {
                fadeIn(animationSpec = tween(250)) togetherWith fadeOut(animationSpec = tween(250))
            },
            modifier = Modifier.fillMaxSize()
        ) { step ->
            when (step) {
                1 -> ProfileNameInputStep(
                    onNext      = { name -> registeringName = name; currentStep = 2 },
                    onCancel    = onBack,
                    initialName = registeringName
                )
                2 -> CaptureStep(
                    viewModel         = viewModel,
                    registeringName   = registeringName,
                    onCaptureSuccess  = { currentStep = 3 },
                    onCancelRequested = { showCancelDialog = true }
                )
                3 -> SuccessStep(onFinish = onRegistrationSuccess)
            }
        }
    }
}

// ─── Step 1: Name input ───────────────────────────────────────────────────────

@Composable
fun ProfileNameInputStep(
    onNext:      (String) -> Unit,
    onCancel:    () -> Unit,
    initialName: String
) {
    var nameText by remember { mutableStateOf(initialName) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Who are you enrolling?",
            fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground, lineHeight = 30.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Enter a name for this face profile.",
            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp
        )

        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value         = nameText,
            onValueChange = { nameText = it },
            label         = { Text("Name  (e.g. Kuldeep, Dad, Mom)") },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth(),
            shape         = RoundedCornerShape(10.dp),
            colors        = OutlinedTextFieldDefaults.colors(
                focusedTextColor        = MaterialTheme.colorScheme.onBackground,
                unfocusedTextColor      = MaterialTheme.colorScheme.onBackground,
                focusedContainerColor   = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor      = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor    = MaterialTheme.colorScheme.outlineVariant
            )
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick  = { if (nameText.trim().isNotEmpty()) onNext(nameText.trim()) },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape    = RoundedCornerShape(10.dp),
            colors   = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            enabled  = nameText.trim().isNotEmpty()
        ) {
            Text("Continue", color = MaterialTheme.colorScheme.background, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ─── Step 2: Camera capture ───────────────────────────────────────────────────

@Composable
fun CaptureStep(
    viewModel:         MainViewModel,
    registeringName:   String,
    onCaptureSuccess:  () -> Unit,
    onCancelRequested: () -> Unit
) {
    val context        = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        isGranted -> hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) { if (!hasCameraPermission) launcher.launch(Manifest.permission.CAMERA) }

    // ── Recognition state — logic UNCHANGED from original ─────────────────────
    var currentPoseStep         by remember { mutableIntStateOf(0) }
    var scanStatus              by remember { mutableStateOf("Align your face inside the circle") }
    var isCooldown              by remember { mutableStateOf(false) }
    var isStable                by remember { mutableStateOf(false) }
    var hasDetectedFace         by remember { mutableStateOf(false) }
    var isLightingGood          by remember { mutableStateOf(true) }
    var isDistanceGood          by remember { mutableStateOf(true) }
    var consecutiveStableFrames by remember { mutableIntStateOf(0) }
    val enrolledTemplates       = remember { mutableStateListOf<FloatArray>() }
    val currentEmbeddings       = remember { mutableStateListOf<FloatArray>() }
    var lastEulerY              by remember { mutableStateOf<Float?>(null) }
    var lastEulerX              by remember { mutableStateOf<Float?>(null) }
    var lastEulerZ              by remember { mutableStateOf<Float?>(null) }
    var poseTimerSeconds        by remember { mutableIntStateOf(30) }
    var timerJob                by remember { mutableStateOf<Job?>(null) }
    var engine                  by remember { mutableStateOf<FaceBiometricEngine?>(null) }
    val cameraExecutor          = remember { Executors.newSingleThreadExecutor() }
    val isProcessingRef         = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var emaLuminance            by remember { mutableFloatStateOf(-1f) }
    var currentLightingState    by remember { mutableStateOf(com.ai.guardian.ai.LightingState.NORMAL) }

    // ── UI-only state (does NOT affect recognition logic) ─────────────────────
    var faceDetected by remember { mutableStateOf(false) }
    var faceRatio    by remember { mutableStateOf(0f) }

    fun resetPoseTimer() {
        timerJob?.cancel()
        poseTimerSeconds = 30
        timerJob = coroutineScope.launch {
            while (poseTimerSeconds > 0) { 
                delay(1000)
                if (!faceDetected) {
                    poseTimerSeconds-- 
                }
            }
            currentPoseStep = 7
            scanStatus = "Timed out. Please keep your face aligned and steady."
        }
    }

    LaunchedEffect(currentPoseStep) {
        if (currentPoseStep < 5) { resetPoseTimer(); consecutiveStableFrames = 0 }
        else timerJob?.cancel()
    }

    LaunchedEffect(Unit) { engine = FaceBiometricEngine(context) }

    DisposableEffect(Unit) { onDispose { timerJob?.cancel(); engine?.close(); cameraExecutor.shutdown() } }

    // ── Layout ────────────────────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Pose strip removed in favor of permanent instructions above the camera

            val guide = poseGuideFor(currentPoseStep)
            Surface(
                shape    = RoundedCornerShape(12.dp),
                color    = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(guide.stepLabel, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(guide.poseName, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, lineHeight = 26.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(guide.detail,   fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
                    }
                    if (guide.imageResId != null) {
                        Spacer(Modifier.width(12.dp))
                        Image(
                            painter = painterResource(id = guide.imageResId),
                            contentDescription = guide.poseName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

        // ── Camera / timeout content ──────────────────────────────────────────
        if (currentPoseStep == 7) {
            // Timeout state
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(56.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text("Pose Timed Out", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground, lineHeight = 26.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Keep your face aligned and steady, then retry.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    enrolledTemplates.clear(); currentEmbeddings.clear()
                    lastEulerY = null; lastEulerX = null; lastEulerZ = null
                    consecutiveStableFrames = 0; faceDetected = false
                    currentPoseStep = 0
                    scanStatus = "Look straight at the camera (FRONT)"
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape    = RoundedCornerShape(10.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Retry Enrollment", color = MaterialTheme.colorScheme.background, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onCancelRequested) {
                Text("Exit", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(16.dp))

        } else if (hasCameraPermission) {
            val outlineColor = when {
                currentPoseStep == 5 -> MaterialTheme.colorScheme.tertiary
                currentPoseStep == 6 -> MaterialTheme.colorScheme.tertiary
                !isDistanceGood      -> MaterialTheme.colorScheme.error
                consecutiveStableFrames > 0 -> MaterialTheme.colorScheme.tertiary.copy(alpha = (0.5f + consecutiveStableFrames * 0.12f).coerceAtMost(1f))
                else -> MaterialTheme.colorScheme.primary
            }

            Box(
                modifier = Modifier
                    .size(240.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(width = 3.dp, color = outlineColor, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (currentPoseStep == 6) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(52.dp), strokeWidth = 3.dp)
                } else {
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }

                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .setTargetResolution(android.util.Size(640, 480))
                                    .build()

                                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                    if (isProcessingRef.getAndSet(true) || isCooldown || currentPoseStep >= 6 || engine == null) {
                                        imageProxy.close(); isProcessingRef.set(false); return@setAnalyzer
                                    }

                                    // Check Lighting Before ML Kit (Stage 1)
                                    if (!hasDetectedFace) {
                                        val luminance = com.ai.guardian.ai.BrightnessEstimator.estimateLuminance(imageProxy)
                                        val currentEma = if (emaLuminance < 0f) luminance.toFloat() else {
                                            com.ai.guardian.ai.FaceRecognitionConfig.EMA_ALPHA * luminance + (1f - com.ai.guardian.ai.FaceRecognitionConfig.EMA_ALPHA) * emaLuminance
                                        }
                                        emaLuminance = currentEma
                                    }
                                    val lightingState = com.ai.guardian.ai.RecognitionPolicyManager.determineLightingState(emaLuminance)
                                    currentLightingState = lightingState
                                    val canEnroll = com.ai.guardian.ai.RecognitionPolicyManager.allowEnrollmentCapture(lightingState)

                                    if (!canEnroll) {
                                        isLightingGood = false
                                        scanStatus = "Lighting is Too Dark"
                                        consecutiveStableFrames = 0
                                        imageProxy.close()
                                        isProcessingRef.set(false)
                                        return@setAnalyzer
                                    }

                                    coroutineScope.launch {
                                        try {
                                            val result = engine?.analyzeFrame(imageProxy, currentLightingState)
                                            
                                            if (result?.faceLuminance != null) {
                                                hasDetectedFace = true
                                                val luma = result.faceLuminance!!
                                                emaLuminance = if (emaLuminance < 0f) luma.toFloat() else {
                                                    com.ai.guardian.ai.FaceRecognitionConfig.EMA_ALPHA * luma + (1f - com.ai.guardian.ai.FaceRecognitionConfig.EMA_ALPHA) * emaLuminance
                                                }
                                            }
                                            
                                            if (result is VerificationResult.Success) {
                                                val face   = result.mlkitFace
                                                val angleY = face.headEulerAngleY
                                                val diffY  = lastEulerY?.let { Math.abs(angleY - it) } ?: 0f
                                                val diffX  = lastEulerX?.let { Math.abs(face.headEulerAngleX - it) } ?: 0f
                                                val diffZ  = lastEulerZ?.let { Math.abs(face.headEulerAngleZ - it) } ?: 0f

                                                lastEulerY = angleY
                                                lastEulerX = face.headEulerAngleX
                                                lastEulerZ = face.headEulerAngleZ

                                                val width     = imageProxy.width
                                                val faceWidth = face.boundingBox.width()
                                                val ratio     = faceWidth.toFloat() / width.toFloat()

                                                // Update UI display state
                                                faceDetected   = true
                                                faceRatio      = ratio
                                                isDistanceGood = ratio >= 0.25f && ratio <= 0.65f
                                                isLightingGood = true

                                                if (currentPoseStep < 5) {
                                                    val isPoseCorrect = when (currentPoseStep) {
                                                        0 -> Math.abs(angleY) <= 12f && Math.abs(face.headEulerAngleX) <= 12f
                                                        1 -> angleY <= -15f && angleY >= -45f
                                                        2 -> angleY >= 15f  && angleY <= 45f
                                                        3 -> face.headEulerAngleX >= 12f // Looking up
                                                        4 -> face.headEulerAngleX <= -12f // Looking down
                                                        else -> false
                                                    }

                                                    if (isPoseCorrect && isDistanceGood) {
                                                        isStable = diffY <= FaceRecognitionConfig.POSE_STABILITY_THRESHOLD &&
                                                                   diffX <= FaceRecognitionConfig.POSE_STABILITY_THRESHOLD &&
                                                                   diffZ <= FaceRecognitionConfig.POSE_STABILITY_THRESHOLD

                                                        if (isStable) {
                                                            consecutiveStableFrames++
                                                            scanStatus = "Holding..."
                                                        } else {
                                                            consecutiveStableFrames = 0
                                                            scanStatus = "Keep your head steady"
                                                        }

                                                        if (consecutiveStableFrames >= 4) {
                                                            consecutiveStableFrames = 0

                                                            var isTooSimilar = false
                                                            for (existing in currentEmbeddings) {
                                                                val similarity = FaceBiometricEngine.calculateCosineSimilarity(result.embedding, existing)
                                                                if (similarity > 0.995f) { isTooSimilar = true; break }
                                                            }

                                                            if (isTooSimilar) {
                                                                scanStatus = "Move head slightly to register more angles"
                                                            } else {
                                                                currentEmbeddings.add(result.embedding)
                                                                scanStatus = "Captured ${currentEmbeddings.size} of 5"

                                                                if (currentEmbeddings.size >= 5) {
                                                                    isCooldown = true
                                                                    enrolledTemplates.addAll(currentEmbeddings)
                                                                    currentEmbeddings.clear()

                                                                    if (currentPoseStep < 4) {
                                                                        scanStatus = "Step completed!"
                                                                        delay(1800)
                                                                        currentPoseStep++
                                                                        isCooldown = false
                                                                    } else {
                                                                        scanStatus = "All poses captured! Verifying..."
                                                                        delay(1800)
                                                                        currentPoseStep = 5
                                                                        isCooldown = false
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    } else {
                                                        consecutiveStableFrames = 0
                                                        scanStatus = when {
                                                            ratio < 0.25f        -> "Move closer to the camera"
                                                            ratio > 0.65f        -> "Move slightly further away"
                                                            currentPoseStep == 0 -> "Look straight at the camera"
                                                            currentPoseStep == 1 -> "Turn your head slightly LEFT"
                                                            currentPoseStep == 2 -> "Turn your head slightly RIGHT"
                                                            currentPoseStep == 3 -> "Tilt your head slightly UP"
                                                            currentPoseStep == 4 -> "Tilt your head slightly DOWN"
                                                            else -> ""
                                                        }
                                                    }
                                                } else if (currentPoseStep == 5) {
                                                    var matched = false
                                                    for (tempTemplate in enrolledTemplates) {
                                                        val similarity = FaceBiometricEngine.calculateCosineSimilarity(result.embedding, tempTemplate)
                                                        if (similarity > FaceRecognitionConfig.MATCH_THRESHOLD) { matched = true; break }
                                                    }

                                                    if (matched) {
                                                        isCooldown = true
                                                        scanStatus = "Verification success! Saving..."
                                                        currentPoseStep = 6

                                                        val db            = com.ai.guardian.data.AppDatabase.getDatabase(context)
                                                        val dao           = db.faceDao()
                                                        val templatesCopy = enrolledTemplates.toList() // Should be 25 templates

                                                        // Convert templates to FaceTemplateEntity
                                                        val templateEntities = templatesCopy.map {
                                                            val byteBuf = java.nio.ByteBuffer.allocate(it.size * 4)
                                                            it.forEach { floatVal -> byteBuf.putFloat(floatVal) }
                                                            com.ai.guardian.data.entity.FaceTemplateEntity(
                                                                profileId = 0, // to be updated
                                                                embeddingData = byteBuf.array()
                                                            )
                                                        }

                                                        withContext(Dispatchers.IO) {
                                                            db.withTransaction {
                                                                val reenrollId = viewModel.reenrollProfileId
                                                                val profileId = if (reenrollId != null) {
                                                                    dao.deleteProfileById(reenrollId)
                                                                    dao.insertProfile(com.ai.guardian.data.entity.FaceProfileEntity(
                                                                        id               = reenrollId,
                                                                        name             = registeringName,
                                                                        registrationDate = System.currentTimeMillis()
                                                                    ))
                                                                } else {
                                                                    dao.insertProfile(com.ai.guardian.data.entity.FaceProfileEntity(
                                                                        name             = registeringName,
                                                                        registrationDate = System.currentTimeMillis()
                                                                    ))
                                                                }

                                                                dao.insertTemplates(templateEntities.map { it.copy(profileId = profileId) })
                                                            }
                                                        }
                                                        viewModel.reenrollProfileId   = null
                                                        viewModel.reenrollProfileName = null
                                                        delay(1000)
                                                        onCaptureSuccess()
                                                    } else {
                                                        scanStatus = "Verification failed. Please align your face."
                                                    }
                                                }
                                            } else if (result is VerificationResult.PoorQuality) {
                                                faceDetected = true
                                                scanStatus = when (result.quality) {
                                                    com.ai.guardian.ai.FaceQuality.TOO_FAR -> "Move closer to the camera"
                                                    com.ai.guardian.ai.FaceQuality.TOO_CLOSE -> "Move slightly further away"
                                                    com.ai.guardian.ai.FaceQuality.NOT_STRAIGHT -> "Straighten your head"
                                                    com.ai.guardian.ai.FaceQuality.EYES_CLOSED -> "Open your eyes"
                                                    com.ai.guardian.ai.FaceQuality.INVALID_AREA -> "Adjust your position"
                                                    else -> "Poor quality. Improve lighting or alignment."
                                                }
                                            } else if (result is VerificationResult.MultipleFaces) {
                                                faceDetected = false
                                                scanStatus   = "Only one face should be visible."
                                            } else if (result is VerificationResult.NoFace) {
                                                faceDetected = false
                                                scanStatus   = "Align face in preview circle."
                                            }
                                        } catch (e: Exception) {
                                            scanStatus = "Error. Please try again."
                                        } finally {
                                            imageProxy.close()
                                            isProcessingRef.set(false)
                                        }
                                    }
                                }

                                try {
                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA,
                                        preview, imageAnalysis
                                    )
                                } catch (e: Exception) { e.printStackTrace() }

                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Capture progress counter
            if (currentPoseStep < 5) {
                Text(
                    "Captured  ${currentEmbeddings.size} / 5",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground, lineHeight = 22.sp
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress   = currentEmbeddings.size / 5f,
                    modifier   = Modifier
                        .fillMaxWidth(0.5f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color      = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant
                )
                Spacer(Modifier.height(12.dp))

                // Quality checklist — user-friendly labels only
                Surface(
                    shape    = RoundedCornerShape(12.dp),
                    color    = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        QualityIndicatorRow(
                            label = if (faceDetected) "Face Detected" else "No Face Detected",
                            isMet = faceDetected
                        )
                        Spacer(Modifier.height(6.dp))
                        QualityIndicatorRow(
                            label = when {
                                !isDistanceGood && faceRatio < 0.25f -> "Move Closer"
                                !isDistanceGood && faceRatio > 0.65f -> "Move Further Away"
                                else -> "Good Distance"
                            },
                            isMet = isDistanceGood
                        )
                        Spacer(Modifier.height(6.dp))
                        QualityIndicatorRow(
                            label = if (consecutiveStableFrames > 0) "Hold Still" else "Head Moving",
                            isMet = consecutiveStableFrames > 0
                        )
                        Spacer(Modifier.height(6.dp))
                        QualityIndicatorRow(
                            label = if (isLightingGood) "Good Lighting" else "Improve Lighting",
                            isMet = isLightingGood
                        )
                    }
                }
            } else if (currentPoseStep == 5) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Verifying your identity...",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.weight(1f))

            TextButton(
                onClick  = onCancelRequested,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Text("Cancel Enrollment", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }

        } else {
            // No camera permission state
            Spacer(Modifier.height(40.dp))
            Text(
                "Camera permission is required to scan your face.",
                color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, lineHeight = 20.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ─── Step 3: Success ──────────────────────────────────────────────────────────

@Composable
fun SuccessStep(onFinish: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Enrollment Complete",
            fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground, lineHeight = 32.sp
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Your face profile has been registered with 25 multi-angle templates and is ready to use.",
            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 22.sp,
            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick  = onFinish,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape    = RoundedCornerShape(10.dp),
            colors   = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("Done", color = MaterialTheme.colorScheme.background, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ─── Shared composables ───────────────────────────────────────────────────────

@Composable
fun QualityIndicatorRow(label: String, isMet: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier          = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector        = if (isMet) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint               = if (isMet) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
            modifier           = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(label, color = if (isMet) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
fun StepIndicator(step: Int, isActive: Boolean) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        Text(
            step.toString(),
            color = if (isActive) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun EnrollmentStepItem(name: String, active: Boolean, completed: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(if (completed || active) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            if (completed) {
                Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.background, modifier = Modifier.size(14.dp))
            } else {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (active) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            name,
            fontSize = 11.sp, fontWeight = FontWeight.Medium,
            color = if (active || completed) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 14.sp
        )
    }
}
