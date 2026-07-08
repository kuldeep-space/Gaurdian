package com.ai.guardian.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "paired_devices")
data class PairedDeviceEntity(
    @PrimaryKey val uuid: String,
    val deviceName: String,
    val pairingKey: String,
    val addedAt: Long = System.currentTimeMillis(),
    /**
     * True  → this record represents the Parent device that manages THIS device.
     *         This device is a CHILD. Guardian lock screen must be shown.
     *
     * False → this record represents a Child device that THIS device manages.
     *         This device is the PARENT. Guardian lock screen must NOT be shown.
     */
    val isParentDevice: Boolean = false,
    val parentPublicKeyId: String? = null,
    val parentPublicKeyPem: String? = null,
    val parentKeyVersion: Int = 0,
    val parentKeyCreatedAt: Long = 0L,
    val parentKeyExpiresAt: Long? = null
)
