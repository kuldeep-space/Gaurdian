package com.ai.guardian.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.os.SystemClock

class MobileFaceNet(context: Context) {
    private var interpreter: Interpreter
    private val inferenceMutex = Mutex()

    // Gamma LUT — precomputed once at class init. 256-int footprint (~1KB).
    // γ = GAMMA_VALUE (0.65) lifts shadows non-linearly without overexposing highlights.
    // Only applied when face luminance < GAMMA_CORRECTION_THRESHOLD (80).
    private val gammaLut: IntArray = IntArray(256) { i ->
        (Math.pow(i / 255.0, FaceRecognitionConfig.GAMMA_VALUE.toDouble()) * 255.0 + 0.5)
            .toInt().coerceIn(0, 255)
    }

    init {
        val options = Interpreter.Options().apply {
            setNumThreads(4)
            // XNNPACK: utilizes ARM NEON SIMD to reduce inference by ~30-40%.
            // Available on all ARM devices from Android 8.0+. Falls back silently if unsupported.
            setUseXNNPACK(true)
        }
        interpreter = Interpreter(loadModelFile(context, "mobilefacenet.tflite"), options)
        android.util.Log.d("GuardianAI_Phase1", "[Init] MobileFaceNet Interpreter initialized (TFLite).")
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    suspend fun getFaceEmbedding(bitmap: Bitmap, faceRect: Rect, rotationDegrees: Int = 0, isFrontCamera: Boolean = true, faceLuminance: Int = 128): FloatArray? {
        android.util.Log.d("GuardianAI_Phase2", "[Pipeline] getFaceEmbedding started. Source Bitmap: ${bitmap.width}x${bitmap.height}, TargetRotation: $rotationDegrees")
        val startTime = SystemClock.uptimeMillis()
        var croppedFace: Bitmap? = null
        var uprightFace: Bitmap? = null
        try {
            // MAP COORDINATES
            val unrotatedRect = if (rotationDegrees != 0) {
                mapRectToOriginalSpace(faceRect, bitmap.width, bitmap.height, rotationDegrees)
            } else {
                faceRect
            }
            android.util.Log.d("GuardianAI_Phase2", "[Pipeline] Mapped ML Kit Rect from $faceRect to Unrotated Sensor Rect $unrotatedRect")

            // CROP
            croppedFace = cropFace(bitmap, unrotatedRect)

            // ALIGN
            uprightFace = if (rotationDegrees != 0 || isFrontCamera) {
                val matrix = android.graphics.Matrix().apply {
                    if (rotationDegrees != 0) {
                        postRotate(rotationDegrees.toFloat())
                    }
                    if (isFrontCamera) {
                        postScale(-1f, 1f)
                    }
                }
                Bitmap.createBitmap(croppedFace, 0, 0, croppedFace.width, croppedFace.height, matrix, true)
            } else {
                croppedFace
            }
            android.util.Log.d("GuardianAI_Phase2", "[Pipeline] Rotation/Mirror applied. Final dimensions: ${uprightFace?.width}x${uprightFace?.height}")

            // Apply gamma correction on dark face crops only.
            // In good light this is skipped entirely — zero cost path.
            if (faceLuminance < FaceRecognitionConfig.GAMMA_CORRECTION_THRESHOLD) {
                if (!uprightFace!!.isMutable) {
                    val mutableCopy = uprightFace.copy(Bitmap.Config.ARGB_8888, true)
                    if (uprightFace !== croppedFace && uprightFace !== bitmap) {
                        uprightFace.recycle()
                    }
                    uprightFace = mutableCopy
                }
                applyGammaLut(uprightFace)
            }

            // Image Processing for MobileFaceNet (112x112, Normalized)
            val imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(112, 112, ResizeOp.ResizeMethod.BILINEAR))
                .add(NormalizeOp(127.5f, 127.5f))
                .build()

            var tensorImage = TensorImage(org.tensorflow.lite.DataType.FLOAT32)
            tensorImage.load(uprightFace)
            tensorImage = imageProcessor.process(tensorImage)

            val output = Array(1) { FloatArray(192) }

            inferenceMutex.withLock {
                interpreter.run(tensorImage.buffer, output)
            }

            return normalizeL2(output[0])
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            if (croppedFace != null && croppedFace !== bitmap) {
                croppedFace.recycle()
            }
            if (uprightFace != null && uprightFace !== croppedFace && uprightFace !== bitmap) {
                uprightFace.recycle()
            }
        }
    }

    /**
     * Applies a precomputed gamma LUT to an ARGB_8888 mutable bitmap in-place.
     * Operates on R, G, B channels independently. Alpha is preserved.
     * Cost: ~12,544 LUT lookups per 112×112 bitmap = <1ms on any ARM device.
     */
    private fun applyGammaLut(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val px = pixels[i]
            val r = gammaLut[(px shr 16) and 0xFF]
            val g = gammaLut[(px shr 8) and 0xFF]
            val b = gammaLut[px and 0xFF]
            pixels[i] = (px and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun cropFace(bitmap: Bitmap, rect: Rect): Bitmap {
        var x = rect.left
        var y = rect.top
        var w = rect.width()
        var h = rect.height()

        x = x.coerceAtLeast(0)
        y = y.coerceAtLeast(0)
        w = w.coerceAtMost(bitmap.width - x)
        h = h.coerceAtMost(bitmap.height - y)

        if (w <= 0 || h <= 0) {
            throw IllegalArgumentException("Invalid face bounding box")
        }

        return Bitmap.createBitmap(bitmap, x, y, w, h)
    }

    private fun mapRectToOriginalSpace(rect: Rect, originalWidth: Int, originalHeight: Int, rotationDegrees: Int): Rect {
        val mappedRect = android.graphics.RectF(rect)
        val matrix = android.graphics.Matrix()
        
        val rotW = if (rotationDegrees % 180 != 0) originalHeight else originalWidth
        val rotH = if (rotationDegrees % 180 != 0) originalWidth else originalHeight
        
        matrix.postTranslate(-rotW / 2f, -rotH / 2f)
        matrix.postRotate(-rotationDegrees.toFloat())
        matrix.postTranslate(originalWidth / 2f, originalHeight / 2f)
        
        matrix.mapRect(mappedRect)
        return Rect(mappedRect.left.toInt(), mappedRect.top.toInt(), mappedRect.right.toInt(), mappedRect.bottom.toInt())
    }

    private fun normalizeL2(embedding: FloatArray): FloatArray {
        var sum = 0f
        for (value in embedding) {
            sum += value * value
        }
        val norm = sqrt(sum)
        for (i in embedding.indices) {
            embedding[i] = embedding[i] / norm
        }
        return embedding
    }

    fun close() {
        interpreter.close()
    }
}
