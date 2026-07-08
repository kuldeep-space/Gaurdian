package com.ai.guardian.data.remote.models

data class SyncRequest(
    val requestId: String = "",
    val requesterId: String = "",
    val requesterName: String = "",
    val pairCode: String = "",
    val type: SyncRequestType = SyncRequestType.INITIAL_SYNC,
    val createdAt: Long = System.currentTimeMillis(),
    val status: SyncStatus = SyncStatus.PENDING,
    val publicKeyPem: String? = null,
    val keyId: String? = null,
    val keyVersion: Int = 0
)
