package com.linka.android.core.identity

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Locale

data class VerifiedQrIdentity(
    val deviceId: String,
    val publicKeyBase64: String,
    val fingerprint: String,
    val issuedAtEpochMs: Long,
    val nonce: String
)

/**
 * Signed QR identity exchange. The QR carries a public key and a short-lived
 * signed statement only; it never contains a private key or session secret.
 *
 * Verification is trust-on-first-use: the user must scan the intended phone's
 * QR in person and compare its displayed fingerprint before saving trust.
 */
object LinkaQrIdentity {
    private const val VERSION = 1
    private const val MAX_AGE_MS = 120_000L
    private const val MAX_FUTURE_SKEW_MS = 30_000L
    private const val MAX_QR_LENGTH = 2_048
    private const val DOMAIN = "LINKA-IDENTITY-QR"

    fun create(identity: LocalDeviceIdentity, nowEpochMs: Long = System.currentTimeMillis()): String {
        require(nowEpochMs > 0)
        validateDeviceId(identity.deviceId)

        val nonceBytes = ByteArray(24).also(SecureRandom()::nextBytes)
        val nonce = Base64.encodeToString(
            nonceBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val statement = canonical(
            VERSION,
            identity.deviceId,
            identity.publicKeyBase64,
            nowEpochMs,
            nonce
        )
        val signature = Base64.encodeToString(identity.sign(statement), Base64.NO_WRAP)

        return JSONObject()
            .put("version", VERSION)
            .put("device_id", identity.deviceId)
            .put("public_key", identity.publicKeyBase64)
            .put("issued_at", nowEpochMs)
            .put("nonce", nonce)
            .put("signature", signature)
            .toString()
            .also { require(it.length <= MAX_QR_LENGTH) }
    }

    fun verify(
        rawValue: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): VerifiedQrIdentity {
        require(rawValue.length in 1..MAX_QR_LENGTH) { "QR payload size is invalid" }
        val json = JSONObject(rawValue)
        require(json.length() == 6) { "QR payload fields are invalid" }

        val version = json.getInt("version")
        require(version == VERSION) { "Unsupported LINKA identity QR version" }
        val deviceId = json.getString("device_id")
        validateDeviceId(deviceId)

        val publicKey = json.getString("public_key")
        require(publicKey.length in 80..512) { "Public key length is invalid" }

        val issuedAt = json.getLong("issued_at")
        require(issuedAt > 0) { "QR timestamp is invalid" }
        require(issuedAt <= nowEpochMs + MAX_FUTURE_SKEW_MS) { "QR was created in the future" }
        require(nowEpochMs - issuedAt <= MAX_AGE_MS) { "QR identity has expired" }

        val nonce = json.getString("nonce")
        require(nonce.matches(Regex("[A-Za-z0-9_-]{32}"))) { "QR nonce is invalid" }
        val signature = Base64.decode(json.getString("signature"), Base64.NO_WRAP)
        require(signature.size in 64..80) { "QR signature length is invalid" }

        val statement = canonical(version, deviceId, publicKey, issuedAt, nonce)
        require(
            LocalDeviceIdentity.verify(publicKey, statement, signature)
        ) { "QR identity signature is invalid" }

        val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
            .digest(Base64.decode(publicKey, Base64.NO_WRAP))
            .joinToString("") { byte -> String.format(Locale.ROOT, "%02X", byte) }

        return VerifiedQrIdentity(
            deviceId = deviceId,
            publicKeyBase64 = publicKey,
            fingerprint = fingerprint,
            issuedAtEpochMs = issuedAt,
            nonce = nonce
        )
    }

    private fun canonical(
        version: Int,
        deviceId: String,
        publicKeyBase64: String,
        issuedAtEpochMs: Long,
        nonce: String
    ): ByteArray = listOf(
        DOMAIN,
        version.toString(),
        deviceId,
        publicKeyBase64,
        issuedAtEpochMs.toString(),
        nonce
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun validateDeviceId(value: String) {
        require(value.matches(Regex("[0-9a-fA-F-]{36}"))) { "LINKA device ID is invalid" }
    }
}
