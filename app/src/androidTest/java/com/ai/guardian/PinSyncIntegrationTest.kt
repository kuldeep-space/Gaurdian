package com.ai.guardian

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.guardian.security.EncryptedPayload
import com.ai.guardian.security.ParentKeyManager
import com.ai.guardian.security.PinSyncCryptography
import com.ai.guardian.security.PinSyncManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPairGenerator
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class PinSyncIntegrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var parentKeyManager: ParentKeyManager
    private lateinit var pinSyncManager: PinSyncManager

    private val testPairId = "test-pair-uuid"
    private val testParentId = "test-parent-device-id"
    private val testChildId = "test-child-device-id"

    @Before
    fun setUp() {
        parentKeyManager = ParentKeyManager(context)
        pinSyncManager = PinSyncManager.getInstance(context)
        
        // Clean up any test keystore aliases
        parentKeyManager.deleteKey("test-key-1")
        parentKeyManager.deleteKey("test-key-2")
    }

    @Test
    fun testSuccessfulEncryptDecrypt() {
        val keyId = "test-key-1"
        parentKeyManager.getOrCreateKeyPair(keyId)
        val publicKeyPem = parentKeyManager.exportPublicKeyPem(keyId)
        assertNotNull("Public key PEM should not be null", publicKeyPem)

        val originalPin = charArrayOf('1', '2', '3', '4')

        // Encrypt on Child
        val payload = PinSyncCryptography.encryptPin(
            pin = originalPin,
            parentPublicKeyPem = publicKeyPem!!,
            keyId = keyId,
            keyVersion = 1,
            pairId = testPairId,
            parentDeviceId = testParentId,
            childDeviceId = testChildId
        )

        // Verify sensitive data zero-out
        assertArrayEquals("Original PIN CharArray should be zeroed out", charArrayOf('0', '0', '0', '0'), originalPin)
        assertNotNull(payload.encryptedPin)
        assertNotNull(payload.encryptedAesKey)

        // Decrypt on Parent
        val privateKeyEntry = parentKeyManager.getPrivateKeyEntry(keyId)
        assertNotNull("Private key entry should not be null", privateKeyEntry)

        val decryptedPin = PinSyncCryptography.decryptPin(payload, privateKeyEntry!!)
        assertArrayEquals(charArrayOf('1', '2', '3', '4'), decryptedPin)
    }

    @Test
    fun testWrongKeyDecryptionFailure() {
        val keyId1 = "test-key-1"
        val keyId2 = "test-key-2"

        parentKeyManager.getOrCreateKeyPair(keyId1)
        parentKeyManager.getOrCreateKeyPair(keyId2)

        val publicKeyPem1 = parentKeyManager.exportPublicKeyPem(keyId1)

        val payload = PinSyncCryptography.encryptPin(
            pin = charArrayOf('5', '6', '7', '8'),
            parentPublicKeyPem = publicKeyPem1!!,
            keyId = keyId1,
            keyVersion = 1,
            pairId = testPairId,
            parentDeviceId = testParentId,
            childDeviceId = testChildId
        )

        // Decrypt using second key (should fail/throw)
        val privateKeyEntry2 = parentKeyManager.getPrivateKeyEntry(keyId2)
        assertNotNull(privateKeyEntry2)

        try {
            PinSyncCryptography.decryptPin(payload, privateKeyEntry2!!)
            fail("Decryption should have failed with wrong key")
        } catch (e: Exception) {
            // Expected
        }
    }

    @Test
    fun testCorruptedPayloadHandling() {
        val keyId = "test-key-1"
        parentKeyManager.getOrCreateKeyPair(keyId)
        val publicKeyPem = parentKeyManager.exportPublicKeyPem(keyId)

        val payload = PinSyncCryptography.encryptPin(
            pin = charArrayOf('9', '9', '9', '9'),
            parentPublicKeyPem = publicKeyPem!!,
            keyId = keyId,
            keyVersion = 1,
            pairId = testPairId,
            parentDeviceId = testParentId,
            childDeviceId = testChildId
        )

        // Corrupt encryptedAesKey bytes
        val corruptedPayload = payload.copy(encryptedAesKey = "corrupted-base64-bytes")
        val privateKeyEntry = parentKeyManager.getPrivateKeyEntry(keyId)

        try {
            PinSyncCryptography.decryptPin(corruptedPayload, privateKeyEntry!!)
            fail("Decryption should fail on corrupted payload")
        } catch (e: Exception) {
            // Expected
        }
    }

    @Test
    fun testReplayAndMetadataRejection() {
        val keyId = "test-key-1"
        parentKeyManager.getOrCreateKeyPair(keyId)
        val publicKeyPem = parentKeyManager.exportPublicKeyPem(keyId)

        val payload = PinSyncCryptography.encryptPin(
            pin = charArrayOf('4', '3', '2', '1'),
            parentPublicKeyPem = publicKeyPem!!,
            keyId = keyId,
            keyVersion = 1,
            pairId = testPairId,
            parentDeviceId = testParentId,
            childDeviceId = testChildId
        )

        // 1. Success decryption
        val stateMap = mapOf(
            "payloadId" to payload.payloadId,
            "payloadVersion" to payload.payloadVersion,
            "algorithmVersion" to payload.algorithmVersion,
            "encryptedPin" to payload.encryptedPin,
            "encryptedAesKey" to payload.encryptedAesKey,
            "algorithm" to payload.algorithm,
            "keyId" to payload.keyId,
            "keyVersion" to payload.keyVersion,
            "nonce" to payload.nonce,
            "updatedAt" to payload.updatedAt,
            "metadata" to mapOf(
                "pairId" to payload.pairId,
                "parentDeviceId" to payload.parentDeviceId,
                "childDeviceId" to payload.childDeviceId
            )
        )

        val decrypted1 = pinSyncManager.decryptReceivedPayload(
            stateMap, parentKeyManager, testParentId, testChildId, testPairId
        )
        assertNotNull(decrypted1)
        assertArrayEquals(charArrayOf('4', '3', '2', '1'), decrypted1)

        // 2. Replay check (should return null on second attempt)
        val decrypted2 = pinSyncManager.decryptReceivedPayload(
            stateMap, parentKeyManager, testParentId, testChildId, testPairId
        )
        assertNull("Replayed payload should be rejected", decrypted2)

        // 3. Pairing Metadata Mismatch (should return null)
        val invalidStateMap = stateMap.toMutableMap().apply {
            put("metadata", mapOf(
                "pairId" to "hacker-pair-id",
                "parentDeviceId" to testParentId,
                "childDeviceId" to testChildId
            ))
        }
        val decrypted3 = pinSyncManager.decryptReceivedPayload(
            invalidStateMap, parentKeyManager, testParentId, testChildId, testPairId
        )
        assertNull("Metadata mismatch should be rejected", decrypted3)
    }

    @Test
    fun testKeyRotation() {
        val keyId1 = "test-key-1"
        val keyId2 = "test-key-2"

        parentKeyManager.getOrCreateKeyPair(keyId1)
        val pubKey1 = parentKeyManager.exportPublicKeyPem(keyId1)

        val payload1 = PinSyncCryptography.encryptPin(
            pin = charArrayOf('1', '1', '1', '1'),
            parentPublicKeyPem = pubKey1!!,
            keyId = keyId1,
            keyVersion = 1,
            pairId = testPairId,
            parentDeviceId = testParentId,
            childDeviceId = testChildId
        )

        // Rotate
        parentKeyManager.getOrCreateKeyPair(keyId2)
        val pubKey2 = parentKeyManager.exportPublicKeyPem(keyId2)

        val payload2 = PinSyncCryptography.encryptPin(
            pin = charArrayOf('2', '2', '2', '2'),
            parentPublicKeyPem = pubKey2!!,
            keyId = keyId2,
            keyVersion = 2,
            pairId = testPairId,
            parentDeviceId = testParentId,
            childDeviceId = testChildId
        )

        val dec1 = PinSyncCryptography.decryptPin(payload1, parentKeyManager.getPrivateKeyEntry(keyId1)!!)
        val dec2 = PinSyncCryptography.decryptPin(payload2, parentKeyManager.getPrivateKeyEntry(keyId2)!!)

        assertArrayEquals(charArrayOf('1', '1', '1', '1'), dec1)
        assertArrayEquals(charArrayOf('2', '2', '2', '2'), dec2)
    }
}
