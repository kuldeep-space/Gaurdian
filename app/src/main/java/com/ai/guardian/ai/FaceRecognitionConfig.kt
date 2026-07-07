package com.ai.guardian.ai

object FaceRecognitionConfig {
    /**
     * Similarity threshold for matching face embeddings.
     * Note: This threshold is model-dependent and must be calibrated using
     * real device testing and target FAR/FRR metrics.
     * The value 0.55f is a temporary placeholder.
     */
    const val MATCH_THRESHOLD = 0.55f

    // Face size limits (percentage of image area)
    const val MIN_FACE_SIZE_RATIO = 0.20f
    const val MAX_FACE_SIZE_RATIO = 0.60f

    // Head rotation limits (in degrees)
    const val MAX_EULER_Y = 40.0f
    const val MAX_EULER_X = 20.0f
    const val MAX_EULER_Z = 20.0f

    // Pose stability threshold (in degrees)
    const val POSE_STABILITY_THRESHOLD = 2.0f

    // Timeouts (in milliseconds)
    const val ENROLLMENT_TIMEOUT_MS = 15000L
    const val AUTHENTICATION_TIMEOUT_NORMAL_MS = 7000L
    const val AUTHENTICATION_TIMEOUT_DIM_MS = 8000L
    const val AUTHENTICATION_TIMEOUT_VERY_DARK_MS = 9000L

    // Lighting Thresholds (Luminance 0-255)
    const val LUM_VERY_DARK = 50
    const val LUM_DIM = 90
    const val LUM_NORMAL = 130
    const val LUM_GOOD = 170

    // Adaptive Stabilizers & Policies
    const val CONFIDENCE_MARGIN = 0.03f
    const val BRIGHTNESS_SAMPLE_INTERVAL_MS = 250L
    const val EXPOSURE_UPDATE_COOLDOWN_MS = 750L
    const val EMA_ALPHA = 0.3f
    const val WARMUP_FRAMES_TO_SKIP = 3
    const val MAXIMUM_WARMUP_TIME_MS = 300L
    const val STABLE_STATE_COUNT_REQUIRED = 3
    const val APP_LOCK_LAUNCH_TIMEOUT_MS = 3000L
}
