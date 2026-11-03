package com.linka.android.core.identity

import java.util.UUID

/**
 * Temporary local identity foundation.
 * Production identity must be backed by Android Keystore keys and explicit pairing.
 */
data class LocalDeviceIdentity(
    val deviceId: String,
    val shortId: String
) {
    companion object {
        fun create(): LocalDeviceIdentity {
            val id = UUID.randomUUID().toString()
            return LocalDeviceIdentity(deviceId = id, shortId = id.take(8).uppercase())
        }
    }
}
