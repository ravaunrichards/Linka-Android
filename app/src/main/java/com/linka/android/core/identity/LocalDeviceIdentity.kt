package com.linka.android.core.identity

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/**
 * Stable, device-local LINKA identity.
 *
 * The identifier is a public routing label, not an authentication secret.
 * The signing private key is generated and retained by Android Keystore.
 */
data class LocalDeviceIdentity(
    val deviceId: String,
    val shortId: String,
    val publicKeyFingerprint: String,
    val publicKeyBase64: String
) {
    /** Sign a short identity statement without exporting the Keystore private key. */
    fun sign(data: ByteArray): ByteArray {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: error("LINKA identity signing key is unavailable")
        return java.security.Signature.getInstance("SHA256withECDSA").run {
            initSign(entry.privateKey)
            update(data)
            sign()
        }
    }

    companion object {
        private const val PREFERENCES = "linka_identity"
        private const val DEVICE_ID_KEY = "device_id"
        private const val KEY_ALIAS = "linka.identity.signing.v1"

        fun verify(
            publicKeyBase64: String,
            data: ByteArray,
            signatureBytes: ByteArray
        ): Boolean = runCatching {
            val encoded = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
            val publicKey = java.security.KeyFactory.getInstance("EC")
                .generatePublic(java.security.spec.X509EncodedKeySpec(encoded))
            java.security.Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(data)
                verify(signatureBytes)
            }
        }.getOrDefault(false)

        fun load(context: Context): LocalDeviceIdentity {
            val preferences = context.applicationContext
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

            val id = preferences.getString(DEVICE_ID_KEY, null) ?: UUID.randomUUID().toString()
                .also { generated ->
                    check(preferences.edit().putString(DEVICE_ID_KEY, generated).commit()) {
                        "Unable to persist LINKA device identity"
                    }
                }

            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC,
                    "AndroidKeyStore"
                )
                generator.initialize(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                    )
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build()
                )
                generator.generateKeyPair()
            }

            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
                ?: error("LINKA identity key is unavailable")

            val publicKey = entry.certificate.publicKey.encoded
            val fingerprint = MessageDigest.getInstance("SHA-256")
                .digest(publicKey)
                .joinToString("") { byte -> "%02X".format(byte) }

            return LocalDeviceIdentity(
                deviceId = id,
                shortId = id.substring(0, 8).uppercase(),
                publicKeyFingerprint = fingerprint,
                publicKeyBase64 = Base64.encodeToString(publicKey, Base64.NO_WRAP)
            )
        }
    }
}
