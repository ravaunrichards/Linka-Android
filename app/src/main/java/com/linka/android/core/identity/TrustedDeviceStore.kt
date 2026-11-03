package com.linka.android.core.identity

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class TrustedDevice(
    val deviceId: String,
    val publicKeyBase64: String,
    val fingerprint: String,
    val displayName: String,
    val trustedAtEpochMs: Long
)

/**
 * App-private trust-on-first-use store for public peer identity keys.
 *
 * Trust must be granted by scanning a valid, short-lived signed QR in person.
 * A device ID already bound to a different key is rejected rather than
 * silently overwritten. Revocation removes the saved public identity.
 */
class TrustedDeviceStore(
    context: Context,
    private val localDeviceId: String
) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun list(): List<TrustedDevice> = readAll().sortedBy { it.displayName.lowercase() }

    @Synchronized
    fun get(deviceId: String): TrustedDevice? =
        readAll().firstOrNull { it.deviceId == deviceId }

    @Synchronized
    fun isTrusted(deviceId: String, fingerprint: String? = null): Boolean {
        val record = get(deviceId) ?: return false
        return fingerprint == null || record.fingerprint.equals(fingerprint, ignoreCase = true)
    }

    @Synchronized
    fun trust(
        identity: VerifiedQrIdentity,
        displayName: String = "LINKA ${identity.deviceId.take(8).uppercase()}",
        nowEpochMs: Long = System.currentTimeMillis()
    ): TrustedDevice {
        require(identity.deviceId != localDeviceId) { "This QR belongs to this phone" }
        require(nowEpochMs > 0)
        require(displayName.isNotBlank() && displayName.length <= 48)

        val current = readAll()
        val existing = current.firstOrNull { it.deviceId == identity.deviceId }
        if (existing != null) {
            require(existing.fingerprint.equals(identity.fingerprint, ignoreCase = true)) {
                "This device ID is already linked to a different public key. Revoke it before pairing a replacement."
            }
            val renamed = existing.copy(displayName = displayName)
            persist(current.map { if (it.deviceId == identity.deviceId) renamed else it })
            return renamed
        }

        val trusted = TrustedDevice(
            deviceId = identity.deviceId,
            publicKeyBase64 = identity.publicKeyBase64,
            fingerprint = identity.fingerprint,
            displayName = displayName,
            trustedAtEpochMs = nowEpochMs
        )
        persist(current + trusted)
        return trusted
    }

    @Synchronized
    fun revoke(deviceId: String): Boolean {
        val current = readAll()
        val updated = current.filterNot { it.deviceId == deviceId }
        if (updated.size == current.size) return false
        persist(updated)
        return true
    }

    private fun readAll(): List<TrustedDevice> {
        val encoded = preferences.getString(TRUSTED_DEVICES_KEY, "[]") ?: "[]"
        val array = JSONArray(encoded)
        val result = ArrayList<TrustedDevice>(array.length())
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            result += TrustedDevice(
                deviceId = item.getString("device_id"),
                publicKeyBase64 = item.getString("public_key"),
                fingerprint = item.getString("fingerprint"),
                displayName = item.getString("display_name"),
                trustedAtEpochMs = item.getLong("trusted_at")
            )
        }
        return result
    }

    private fun persist(devices: List<TrustedDevice>) {
        val array = JSONArray()
        devices.forEach { trusted ->
            array.put(
                JSONObject()
                    .put("device_id", trusted.deviceId)
                    .put("public_key", trusted.publicKeyBase64)
                    .put("fingerprint", trusted.fingerprint)
                    .put("display_name", trusted.displayName)
                    .put("trusted_at", trusted.trustedAtEpochMs)
            )
        }
        check(
            preferences.edit()
                .putString(TRUSTED_DEVICES_KEY, array.toString())
                .commit()
        ) { "Unable to save trusted LINKA devices" }
    }

    companion object {
        private const val PREFERENCES = "linka_trusted_devices"
        private const val TRUSTED_DEVICES_KEY = "trusted_devices_v1"
    }
}
