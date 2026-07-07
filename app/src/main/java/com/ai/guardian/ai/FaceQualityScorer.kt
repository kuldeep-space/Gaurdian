package com.ai.guardian.ai

import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.face.Face

data class QualityResult(
    val score: Int,
    val luminance: Int,
    val blurScore: Int,
    val guidancePriority: Int
)

object FaceQualityScorer {

    // Guidance Priorities (Lower number = Higher Priority)
    const val GUIDANCE_NONE = 0
    const val GUIDANCE_NO_FACE = 1
    const val GUIDANCE_IMPROVE_LIGHTING = 2
    const val GUIDANCE_HOLD_STEADY = 3
    const val GUIDANCE_MOVE_CLOSER = 4
    const val GUIDANCE_LOOK_STRAIGHT = 5

    /**
     * Calculates a lightweight quality score (0-100) using the Y-plane of the ImageProxy.
     * Operates strictly on primitives to avoid Bitmap allocations.
     */
    fun calculateQuality(imageProxy: ImageProxy, face: Face): QualityResult {
        val width = imageProxy.width
        val height = imageProxy.height
        val box = face.boundingBox

        // Size check
        val imageArea = (width * height).toFloat()
        val faceArea = (box.width() * box.height()).toFloat()
        val faceRatio = if (imageArea > 0f) faceArea / imageArea else 0f

        var guidance = GUIDANCE_NONE

        if (faceRatio < FaceRecognitionConfig.MIN_FACE_SIZE_RATIO) {
            guidance = GUIDANCE_MOVE_CLOSER
        }

        // Pose check
        val rotZ = Math.abs(face.headEulerAngleZ)
        if (rotZ > 35f) { // Relaxed threshold for general tracking
            if (guidance == GUIDANCE_NONE || guidance > GUIDANCE_LOOK_STRAIGHT) {
                guidance = GUIDANCE_LOOK_STRAIGHT
            }
        }

        // Lightweight Y-Plane Analysis (Luminance & Blur)
        var luminance = 0
        var blurScore = 0

        val planes = imageProxy.planes
        if (planes.isNotEmpty()) {
            val yPlane = planes[0]
            val buffer = yPlane.buffer
            val rowStride = yPlane.rowStride
            val pixelStride = yPlane.pixelStride

            // Restrict bounds to the image limits
            val left = box.left.coerceAtLeast(0)
            val top = box.top.coerceAtLeast(0)
            val right = box.right.coerceAtMost(width - 1)
            val bottom = box.bottom.coerceAtMost(height - 1)

            if (right > left && bottom > top) {
                var sumLuma = 0L
                var sumLaplacian = 0L
                var pixelCount = 0
                var gradCount = 0

                // Sparse sampling: Skip rows and columns for O(N) speed
                val stepX = maxOf(1, (right - left) / 15)
                val stepY = maxOf(1, (bottom - top) / 15)

                try {
                    // Save buffer position to restore it later
                    val originalPos = buffer.position()
                    buffer.position(0)

                    // Dual-direction Laplacian: horizontal + vertical gradients.
                    // In low light the Y-plane is uniformly dark; a horizontal-only gradient
                    // gives near-zero blur scores even for sharp faces. Adding the vertical
                    // direction doubles gradient signal and correctly identifies true sharpness.
                    for (y in top until (bottom - stepY) step stepY) {
                        val rowOffset = y * rowStride
                        val nextRowOffset = (y + stepY) * rowStride
                        for (x in left until (right - stepX) step stepX) {
                            val idx1 = rowOffset + x * pixelStride
                            val idx2 = rowOffset + (x + stepX) * pixelStride  // horizontal neighbor
                            val idx3 = nextRowOffset + x * pixelStride         // vertical neighbor

                            if (idx2 < buffer.capacity() && idx3 < buffer.capacity()) {
                                val p1 = buffer.get(idx1).toInt() and 0xFF
                                val p2 = buffer.get(idx2).toInt() and 0xFF
                                val p3 = buffer.get(idx3).toInt() and 0xFF

                                sumLuma += p1
                                // Sum horizontal + vertical absolute differences and divide by 2 to maintain threshold scale
                                sumLaplacian += (Math.abs(p1 - p2) + Math.abs(p1 - p3)) / 2
                                pixelCount++
                                gradCount++
                            }
                        }
                    }

                    // Restore buffer position for other analyzers
                    buffer.position(originalPos)
                } catch (e: Exception) {
                    // Ignore index exceptions
                }

                if (pixelCount > 0) {
                    luminance = (sumLuma / pixelCount).toInt()
                }
                if (gradCount > 0) {
                    blurScore = (sumLaplacian / gradCount).toInt()
                }
            }
        }

        if (luminance < 30) {
            if (guidance == GUIDANCE_NONE || guidance > GUIDANCE_IMPROVE_LIGHTING) {
                guidance = GUIDANCE_IMPROVE_LIGHTING
            }
        }

        // Luminance-normalized blur score.
        // Raw blur in low light is structurally suppressed (dark pixels have low gradients
        // even when the image is sharp). Normalizing against luminance corrects for this:
        // a sharp face at luma=50 gets a comparable blur score to a sharp face at luma=150.
        val normalizedBlurScore = if (luminance > 20) {
            (blurScore.toFloat() * 128f / luminance).toInt().coerceIn(0, 80)
        } else {
            blurScore
        }

        if (normalizedBlurScore < 12) {
            if (guidance == GUIDANCE_NONE || guidance > GUIDANCE_HOLD_STEADY) {
                guidance = GUIDANCE_HOLD_STEADY
            }
        }

        // Score weights: Blur 30%, Luminance 40%, Pose/Size 30%.
        // Previous: Blur 40% / Luma 30% — in low light blur was suppressed so 40% of the
        // score was near-zero for sharp dark faces, causing them to fail the quality gate.
        // Luminance at 40% now correctly penalizes true darkness while not penalizing
        // dark-but-sharp frames that previously scored < min threshold unfairly.
        val normBlur = (normalizedBlurScore / 25f).coerceIn(0f, 1f) * 30f
        val normLuma = (luminance / 120f).coerceIn(0f, 1f) * 40f

        // Size & Pose (30% weight)
        var normPose = 30f
        if (guidance == GUIDANCE_MOVE_CLOSER) normPose -= 15f
        if (guidance == GUIDANCE_LOOK_STRAIGHT) normPose -= 15f
        if (face.leftEyeOpenProbability != null && face.leftEyeOpenProbability!! < 0.2f) normPose -= 10f

        val finalScore = (normBlur + normLuma + normPose).toInt().coerceIn(0, 100)

        return QualityResult(
            score = finalScore,
            luminance = luminance,
            blurScore = normalizedBlurScore,
            guidancePriority = guidance
        )
    }
}
