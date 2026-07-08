package com.ai.guardian.security

import android.util.Base64
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyStore
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

data class EncryptedPayload(
    val payloadId: String,
    val payloadVersion: Int,
    val algorithmVersion: Int,
    val encryptedPin: String,      // Base64
    val encryptedAesKey: String,   // Base64
    val algorithm: String,
    val keyId: String,
    val keyVersion: Int,
    val nonce: String,             // Base64 AES IV
    val updatedAt: Long,
    val pairId: String,
    val parentDeviceId: String,
    val childDeviceId: String
)

/**
 * Handles all core hybrid encryption (AES-256-GCM + RSA-OAEP-256) operations.
 */
object PinSyncCryptography {

    private const val AES_KEY_SIZE = 256
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val RSA_ALGORITHM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
    private const val AES_ALGORITHM = "AES/GCM/NoPadding"

    private val oaepParameterSpec = OAEPParameterSpec(
        "SHA-256",
        "MGF1",
        MGF1ParameterSpec.SHA1,
        PSource.PSpecified.DEFAULT
    )

    /**
     * Helper to zero-out sensitive CharArray in memory.
     */
    fun clearPin(pin: CharArray) {
        for (i in pin.indices) {
            pin[i] = '0'
        }
    }

    /**
     * Parses a public key PEM string into a PublicKey instance.
     */
    fun parsePublicKeyPem(pem: String): PublicKey {
        val cleanPem = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\n", "")
            .replace("\r", "")
            .trim()
        val decoded = Base64.decode(cleanPem, Base64.NO_WRAP)
        val spec = X509EncodedKeySpec(decoded)
        val kf = KeyFactory.getInstance("RSA")
        return kf.generatePublic(spec)
    }

    /**
     * Encrypts the plaintext PIN using hybrid encryption (AES-GCM + RSA-OAEP-256).
     * Automatically zeroes out the plaintext bytes.
     */
    fun encryptPin(
        pin: CharArray,
        parentPublicKeyPem: String,
        keyId: String,
        keyVersion: Int,
        pairId: String,
        parentDeviceId: String,
        childDeviceId: String
    ): EncryptedPayload {
        // 1. Convert CharArray to ByteArray without persistent String allocations
        val byteBuffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(pin))
        val pinBytes = ByteArray(byteBuffer.remaining())
        byteBuffer.get(pinBytes)

        try {
            // 2. Generate ephemeral AES-256 key
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(AES_KEY_SIZE)
            val aesKey = keyGen.generateKey()

            // 3. Encrypt PIN using AES-GCM
            val aesCipher = Cipher.getInstance(AES_ALGORITHM)
            aesCipher.init(Cipher.ENCRYPT_MODE, aesKey)
            val iv = aesCipher.iv
            val encryptedPinBytes = aesCipher.doFinal(pinBytes)

            // 4. Encrypt AES key using RSA public key
            val publicKey = parsePublicKeyPem(parentPublicKeyPem)
            val rsaCipher = Cipher.getInstance(RSA_ALGORITHM)
            rsaCipher.init(Cipher.ENCRYPT_MODE, publicKey, oaepParameterSpec)
            val encryptedKeyBytes = rsaCipher.doFinal(aesKey.encoded)

            // 5. Construct payload
            return EncryptedPayload(
                payloadId = UUID.randomUUID().toString(),
                payloadVersion = 1,
                algorithmVersion = 1,
                encryptedPin = Base64.encodeToString(encryptedPinBytes, Base64.NO_WRAP),
                encryptedAesKey = Base64.encodeToString(encryptedKeyBytes, Base64.NO_WRAP),
                algorithm = "$AES_ALGORITHM/$RSA_ALGORITHM",
                keyId = keyId,
                keyVersion = keyVersion,
                nonce = Base64.encodeToString(iv, Base64.NO_WRAP),
                updatedAt = System.currentTimeMillis(),
                pairId = pairId,
                parentDeviceId = parentDeviceId,
                childDeviceId = childDeviceId
            )
        } finally {
            // Securely overwrite the plaintext bytes in memory
            for (i in pinBytes.indices) {
                pinBytes[i] = 0
            }
            clearPin(pin)
        }
    }

    /**
     * Decrypts the E2EE payload back into a plaintext CharArray.
     */
    fun decryptPin(
        payload: EncryptedPayload,
        privateKeyEntry: KeyStore.PrivateKeyEntry
    ): CharArray {
        // 1. Decrypt the AES key using RSA private key
        val rsaCipher = Cipher.getInstance(RSA_ALGORITHM)
        rsaCipher.init(Cipher.DECRYPT_MODE, privateKeyEntry.privateKey, oaepParameterSpec)
        val decryptedKeyBytes = rsaCipher.doFinal(Base64.decode(payload.encryptedAesKey, Base64.NO_WRAP))
        val aesKey = SecretKeySpec(decryptedKeyBytes, "AES")

        try {
            // 2. Decrypt the PIN using the AES key
            val aesCipher = Cipher.getInstance(AES_ALGORITHM)
            val iv = Base64.decode(payload.nonce, Base64.NO_WRAP)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            aesCipher.init(Cipher.DECRYPT_MODE, aesKey, spec)

            val decryptedPinBytes = aesCipher.doFinal(Base64.decode(payload.encryptedPin, Base64.NO_WRAP))
            try {
                // 3. Convert bytes back to CharArray without allocating strings
                val charBuffer = StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(decryptedPinBytes))
                val result = CharArray(charBuffer.remaining())
                charBuffer.get(result)
                return result
            } finally {
                // Overwrite intermediate decrypted bytes
                for (i in decryptedPinBytes.indices) {
                    decryptedPinBytes[i] = 0
                }
            }
        } finally {
            // Overwrite key bytes
            for (i in decryptedKeyBytes.indices) {
                decryptedKeyBytes[i] = 0
            }
        }
    }
}
