package com.ai.guardian.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.Image
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.face.Face
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt
import com.ai.guardian.data.entity.FaceProfileWithTemplates
import com.ai.guardian.data.entity.FaceTemplateEntity
import com.ai.guardian.data.entity.FaceProfileEntity

enum class FaceQuality {
    GOOD,
    INVALID_AREA,
    TOO_FAR,
    TOO_CLOSE,
    NOT_STRAIGHT,
    EYES_CLOSED,
    POOR_LUMINANCE,
    BLURRED
}

class FaceBiometricEngine(context: Context) {
    private val faceDetector = FaceDetectorHelper()
    private val mobileFaceNet = MobileFaceNet(context)
    private val isClosed = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        android.util.Log.d("GuardianAI_Phase1", "[Init] FaceBiometricEngine initialized (FaceDetector & MobileFaceNet instantiated).")
    }

    private var firstFrameTime: Long = -1L
    private var bestQualityScore = 0
    private var consecutiveMediumFrames = 0
    private var consecutiveNoFaceFrames = 0
    private var poorQualityStartTime = -1L
    private var lastInferenceTime = 0L

    fun resetWarmup() {
        firstFrameTime = -1L
        bestQualityScore = 0
        consecutiveMediumFrames = 0
        consecutiveNoFaceFrames = 0
        poorQualityStartTime = -1L
        lastInferenceTime = 0L
    }

    // Memory Cache for recognition
    private val templateCache = mutableMapOf<FaceProfileEntity, List<FloatArray>>()

    /**
     * Loads all templates for the given profiles into memory.
     * This prevents querying the database on every frame.
     */
    fun loadTemplates(profiles: List<FaceProfileWithTemplates>) {
        templateCache.clear()
        for (profileWithTemplates in profiles) {
            val floatArrays = profileWithTemplates.templates.mapNotNull { template ->
                val bytes = template.embeddingData
                if (bytes.size == 192 * 4) {
                    val buffer = java.nio.ByteBuffer.wrap(bytes)
                    val floatArr = FloatArray(192)
                    for (i in 0 until 192) {
                        floatArr[i] = buffer.float
                    }
                    floatArr
                } else null
            }
            templateCache[profileWithTemplates.profile] = floatArrays
        }
    }

    /**
     * Clears the template cache from memory.
     */
    fun clearCache() {
        templateCache.clear()
    }

    /**
     * Matches the given embedding against all cached templates across all profiles.
     * Returns a pair of the matched FaceProfileEntity and the best similarity score, or null if no match exceeds threshold.
     */
    fun matchAgainstCache(liveEmbedding: FloatArray, threshold: Float = FaceRecognitionConfig.MATCH_THRESHOLD): Pair<FaceProfileEntity, Float>? {
        var bestOverallScore = 0f
        var bestProfile: FaceProfileEntity? = null

        for ((profile, embeddings) in templateCache) {
            var bestProfileScore = 0f
            for (cachedEmbedding in embeddings) {
                val score = calculateCosineSimilarity(liveEmbedding, cachedEmbedding)
                if (score > bestProfileScore) {
                    bestProfileScore = score
                }
            }
            if (bestProfileScore > bestOverallScore) {
                bestOverallScore = bestProfileScore
                bestProfile = profile
            }
        }

        if (bestOverallScore > threshold && bestProfile != null) {
            return Pair(bestProfile, bestOverallScore)
        }
        return null
    }

    /**
     * Helper to validate quality of a detected face.
     * Returns FaceQuality.GOOD if quality checks pass, or the specific error reason.
     */
    fun checkFaceQuality(face: Face, width: Int, height: Int): FaceQuality {
        val imageArea = (width * height).toFloat()
        if (imageArea <= 0f) return FaceQuality.INVALID_AREA

        val faceArea = (face.boundingBox.width() * face.boundingBox.height()).toFloat()
        val faceRatio = faceArea / imageArea

        if (faceRatio < FaceRecognitionConfig.MIN_FACE_SIZE_RATIO) {
            return FaceQuality.TOO_FAR
        }
        if (faceRatio > FaceRecognitionConfig.MAX_FACE_SIZE_RATIO) {
            return FaceQuality.TOO_CLOSE
        }

        // Relaxed movement thresholds for natural micro-movements
        val rotY = Math.abs(face.headEulerAngleY)
        val rotX = Math.abs(face.headEulerAngleX)
        val rotZ = Math.abs(face.headEulerAngleZ)

        // Using relaxed thresholds (e.g., 30 degrees instead of strictly looking straight for general detection)
        // Pose strictness is handled in the UI for enrollment.
        if (rotZ > 35f) {
            return FaceQuality.NOT_STRAIGHT
        }

        val leftEyeOpen = face.leftEyeOpenProbability ?: 1.0f
        val rightEyeOpen = face.rightEyeOpenProbability ?: 1.0f
        if (leftEyeOpen < 0.2f || rightEyeOpen < 0.2f) {
            return FaceQuality.EYES_CLOSED
        }

        return FaceQuality.GOOD
    }

    /**
     * Analyzes a CameraX ImageProxy frame directly.
     * Implements zero-buffer throttling to only run heavy inference on the best frames.
     */
    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    suspend fun analyzeFrame(imageProxy: ImageProxy, lightingState: LightingState): VerificationResult = withContext(Dispatchers.Default) {
        try {
            val mediaImage = imageProxy.image
                ?: return@withContext VerificationResult.Error("mediaImage is null")

            if (firstFrameTime == -1L) {
                firstFrameTime = System.currentTimeMillis()
                poorQualityStartTime = -1L
            }
            val now = System.currentTimeMillis()
            // Use configurable warmup instead of hardcoded 600ms.
            // ENGINE_WARMUP_MS = 400ms — shorter on capable devices, still allows AE to settle.
            if (now - firstFrameTime < FaceRecognitionConfig.ENGINE_WARMUP_MS) {
                return@withContext VerificationResult.Warmup
            }
            
            // Step 1: 150ms Cooldown after previous inference
            if (now - lastInferenceTime < 150) {
                return@withContext VerificationResult.Warmup // Act as warmup during cooldown
            }

            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            // Step 2: Detect faces (ML Kit)
            val faces = faceDetector.detectFaces(mediaImage, rotationDegrees)
            android.util.Log.d("GuardianAI_Phase2", "[Pipeline] ML Kit FaceDetector returned ${faces.size} faces.")
            if (faces.isEmpty()) {
                consecutiveNoFaceFrames++
                if (consecutiveNoFaceFrames >= 3) {
                    bestQualityScore = 0
                }
                handlePoorQuality(now)
                if (poorQualityStartTime != -1L && now - poorQualityStartTime > 2000L) {
                    return@withContext VerificationResult.Guidance(FaceQualityScorer.GUIDANCE_NO_FACE, "Align your face in the frame")
                }
                return@withContext VerificationResult.NoFace
            }
            consecutiveNoFaceFrames = 0
            if (faces.size > 1) {
                poorQualityStartTime = -1L // Reset guidance timer
                return@withContext VerificationResult.MultipleFaces
            }

            val face = faces[0]

            // Step 3: Lightweight Quality Scoring
            val qualityResult = FaceQualityScorer.calculateQuality(imageProxy, face)
            
            // Adaptive Thresholds based on Environment Lighting and Enrollment Baseline
            // Get the minimum enrollment baseline among all enrolled users (fallback to 130 if empty)
            val baselineLuma = templateCache.keys.minOfOrNull { it.enrollmentLuminance } ?: 130
            val baselineBlur = templateCache.keys.minOfOrNull { it.enrollmentBlurScore } ?: 30
            
            val (excellentThreshold, minThreshold) = when (lightingState) {
                LightingState.EXCELLENT -> Pair(85, 65)
                LightingState.GOOD      -> Pair(80, 60)
                LightingState.NORMAL    -> Pair(75, 55)
                LightingState.DIM       -> Pair(70, 50)
                LightingState.VERY_DARK -> Pair(65, 45)
            }
            
            // If live luminance drops significantly below the enrollment baseline, we require higher overall quality
            val isDarkerThanBaseline = qualityResult.luminance < baselineLuma - 20
            val adjustedMinThreshold = if (isDarkerThanBaseline) minThreshold + 5 else minThreshold
            val adjustedExcellentThreshold = if (isDarkerThanBaseline) excellentThreshold + 5 else excellentThreshold

            if (qualityResult.score < adjustedMinThreshold) {
                handlePoorQuality(now)
                if (poorQualityStartTime != -1L && now - poorQualityStartTime > 2000L) {
                    val msg = getGuidanceMessage(qualityResult.guidancePriority)
                    return@withContext VerificationResult.Guidance(qualityResult.guidancePriority, msg, qualityResult.luminance)
                }
                return@withContext VerificationResult.PoorQuality(FaceQuality.BLURRED, qualityResult.luminance)
            }
            
            poorQualityStartTime = -1L // Quality is acceptable, reset guidance timer

            // Step 4: Zero-Buffer Throttling
            var shouldProcess = false
            if (qualityResult.score >= adjustedExcellentThreshold) {
                shouldProcess = true // Immediate bypass
            } else {
                if (qualityResult.score > bestQualityScore || consecutiveMediumFrames >= 3) {
                    shouldProcess = true // Improved score or timeout reached
                } else {
                    consecutiveMediumFrames++
                    return@withContext VerificationResult.Warmup // Pretend we are still warming up (skipping)
                }
            }
            
            if (shouldProcess) {
                bestQualityScore = qualityResult.score
                consecutiveMediumFrames = 0

                // Step 5: Run Heavy Inference
                lastInferenceTime = now
                var bitmap: Bitmap? = null
                var uprightFace: Bitmap? = null
                var croppedFace: Bitmap? = null
                try {
                    bitmap = imageProxy.toBitmap()
                    android.util.Log.d("GuardianAI_Phase2", "[Pipeline] imageProxy.toBitmap() Dimens: ${bitmap.width}x${bitmap.height}, TargetRotation: $rotationDegrees")
                    
                    val embedding = mobileFaceNet.getFaceEmbedding(
                        bitmap,
                        face.boundingBox,
                        rotationDegrees,
                        faceLuminance = qualityResult.luminance
                    ) ?: return@withContext VerificationResult.Error("Failed to generate embedding")
                    return@withContext VerificationResult.Success(
                        embedding,
                        face,
                        qualityResult.luminance,
                        qualityResult.score,
                        qualityResult.blurScore
                    )
                } finally {
                    bitmap?.recycle()
                }
            }
            
            return@withContext VerificationResult.Warmup

        } catch (e: Exception) {
            android.util.Log.e("GuardianAI_Debug", "[AI] analyzeFrame error: ${e.message}", e)
            return@withContext VerificationResult.Error(e.message ?: "Unknown error")
        }
    }
    
    private fun handlePoorQuality(now: Long) {
        if (poorQualityStartTime == -1L) {
            poorQualityStartTime = now
        }
    }
    
    private fun getGuidanceMessage(priority: Int): String {
        return when (priority) {
            FaceQualityScorer.GUIDANCE_NO_FACE -> "Align your face in the frame"
            FaceQualityScorer.GUIDANCE_IMPROVE_LIGHTING -> "Improve lighting conditions"
            FaceQualityScorer.GUIDANCE_HOLD_STEADY -> "Hold the phone steady"
            FaceQualityScorer.GUIDANCE_MOVE_CLOSER -> "Move closer to the camera"
            FaceQualityScorer.GUIDANCE_LOOK_STRAIGHT -> "Look straight at the camera"
            else -> "Processing face..."
        }
    }

    /**
     * Legacy frame analysis helper.
     */
    suspend fun analyzeFrame(image: Image, rotationDegrees: Int, bitmap: Bitmap): VerificationResult = withContext(Dispatchers.Default) {
        try {
            val faces = faceDetector.detectFaces(image, rotationDegrees)
            if (faces.isEmpty()) {
                return@withContext VerificationResult.NoFace
            }
            if (faces.size > 1) {
                return@withContext VerificationResult.MultipleFaces
            }

            val face = faces[0]

            val quality = checkFaceQuality(face, bitmap.width, bitmap.height)
            if (quality != FaceQuality.GOOD) {
                return@withContext VerificationResult.PoorQuality(quality)
            }

            val embedding = mobileFaceNet.getFaceEmbedding(bitmap, face.boundingBox, rotationDegrees)
                ?: return@withContext VerificationResult.Error("Failed to generate embedding")

            return@withContext VerificationResult.Success(embedding, face)
        } catch (e: Exception) {
            return@withContext VerificationResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Analyzes static bitmap.
     */
    suspend fun analyzeStaticImage(bitmap: Bitmap): VerificationResult = withContext(Dispatchers.Default) {
        try {
            val faces = faceDetector.detectFacesFromBitmap(bitmap)
            if (faces.isEmpty()) {
                return@withContext VerificationResult.NoFace
            }
            if (faces.size > 1) {
                return@withContext VerificationResult.MultipleFaces
            }

            val face = faces[0]

            val quality = checkFaceQuality(face, bitmap.width, bitmap.height)
            if (quality != FaceQuality.GOOD) {
                return@withContext VerificationResult.PoorQuality(quality)
            }

            val embedding = mobileFaceNet.getFaceEmbedding(bitmap, face.boundingBox, 0, isFrontCamera = false)
                ?: return@withContext VerificationResult.Error("Failed to generate embedding")

            return@withContext VerificationResult.Success(embedding, face)
        } catch (e: Exception) {
            return@withContext VerificationResult.Error(e.message ?: "Unknown error")
        }
    }

    fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        clearCache()
        faceDetector.close()
        mobileFaceNet.close()
    }

    companion object {
        @JvmField
        @Deprecated("Use FaceRecognitionConfig.MATCH_THRESHOLD")
        val MATCH_THRESHOLD = FaceRecognitionConfig.MATCH_THRESHOLD

        fun calculateCosineSimilarity(emb1: FloatArray, emb2: FloatArray): Float {
            var dot = 0f
            var norm1 = 0f
            var norm2 = 0f
            for (i in emb1.indices) {
                dot += emb1[i] * emb2[i]
                norm1 += emb1[i] * emb1[i]
                norm2 += emb2[i] * emb2[i]
            }
            if (norm1 <= 0f || norm2 <= 0f) return 0f
            return dot / (sqrt(norm1) * sqrt(norm2))
        }

        fun calculateL2Distance(emb1: FloatArray, emb2: FloatArray): Float {
            var sum = 0f
            for (i in emb1.indices) {
                val diff = emb1[i] - emb2[i]
                sum += diff * diff
            }
            return sqrt(sum)
        }
    }
}

open class VerificationResult {
    open val faceLuminance: Int? = null

    open class NoFaceDetected : VerificationResult()
    object NoFace : NoFaceDetected()

    object MultipleFaces : VerificationResult()

    open class Failed(val reason: String) : VerificationResult()
    class PoorQuality(val quality: FaceQuality, override val faceLuminance: Int? = null) : VerificationResult()
    class Error(val reasonStr: String) : Failed(reasonStr)
    object Warmup : VerificationResult()

    data class Success(
        val embedding: FloatArray,
        val mlkitFace: Face,
        override val faceLuminance: Int? = null,
        val qualityScore: Int = 0,
        val blurScore: Int = 0
    ) : VerificationResult()
    data class Guidance(val priority: Int, val message: String, override val faceLuminance: Int? = null) : VerificationResult()
}
