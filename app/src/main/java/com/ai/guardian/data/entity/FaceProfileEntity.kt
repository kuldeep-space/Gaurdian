package com.ai.guardian.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

@Entity(tableName = "face_profiles")
data class FaceProfileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val avatarColorArgb: Int = Color.Gray.toArgb(),
    val registrationDate: Long = System.currentTimeMillis(),
    val enrollmentLuminance: Int = 130,
    val enrollmentBlurScore: Int = 30
)
