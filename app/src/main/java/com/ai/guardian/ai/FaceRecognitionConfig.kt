package com.ai.guardian.ai

object FaceRecognitionConfig {
    /**
     * Similarity threshold for matching face embeddings.
     * Note: This threshold is model-dependent and must be calibrated using
     * real device testing and target FAR/FRR metrics.
     * Calibrated to 0.65f (optimal balance between False Accept and False Reject).
     */
    const val MATCH_THRESHOLD = 0.65f

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
    // Slowed from 0.3f → 0.15f: prevents ISP oscillation from spiking EMA on low-end cameras
    const val EMA_ALPHA = 0.15f
    const val WARMUP_FRAMES_TO_SKIP = 3
    const val MAXIMUM_WARMUP_TIME_MS = 300L
    // Increased from 3 → 4: requires more evidence before committing a lighting state change
    const val STABLE_STATE_COUNT_REQUIRED = 4

    // Engine warmup: moved from hardcoded 600ms → 400ms via config
    const val ENGINE_WARMUP_MS = 400L

    // Gamma correction: applied to face crop when luminance is below this threshold.
    // γ = 0.65 non-linearly lifts shadows without blowing out highlights.
    // Pre-computed LUT has 256-byte footprint. Applied on 112×112 crop: < 1ms.
    const val GAMMA_CORRECTION_THRESHOLD = 80
    const val GAMMA_VALUE = 0.65f

    // Embedding ring buffer: 2 frames, averaged before final match in borderline cases.
    // Fast-unlock margin: if score exceeds threshold by this amount, unlock immediately.
    const val EMBEDDING_RING_BUFFER_SIZE = 2
    const val EMBEDDING_FAST_UNLOCK_MARGIN = 0.05f

    // Guidance hysteresis: minimum milliseconds between guidance text changes.
    const val GUIDANCE_UPDATE_MIN_MS = 1000L
    const val APP_LOCK_LAUNCH_TIMEOUT_MS = 3000L
}
