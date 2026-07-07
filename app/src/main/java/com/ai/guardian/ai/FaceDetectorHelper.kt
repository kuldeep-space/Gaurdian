package com.ai.guardian.ai

import android.graphics.Bitmap
import android.media.Image
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await

class FaceDetectorHelper {
    private val detector: FaceDetector

    init {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)
            .build()
        detector = FaceDetection.getClient(options)
        android.util.Log.d("GuardianAI_Phase1", "[Init] ML Kit FaceDetector initialized.")
    }

    suspend fun detectFaces(image: Image, rotationDegrees: Int): List<Face> {
        val inputImage = InputImage.fromMediaImage(image, rotationDegrees)
        return try {
            detector.process(inputImage).await()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun detectFacesFromBitmap(bitmap: Bitmap): List<Face> {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        return try {
            detector.process(inputImage).await()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun close() {
        detector.close()
    }
}
