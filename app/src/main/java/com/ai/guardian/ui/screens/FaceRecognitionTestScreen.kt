package com.ai.guardian.ui.screens

import android.graphics.Bitmap
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.ai.guardian.ai.FaceBiometricEngine
import com.ai.guardian.ai.FaceRecognitionConfig
import com.ai.guardian.ai.VerificationResult
import com.ai.guardian.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

@Composable
fun FaceRecognitionTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    // 0: Scanning, 1: Comparing, 2: Success, 3: Failure, 4: Camera Error
    var testSubStep by remember { mutableIntStateOf(0) }
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var similarityScore by remember { mutableFloatStateOf(0f) }
    var matchedProfileName by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("Position your face inside the circle") }
    var testTimestamp by remember { mutableLongStateOf(0L) }
    var matchTimeMs by remember { mutableLongStateOf(0L) }

    val faceProfiles = remember { mutableStateListOf<com.ai.guardian.data.entity.FaceProfileWithTemplates>() }
    var engine by remember { mutableStateOf<FaceBiometricEngine?>(null) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val isProcessingRef = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var emaLuminance by remember { mutableFloatStateOf(-1f) }
    var hasDetectedFace by remember { mutableStateOf(false) }
    var currentLightingState by remember { mutableStateOf(com.ai.guardian.ai.LightingState.NORMAL) }

    LaunchedEffect(Unit) {
        engine = FaceBiometricEngine(context)
        try {
            val dao = com.ai.guardian.data.AppDatabase.getDatabase(context).faceDao()
            val profiles = withContext(Dispatchers.IO) {
                dao.getAllProfilesWithTemplates()
            }
            faceProfiles.clear()
            faceProfiles.addAll(profiles)
            engine?.loadTemplates(profiles)
        } catch (e: Exception) {
            android.util.Log.e("GuardianAI_Debug", "[Test] Failed to load profiles", e)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            engine?.close()
            cameraExecutor.shutdown()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                text = "Recognition Test",
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground, lineHeight = 26.sp,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(48.dp))
        }

        when (testSubStep) {
            0 -> {
                // Scanning
                Spacer(Modifier.height(32.dp))
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                            cameraProviderFuture.addListener({
                                try {
                                    val cameraProvider = cameraProviderFuture.get()
                                    val preview = Preview.Builder().build().also {
                                        it.setSurfaceProvider(previewView.surfaceProvider)
                                    }

                                    val imageAnalysis = ImageAnalysis.Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .setTargetResolution(android.util.Size(640, 480))
                                        .build()

                                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                        if (isProcessingRef.getAndSet(true) || testSubStep != 0 || engine == null) {
                                            imageProxy.close()
                                            isProcessingRef.set(false)
                                            return@setAnalyzer
                                        }

                                        coroutineScope.launch {
                                            val startTime = System.currentTimeMillis()
                                            try {
                                                // Check Lighting Before ML Kit (Stage 1)
                                                if (!hasDetectedFace) {
                                                    val luminance = com.ai.guardian.ai.BrightnessEstimator.estimateLuminance(imageProxy)
                                                    val currentEma = if (emaLuminance < 0f) luminance.toFloat() else {
                                                        com.ai.guardian.ai.FaceRecognitionConfig.EMA_ALPHA * luminance + (1f - com.ai.guardian.ai.FaceRecognitionConfig.EMA_ALPHA) * emaLuminance
                                                    }
                                                    emaLuminance = currentEma
                                                }
                                                currentLightingState = com.ai.guardian.ai.RecognitionPolicyManager.determineLightingState(emaLuminance)

                                                val result = engine?.analyzeFrame(imageProxy, currentLightingState)
                                                
                                                if (result?.faceLuminance != null) {
                                                    hasDetectedFace = true
                                                    val luma = result.faceLuminance!!
                                                    emaLuminance = if (emaLuminance < 0f) luma.toFloat() else {
                                                        com.ai.guardian.ai.FaceRecognitionConfig.EMA_ALPHA * luma + (1f - com.ai.guardian.ai.FaceRecognitionConfig.EMA_ALPHA) * emaLuminance
                                                    }
                                                }
                                                
                                                if (result is VerificationResult.Success) {
                                                    val bitmap = imageProxy.toBitmap()
                                                    val rotation = imageProxy.imageInfo.rotationDegrees
                                                    val rotatedBitmap = if (rotation != 0 || true) {
                                                        val matrix = android.graphics.Matrix().apply {
                                                            if (rotation != 0) {
                                                                postRotate(rotation.toFloat())
                                                            }
                                                            postScale(-1f, 1f)
                                                        }
                                                        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                                                    } else {
                                                        bitmap
                                                    }

                                                    withContext(Dispatchers.Main) {
                                                        cameraProvider.unbindAll()
                                                        capturedBitmap = rotatedBitmap
                                                        testTimestamp = System.currentTimeMillis()
                                                        testSubStep = 1 // Start Comparing animation
                                                    }

                                                    delay(1200) // visual comparing delay
                                                    
                                                    val matchResult = engine?.matchAgainstCache(result.embedding)

                                                    val elapsed = System.currentTimeMillis() - startTime
                                                    withContext(Dispatchers.Main) {
                                                        if (matchResult != null) {
                                                            similarityScore = matchResult.second
                                                            matchTimeMs = elapsed
                                                            matchedProfileName = matchResult.first.name
                                                            testSubStep = 2 // Success
                                                        } else {
                                                            similarityScore = 0f
                                                            matchTimeMs = elapsed
                                                            testSubStep = 3 // Failure
                                                        }
                                                    }
                                                } else if (result is VerificationResult.PoorQuality) {
                                                    val msg = when (result.quality) {
                                                        com.ai.guardian.ai.FaceQuality.TOO_FAR -> "Move closer"
                                                        com.ai.guardian.ai.FaceQuality.TOO_CLOSE -> "Move back"
                                                        com.ai.guardian.ai.FaceQuality.NOT_STRAIGHT -> "Straighten your head"
                                                        com.ai.guardian.ai.FaceQuality.EYES_CLOSED -> "Open eyes"
                                                        com.ai.guardian.ai.FaceQuality.INVALID_AREA -> "Invalid image area"
                                                        else -> "Poor quality"
                                                    }
                                                    statusMessage = "Quality: $msg"
                                                } else if (result is VerificationResult.MultipleFaces) {
                                                    statusMessage = "Only one face should be visible"
                                                } else if (result is VerificationResult.NoFace) {
                                                    statusMessage = "Align your face in the circle"
                                                } else if (result is VerificationResult.Warmup) {
                                                    statusMessage = "Optimizing Camera..."
                                                }
                                            } catch (e: Exception) {
                                                statusMessage = "Error: ${e.message}"
                                            } finally {
                                                imageProxy.close()
                                                isProcessingRef.set(false)
                                            }
                                        }
                                    }

                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_FRONT_CAMERA,
                                        preview,
                                        imageAnalysis
                                    )
                                } catch (e: Exception) {
                                    testSubStep = 4
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                
                Spacer(Modifier.height(24.dp))
                Text(statusMessage, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Text("Lighting: $currentLightingState", color = if (currentLightingState == com.ai.guardian.ai.LightingState.VERY_DARK) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
            1 -> {
                // Comparing
                Spacer(Modifier.height(32.dp))
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    capturedBitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                        )
                    }

                    // Radar scan line animation
                    val infiniteTransition = rememberInfiniteTransition(label = "radar")
                    val positionY by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 260f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1200, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "positionY"
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .offset(y = positionY.dp - 130.dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }

                Spacer(Modifier.height(24.dp))
                Text("Scanning...", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            2 -> {
                // Success Result Card
                Spacer(Modifier.height(24.dp))
                
                val formattedTime = SimpleDateFormat("h:mm a · MMM d", Locale.getDefault()).format(Date(testTimestamp))
                
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Avatar
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            val initials = matchedProfileName.take(1).uppercase(Locale.getDefault())
                            Text(initials, color = MaterialTheme.colorScheme.primary, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                        }
                        
                        Spacer(Modifier.height(16.dp))
                        Text(matchedProfileName, color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(24.dp))
                        
                        ResultDetailRow(label = "Similarity", value = String.format("%.1f%%", similarityScore * 100))
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
                        ResultDetailRow(label = "Matched in", value = "${matchTimeMs}ms")
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
                        ResultDetailRow(label = "Time", value = formattedTime)
                    }
                }
                
                Spacer(Modifier.weight(1f))
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Done", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = {
                            testSubStep = 0
                            statusMessage = "Position your face inside the circle"
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Test Again", color = MaterialTheme.colorScheme.background, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            3 -> {
                // Failure
                Spacer(Modifier.height(48.dp))
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                }
                Spacer(Modifier.height(24.dp))
                Text("No Match Found", color = MaterialTheme.colorScheme.onBackground, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Text(
                    "The face does not match any registered profiles.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )

                Spacer(Modifier.weight(1f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Done", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = {
                            testSubStep = 0
                            statusMessage = "Position your face inside the circle"
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Try Again", color = MaterialTheme.colorScheme.background, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            4 -> {
                // Camera Error
                Spacer(Modifier.height(48.dp))
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                }
                Spacer(Modifier.height(24.dp))
                Text("Camera Error", color = MaterialTheme.colorScheme.onBackground, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Failed to acquire camera stream.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center
                )

                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        testSubStep = 0
                        statusMessage = "Position your face inside the circle"
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 24.dp)
                        .height(50.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Retry", color = MaterialTheme.colorScheme.background, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun ResultDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        Text(text = value, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}
