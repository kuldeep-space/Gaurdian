package com.ai.guardian.ai

enum class AuthenticationState {
    INITIALIZING,
    WARMING_UP,
    BRIGHTNESS_ANALYSIS,
    FACE_SEARCHING,
    RECOGNIZING,
    SECOND_INFERENCE,
    GUIDANCE,
    SUCCESS,
    FAILED,
    CLEANUP
}
