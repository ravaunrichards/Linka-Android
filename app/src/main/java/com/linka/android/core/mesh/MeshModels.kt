package com.linka.android.core.mesh

enum class LinkTransport { BLUETOOTH, WIFI_DIRECT, NEARBY_CONNECTIONS }

data class Neighbor(
    val deviceId: String,
    val transport: LinkTransport,
    val estimatedLatencyMs: Int?,
    val reliability: Double,
    val batteryPercent: Int?,
    val relayOptIn: Boolean
)

data class Route(
    val destinationDeviceId: String,
    val hops: List<String>,
    val estimatedLatencyMs: Int,
    val reliability: Double
) {
    val hopCount: Int get() = hops.size
}

data class VoiceEnvelope(
    val protocolVersion: Int,
    val callId: String,
    val senderDeviceId: String,
    val destinationDeviceId: String,
    val sequence: Long,
    val hopLimit: Int,
    val encryptedPayload: ByteArray
)
