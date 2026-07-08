package com.ai.guardian.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.RSAKeyGenParameterSpec

/**
 * Manages the RSA asymmetric keypair stored in Android KeyStore on the Parent device.
 * Private key is non-exportable and performs decryption strictly inside the Keystore/HSM.
 */
class ParentKeyManager(private val context: Context) {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
    }

    /**
     * Retrieves or generates a new non-exportable RSA-2048 keypair in Android KeyStore.
     */
    fun getOrCreateKeyPair(keyId: String): KeyPair {
        val alias = aliasFor(keyId)
        if (keyStore.containsAlias(alias)) {
            val privateKey = keyStore.getKey(alias, null) as? java.security.PrivateKey
            val publicKey = keyStore.getCertificate(alias)?.publicKey
            if (privateKey != null && publicKey != null) {
                return KeyPair(publicKey, privateKey)
            }
        }

        Log.d(TAG, "Generating new RSA keypair for alias: $alias")
        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA,
            "AndroidKeyStore"
        )
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT
        )
            .setAlgorithmParameterSpec(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
            .setBlockModes(KeyProperties.BLOCK_MODE_ECB)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setDigests(
                KeyProperties.DIGEST_SHA1,
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA512
            )
            .build()

        keyPairGenerator.initialize(spec)
        return keyPairGenerator.generateKeyPair()
    }

    /**
     * Returns the KeyStore.PrivateKeyEntry for decryption, or null if the key doesn't exist.
     */
    fun getPrivateKeyEntry(keyId: String): KeyStore.PrivateKeyEntry? {
        val alias = aliasFor(keyId)
        if (!keyStore.containsAlias(alias)) return null
        return keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
    }

    /**
     * Exports the Public Key of the keypair in standard PEM format.
     */
    fun exportPublicKeyPem(keyId: String): String? {
        val alias = aliasFor(keyId)
        if (!keyStore.containsAlias(alias)) {
            // Force generate to ensure we have key
            getOrCreateKeyPair(keyId)
        }
        val certificate = keyStore.getCertificate(alias) ?: return null
        val base64 = Base64.encodeToString(certificate.publicKey.encoded, Base64.NO_WRAP)
        return "-----BEGIN PUBLIC KEY-----\n$base64\n-----END PUBLIC KEY-----"
    }

    /**
     * Deletes the key entry from the KeyStore.
     */
    fun deleteKey(keyId: String) {
        val alias = aliasFor(keyId)
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
            Log.d(TAG, "Deleted RSA key for alias: $alias")
        }
    }

    private fun aliasFor(keyId: String): String = "guardian_parent_rsa_$keyId"

    companion object {
        private const val TAG = "ParentKeyManager"
    }
}
