package com.linka.android.core.mesh

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

enum class MeshPacketType(val wireValue: Int) {
    ROUTE_REQUEST(1),
    ROUTE_REPLY(2),
    OPAQUE_DATA(3);

    companion object {
        fun fromWire(value: Int): MeshPacketType =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Unknown LINKA mesh packet type")
    }
}

data class MeshPacket(
    val type: MeshPacketType,
    val packetId: String,
    val requestId: String,
    val sourceDeviceId: String,
    val destinationDeviceId: String,
    val hopLimit: Int,
    val hopCount: Int,
    val expiresAtEpochMs: Long,
    val payload: ByteArray,
    val signature: ByteArray
)

data class MeshNeighbor(
    val endpointId: String,
    val deviceId: String,
    /** Signed relay-consent capability advertised by the remote phone. */
    val relayOptIn: Boolean
)

data class MeshRoute(
    val destinationDeviceId: String,
    val nextHopEndpointId: String,
    val nextHopDeviceId: String,
    val hopCount: Int,
    val expiresAtEpochMs: Long
)

sealed interface MeshRouterAction {
    data class SendTo(val endpointId: String, val packet: ByteArray) : MeshRouterAction
    data class DeliverOpaqueData(val sourceDeviceId: String, val payload: ByteArray) : MeshRouterAction
    data class RouteEstablished(val route: MeshRoute) : MeshRouterAction
    data class Dropped(val reason: String) : MeshRouterAction
}

/**
 * Bounded route discovery and opaque payload forwarding for LINKA.
 *
 * The caller MUST encrypt OPAQUE_DATA end-to-end before calling sendOpaqueData.
 * Each hop's envelope is transported inside the already authenticated,
 * encrypted direct-neighbour control channel. This router never decrypts or
 * modifies the opaque payload.
 */
class MeshPacketRouter(
    private val localDeviceId: String,
    private val relayEnabled: () -> Boolean,
    private val signOrigin: (ByteArray) -> ByteArray,
    private val verifyOrigin: (sourceDeviceId: String, body: ByteArray, signature: ByteArray) -> Boolean
) {
    private data class ReversePath(
        val endpointId: String,
        val originDeviceId: String,
        val destinationDeviceId: String,
        val hopCount: Int,
        val expiresAt: Long
    )

    private val neighbors = mutableMapOf<String, MeshNeighbor>()
    private val routes = mutableMapOf<String, MeshRoute>()
    private val reversePaths = mutableMapOf<String, ReversePath>()
    private val seenRequests = mutableMapOf<String, Long>()
    private val seenReplies = mutableMapOf<String, Long>()
    private val pendingDestinations = mutableMapOf<String, String>()
    private val pendingDiscoveryExpiry = mutableMapOf<String, Long>()
    private val seenData = mutableMapOf<String, Long>()

    init {
        require(localDeviceId.isNotBlank() && localDeviceId.length <= MAX_ID_BYTES)
    }

    @Synchronized
    fun registerNeighbor(
        endpointId: String,
        deviceId: String,
        relayOptIn: Boolean,
        nowEpochMs: Long = System.currentTimeMillis()
    ) {
        require(endpointId.isNotBlank() && deviceId.isNotBlank() && deviceId != localDeviceId)
        neighbors[endpointId] = MeshNeighbor(endpointId, deviceId, relayOptIn)
        routes[deviceId] = MeshRoute(
            destinationDeviceId = deviceId,
            nextHopEndpointId = endpointId,
            nextHopDeviceId = deviceId,
            hopCount = 1,
            expiresAtEpochMs = nowEpochMs + DIRECT_ROUTE_TTL_MS
        )
    }

    @Synchronized
    fun unregisterNeighbor(endpointId: String) {
        neighbors.remove(endpointId)
        routes.entries.removeAll { it.value.nextHopEndpointId == endpointId }
        reversePaths.entries.removeAll { it.value.endpointId == endpointId }
    }

    @Synchronized
    fun clearRoutesAndPendingDiscovery() {
        routes.clear()
        reversePaths.clear()
        seenRequests.clear()
        seenReplies.clear()
        pendingDestinations.clear()
        pendingDiscoveryExpiry.clear()
        seenData.clear()
    }

    @Synchronized
    fun routeTo(destinationDeviceId: String, nowEpochMs: Long = System.currentTimeMillis()): MeshRoute? {
        prune(nowEpochMs)
        if (destinationDeviceId == localDeviceId) return null
        return routes[destinationDeviceId]
    }

    @Synchronized
    fun startRouteDiscovery(
        destinationDeviceId: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): List<MeshRouterAction> {
        require(destinationDeviceId.isNotBlank() && destinationDeviceId != localDeviceId)
        prune(nowEpochMs)
        val existing = routes[destinationDeviceId]
        if (existing != null) return listOf(MeshRouterAction.RouteEstablished(existing))

        val requestId = UUID.randomUUID().toString()
        val unsigned = MeshPacket(
            type = MeshPacketType.ROUTE_REQUEST,
            packetId = requestId,
            requestId = requestId,
            sourceDeviceId = localDeviceId,
            destinationDeviceId = destinationDeviceId,
            hopLimit = MAX_HOPS,
            hopCount = 0,
            expiresAtEpochMs = nowEpochMs + ROUTE_REQUEST_TTL_MS,
            payload = ByteArray(0),
            signature = ByteArray(0)
        )
        val packet = sign(unsigned)
        seenRequests[requestId] = packet.expiresAtEpochMs
        pendingDestinations[requestId] = destinationDeviceId
        pendingDiscoveryExpiry[requestId] = packet.expiresAtEpochMs

        val targets = neighbors.values.filter {
            it.relayOptIn || it.deviceId == destinationDeviceId
        }
        if (targets.isEmpty()) {
            return listOf(MeshRouterAction.Dropped("No connected relay-capable neighbours are available"))
        }
        return targets.map { MeshRouterAction.SendTo(it.endpointId, MeshPacketCodec.encode(packet)) }
    }

    @Synchronized
    fun sendOpaqueData(
        destinationDeviceId: String,
        encryptedPayload: ByteArray,
        nowEpochMs: Long = System.currentTimeMillis()
    ): List<MeshRouterAction> {
        prune(nowEpochMs)
        if (encryptedPayload.isEmpty() || encryptedPayload.size > MeshPacketCodec.MAX_PAYLOAD_BYTES) {
            return listOf(MeshRouterAction.Dropped("Encrypted mesh payload size is invalid"))
        }
        val route = routes[destinationDeviceId]
            ?: return listOf(MeshRouterAction.Dropped("No route to destination; discover a route first"))
        val refreshedRoute = route.copy(expiresAtEpochMs = nowEpochMs + ROUTE_TTL_MS)
        routes[destinationDeviceId] = refreshedRoute
        val neighbor = neighbors[refreshedRoute.nextHopEndpointId]
            ?: return listOf(MeshRouterAction.Dropped("The selected next hop is no longer connected"))
        if (neighbor.deviceId != destinationDeviceId && !neighbor.relayOptIn) {
            return listOf(MeshRouterAction.Dropped("The selected next hop has not opted in to relaying"))
        }

        val unsigned = MeshPacket(
            type = MeshPacketType.OPAQUE_DATA,
            packetId = UUID.randomUUID().toString(),
            requestId = "",
            sourceDeviceId = localDeviceId,
            destinationDeviceId = destinationDeviceId,
            hopLimit = MAX_HOPS,
            hopCount = 0,
            expiresAtEpochMs = nowEpochMs + DATA_PACKET_TTL_MS,
            payload = encryptedPayload.copyOf(),
            signature = ByteArray(0)
        )
        // The OPAQUE_DATA body is already end-to-end authenticated by the
        // caller/recipient handshake and AES-GCM. Avoid a Keystore ECDSA
        // operation for every 20 ms voice frame.
        return listOf(MeshRouterAction.SendTo(route.nextHopEndpointId, MeshPacketCodec.encode(unsigned)))
    }

    @Synchronized
    fun receive(
        encodedPacket: ByteArray,
        ingressEndpointId: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): List<MeshRouterAction> {
        prune(nowEpochMs)
        val ingress = neighbors[ingressEndpointId]
            ?: return listOf(MeshRouterAction.Dropped("Packet arrived on an unauthenticated neighbour link"))
        val packet = try {
            MeshPacketCodec.decode(encodedPacket)
        } catch (error: Exception) {
            return listOf(MeshRouterAction.Dropped(error.localizedMessage ?: "Malformed mesh packet"))
        }

        if (packet.expiresAtEpochMs <= nowEpochMs) {
            return listOf(MeshRouterAction.Dropped("Expired mesh packet"))
        }
        if (packet.expiresAtEpochMs - nowEpochMs > MAX_PACKET_TTL_MS) {
            return listOf(MeshRouterAction.Dropped("Mesh packet expiry exceeds the protocol limit"))
        }
        if (packet.sourceDeviceId == localDeviceId) {
            return listOf(MeshRouterAction.Dropped("Looped packet from local device"))
        }
        if (packet.type != MeshPacketType.OPAQUE_DATA &&
            !verifyOrigin(packet.sourceDeviceId, MeshPacketCodec.signingBytes(packet), packet.signature)
        ) {
            return listOf(MeshRouterAction.Dropped("Mesh packet origin signature is invalid or untrusted"))
        }

        return when (packet.type) {
            MeshPacketType.ROUTE_REQUEST -> receiveRouteRequest(packet, ingress, nowEpochMs)
            MeshPacketType.ROUTE_REPLY -> receiveRouteReply(packet, ingress, nowEpochMs)
            MeshPacketType.OPAQUE_DATA -> receiveOpaqueData(packet, ingress, nowEpochMs)
        }
    }

    private fun receiveRouteRequest(
        packet: MeshPacket,
        ingress: MeshNeighbor,
        nowEpochMs: Long
    ): List<MeshRouterAction> {
        if (packet.payload.isNotEmpty() || packet.requestId.isBlank()) {
            return listOf(MeshRouterAction.Dropped("Invalid route request fields"))
        }
        val previousExpiry = seenRequests[packet.requestId]
        if (previousExpiry != null && previousExpiry > nowEpochMs) {
            return listOf(MeshRouterAction.Dropped("Duplicate route request suppressed"))
        }
        seenRequests[packet.requestId] = packet.expiresAtEpochMs
        if (packet.destinationDeviceId != localDeviceId &&
            (packet.hopCount >= MAX_HOPS || packet.hopLimit <= 1)
        ) {
            return listOf(MeshRouterAction.Dropped("Route request exceeded the maximum hop count"))
        }
        reversePaths[packet.requestId] = ReversePath(
            endpointId = ingress.endpointId,
            originDeviceId = packet.sourceDeviceId,
            destinationDeviceId = packet.destinationDeviceId,
            hopCount = packet.hopCount + 1,
            expiresAt = nowEpochMs + ROUTE_TTL_MS
        )
        // Every relay learns the reverse route to the request origin. This
        // enables recipient-to-caller media on chains with multiple relays.
        routes[packet.sourceDeviceId] = MeshRoute(
            destinationDeviceId = packet.sourceDeviceId,
            nextHopEndpointId = ingress.endpointId,
            nextHopDeviceId = ingress.deviceId,
            hopCount = (packet.hopCount + 1).coerceAtMost(MAX_HOPS),
            expiresAtEpochMs = nowEpochMs + ROUTE_TTL_MS
        )

        if (packet.destinationDeviceId == localDeviceId) {
            val directRoute = MeshRoute(
                destinationDeviceId = packet.sourceDeviceId,
                nextHopEndpointId = ingress.endpointId,
                nextHopDeviceId = ingress.deviceId,
                hopCount = packet.hopCount + 1,
                expiresAtEpochMs = nowEpochMs + ROUTE_TTL_MS
            )
            routes[packet.sourceDeviceId] = directRoute
            val reply = sign(
                MeshPacket(
                    type = MeshPacketType.ROUTE_REPLY,
                    packetId = UUID.randomUUID().toString(),
                    requestId = packet.requestId,
                    sourceDeviceId = localDeviceId,
                    destinationDeviceId = packet.sourceDeviceId,
                    hopLimit = MAX_HOPS,
                    hopCount = 0,
                    expiresAtEpochMs = nowEpochMs + ROUTE_REQUEST_TTL_MS,
                    payload = ByteArray(0),
                    signature = ByteArray(0)
                )
            )
            return listOf(MeshRouterAction.SendTo(ingress.endpointId, MeshPacketCodec.encode(reply)))
        }

        if (!relayEnabled() || packet.hopLimit <= 1) {
            return listOf(MeshRouterAction.Dropped("This phone is not enabled to relay route requests"))
        }

        val forwarded = packet.copy(
            hopLimit = packet.hopLimit - 1,
            hopCount = packet.hopCount + 1
        )
        val encoded = MeshPacketCodec.encode(forwarded)
        return neighbors.values
            .filter { it.endpointId != ingress.endpointId }
            .filter { it.relayOptIn || it.deviceId == packet.destinationDeviceId }
            .map { MeshRouterAction.SendTo(it.endpointId, encoded) }
            .ifEmpty { listOf(MeshRouterAction.Dropped("No consenting next hop is available")) }
    }

    private fun receiveRouteReply(
        packet: MeshPacket,
        ingress: MeshNeighbor,
        nowEpochMs: Long
    ): List<MeshRouterAction> {
        if (packet.payload.isNotEmpty() || packet.requestId.isBlank()) {
            return listOf(MeshRouterAction.Dropped("Invalid route reply fields"))
        }
        val duplicateExpiry = seenReplies[packet.packetId]
        if (duplicateExpiry != null && duplicateExpiry > nowEpochMs) {
            return listOf(MeshRouterAction.Dropped("Duplicate route reply suppressed"))
        }

        val isFinalDestination = packet.destinationDeviceId == localDeviceId
        val reverse = if (isFinalDestination) {
            val expectedDestination = pendingDestinations[packet.requestId]
            if (expectedDestination == null || expectedDestination != packet.sourceDeviceId) {
                return listOf(MeshRouterAction.Dropped("Route reply does not match an outstanding discovery"))
            }
            null
        } else {
            reversePaths[packet.requestId]
                ?: return listOf(MeshRouterAction.Dropped("No reverse path exists for route reply"))
        }

        if (reverse != null && (
            reverse.expiresAt <= nowEpochMs ||
                reverse.endpointId == ingress.endpointId ||
                reverse.originDeviceId != packet.destinationDeviceId ||
                reverse.destinationDeviceId != packet.sourceDeviceId
            )
        ) {
            return listOf(MeshRouterAction.Dropped("Expired, mismatched or looping reverse route"))
        }

        seenReplies[packet.packetId] = packet.expiresAtEpochMs
        val route = MeshRoute(
            destinationDeviceId = packet.sourceDeviceId,
            nextHopEndpointId = ingress.endpointId,
            nextHopDeviceId = ingress.deviceId,
            hopCount = (packet.hopCount + 1).coerceAtMost(MAX_HOPS),
            expiresAtEpochMs = nowEpochMs + ROUTE_TTL_MS
        )
        routes[packet.sourceDeviceId] = route

        if (isFinalDestination) {
            pendingDestinations.remove(packet.requestId)
            pendingDiscoveryExpiry.remove(packet.requestId)
            return listOf(MeshRouterAction.RouteEstablished(route))
        }

        if (!relayEnabled() || packet.hopLimit <= 1) {
            return listOf(MeshRouterAction.Dropped("This phone cannot relay the route reply"))
        }
        val forwarded = packet.copy(
            hopLimit = packet.hopLimit - 1,
            hopCount = (packet.hopCount + 1).coerceAtMost(MAX_HOPS)
        )
        val returnPath = reverse
            ?: return listOf(MeshRouterAction.Dropped("No reverse route exists for route reply"))
        return listOf(
            MeshRouterAction.SendTo(returnPath.endpointId, MeshPacketCodec.encode(forwarded))
        )
    }

    private fun receiveOpaqueData(
        packet: MeshPacket,
        ingress: MeshNeighbor,
        nowEpochMs: Long
    ): List<MeshRouterAction> {
        if (packet.requestId.isNotEmpty() || packet.payload.isEmpty()) {
            return listOf(MeshRouterAction.Dropped("Invalid opaque data packet fields"))
        }
        val previousExpiry = seenData[packet.packetId]
        if (previousExpiry != null && previousExpiry > nowEpochMs) {
            return listOf(MeshRouterAction.Dropped("Duplicate data packet suppressed"))
        }
        seenData[packet.packetId] = packet.expiresAtEpochMs

        // Refresh the authenticated return route at every hop. Without this,
        // the reverse path would expire with route discovery while the call
        // might still be ringing or carrying live media.
        val returnRoute = routes[packet.sourceDeviceId]
            ?: return listOf(MeshRouterAction.Dropped("No return route exists for the packet origin"))
        if (returnRoute.nextHopEndpointId != ingress.endpointId) {
            return listOf(MeshRouterAction.Dropped("Packet arrived outside its authenticated return path"))
        }
        routes[packet.sourceDeviceId] = returnRoute.copy(
            expiresAtEpochMs = nowEpochMs + ROUTE_TTL_MS
        )

        if (packet.destinationDeviceId == localDeviceId) {
            return listOf(
                MeshRouterAction.DeliverOpaqueData(
                    sourceDeviceId = packet.sourceDeviceId,
                    payload = packet.payload.copyOf()
                )
            )
        }

        if (!relayEnabled() || packet.hopLimit <= 1) {
            return listOf(MeshRouterAction.Dropped("This phone is not enabled to relay data"))
        }
        val route = routes[packet.destinationDeviceId]
            ?: return listOf(MeshRouterAction.Dropped("No route is known for this destination"))
        if (route.nextHopEndpointId == ingress.endpointId) {
            return listOf(MeshRouterAction.Dropped("Mesh forwarding loop detected"))
        }
        val refreshedRoute = route.copy(expiresAtEpochMs = nowEpochMs + ROUTE_TTL_MS)
        routes[packet.destinationDeviceId] = refreshedRoute
        val nextHop = neighbors[refreshedRoute.nextHopEndpointId]
            ?: return listOf(MeshRouterAction.Dropped("The next hop is disconnected"))
        if (nextHop.deviceId != packet.destinationDeviceId && !nextHop.relayOptIn) {
            return listOf(MeshRouterAction.Dropped("The next hop has not opted in to relaying"))
        }

        return listOf(
            MeshRouterAction.SendTo(
                refreshedRoute.nextHopEndpointId,
                MeshPacketCodec.encode(
                    packet.copy(
                        hopLimit = packet.hopLimit - 1,
                        hopCount = (packet.hopCount + 1).coerceAtMost(MAX_HOPS)
                    )
                )
            )
        )
    }

    private fun sign(packet: MeshPacket): MeshPacket {
        val body = MeshPacketCodec.signingBytes(packet)
        return packet.copy(signature = signOrigin(body))
    }

    private fun prune(nowEpochMs: Long) {
        routes.entries.removeAll { it.value.expiresAtEpochMs <= nowEpochMs }
        reversePaths.entries.removeAll { it.value.expiresAt <= nowEpochMs }
        seenRequests.entries.removeAll { it.value <= nowEpochMs }
        seenReplies.entries.removeAll { it.value <= nowEpochMs }
        pendingDiscoveryExpiry.entries.removeAll { entry ->
            if (entry.value <= nowEpochMs) {
                pendingDestinations.remove(entry.key)
                true
            } else false
        }
        seenData.entries.removeAll { it.value <= nowEpochMs }
        trimMap(seenRequests, MAX_SEEN_IDS)
        trimMap(seenReplies, MAX_SEEN_IDS)
        trimMap(pendingDestinations, MAX_SEEN_IDS)
        trimMap(pendingDiscoveryExpiry, MAX_SEEN_IDS)
        trimMap(seenData, MAX_SEEN_IDS)
        trimMap(reversePaths, MAX_SEEN_IDS)
        trimMap(routes, MAX_ROUTE_ENTRIES)
    }

    private fun <K, V> trimMap(map: MutableMap<K, V>, maxSize: Int) {
        while (map.size > maxSize) {
            map.keys.firstOrNull()?.let(map::remove) ?: return
        }
    }

    companion object {
        const val MAX_HOPS = 8
        private const val ROUTE_REQUEST_TTL_MS = 15_000L
        private const val ROUTE_TTL_MS = 60_000L
        private const val DIRECT_ROUTE_TTL_MS = 24 * 60 * 60 * 1_000L
        private const val DATA_PACKET_TTL_MS = 5_000L
        private const val MAX_PACKET_TTL_MS = 60_000L
        private const val MAX_ID_BYTES = 64
        private const val MAX_SEEN_IDS = 2_048
        private const val MAX_ROUTE_ENTRIES = 1_024
    }
}

object MeshPacketCodec {
    const val MAX_PACKET_BYTES = 1024
    const val MAX_PAYLOAD_BYTES = 700

    private const val MAGIC = 0x4C4E4B50 // "LNKP"
    private const val VERSION = 1
    private const val SIGNATURE_MAX_BYTES = 96
    private const val SIGNATURE_MIN_BYTES = 64
    private const val MAX_ID_BYTES = 64
    private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun isMeshPacket(bytes: ByteArray): Boolean =
        bytes.size >= 4 && ByteBuffer.wrap(bytes, 0, 4).int == MAGIC

    fun encode(packet: MeshPacket): ByteArray {
        validatePacket(packet)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeByte(VERSION)
            output.writeByte(packet.type.wireValue)
            output.writeByte(packet.hopLimit)
            output.writeByte(packet.hopCount)
            output.writeLong(packet.expiresAtEpochMs)
            writeString(output, packet.packetId, MAX_ID_BYTES, allowEmpty = false)
            writeString(output, packet.requestId, MAX_ID_BYTES, allowEmpty = true)
            writeString(output, packet.sourceDeviceId, MAX_ID_BYTES, allowEmpty = false)
            writeString(output, packet.destinationDeviceId, MAX_ID_BYTES, allowEmpty = false)
            output.writeShort(packet.payload.size)
            output.write(packet.payload)
            writeSignature(output, packet.type, packet.signature)
        }
        return bytes.toByteArray().also {
            require(it.size <= MAX_PACKET_BYTES) { "Mesh packet exceeds transport limit" }
        }
    }

    fun decode(bytes: ByteArray): MeshPacket {
        require(bytes.size in 20..MAX_PACKET_BYTES) { "Mesh packet size is invalid" }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC) { "Mesh packet magic is invalid" }
        require(input.readUnsignedByte() == VERSION) { "Mesh packet version is unsupported" }
        val type = MeshPacketType.fromWire(input.readUnsignedByte())
        val hopLimit = input.readUnsignedByte()
        val hopCount = input.readUnsignedByte()
        val expiresAt = input.readLong()
        val packetId = readString(input, MAX_ID_BYTES, allowEmpty = false)
        val requestId = readString(input, MAX_ID_BYTES, allowEmpty = true)
        val source = readString(input, MAX_ID_BYTES, allowEmpty = false)
        val destination = readString(input, MAX_ID_BYTES, allowEmpty = false)
        val payloadLength = input.readUnsignedShort()
        require(payloadLength <= MAX_PAYLOAD_BYTES && payloadLength <= input.available()) {
            "Mesh payload length is invalid"
        }
        val payload = ByteArray(payloadLength).also(input::readFully)
        val signature = readSignature(input, type)
        require(input.available() == 0) { "Mesh packet contains trailing bytes" }

        val packet = MeshPacket(
            type, packetId, requestId, source, destination,
            hopLimit, hopCount, expiresAt, payload, signature
        )
        validatePacket(packet)
        return packet
    }

    fun signingBytes(packet: MeshPacket): ByteArray {
        validatePacket(packet, requireSignature = false)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.write("LINKA-MESH-PACKET-v1".toByteArray(StandardCharsets.UTF_8))
            output.writeByte(packet.type.wireValue)
            writeString(output, packet.packetId, MAX_ID_BYTES, allowEmpty = false)
            writeString(output, packet.requestId, MAX_ID_BYTES, allowEmpty = true)
            writeString(output, packet.sourceDeviceId, MAX_ID_BYTES, allowEmpty = false)
            writeString(output, packet.destinationDeviceId, MAX_ID_BYTES, allowEmpty = false)
            output.writeLong(packet.expiresAtEpochMs)
            output.write(MessageDigest.getInstance("SHA-256").digest(packet.payload))
        }
        return bytes.toByteArray()
    }

    private fun validatePacket(packet: MeshPacket, requireSignature: Boolean = true) {
        require(packet.packetId.matches(UUID_PATTERN)) { "Mesh packet ID must be a UUID" }
        require(packet.sourceDeviceId.matches(UUID_PATTERN)) { "Mesh source device ID must be a UUID" }
        require(packet.destinationDeviceId.matches(UUID_PATTERN)) { "Mesh destination device ID must be a UUID" }
        require(packet.requestId.isEmpty() || packet.requestId.matches(UUID_PATTERN)) {
            "Mesh request ID must be empty or a UUID"
        }
        require(packet.hopLimit in 0..MeshPacketRouter.MAX_HOPS)
        require(packet.hopCount in 0..MeshPacketRouter.MAX_HOPS)
        require(packet.hopLimit + packet.hopCount == MeshPacketRouter.MAX_HOPS) {
            "Mesh hop budget was altered or exceeded"
        }
        require(packet.expiresAtEpochMs > 0)
        require(packet.payload.size <= MAX_PAYLOAD_BYTES)
        if (requireSignature) {
            when (packet.type) {
                MeshPacketType.ROUTE_REQUEST, MeshPacketType.ROUTE_REPLY ->
                    require(packet.signature.size in SIGNATURE_MIN_BYTES..SIGNATURE_MAX_BYTES) {
                        "Route-control packet signature length is invalid"
                    }
                MeshPacketType.OPAQUE_DATA ->
                    require(packet.signature.isEmpty()) {
                        "Opaque data uses end-to-end AEAD and must not carry a per-frame origin signature"
                    }
            }
        }
        when (packet.type) {
            MeshPacketType.ROUTE_REQUEST, MeshPacketType.ROUTE_REPLY ->
                require(packet.payload.isEmpty() && packet.requestId.isNotBlank())
            MeshPacketType.OPAQUE_DATA ->
                require(packet.payload.isNotEmpty() && packet.requestId.isEmpty())
        }
    }

    private fun writeString(output: DataOutputStream, value: String, maxBytes: Int, allowEmpty: Boolean) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= maxBytes && (allowEmpty || encoded.isNotEmpty()))
        output.writeByte(encoded.size)
        output.write(encoded)
    }

    private fun readString(input: DataInputStream, maxBytes: Int, allowEmpty: Boolean): String {
        val length = input.readUnsignedByte()
        require(length <= maxBytes && (allowEmpty || length > 0))
        val bytes = ByteArray(length)
        if (length > 0) input.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun writeSignature(
        output: DataOutputStream,
        type: MeshPacketType,
        signature: ByteArray
    ) {
        if (type == MeshPacketType.OPAQUE_DATA) {
            require(signature.isEmpty())
            output.writeByte(0)
        } else {
            writeBytes(output, signature, SIGNATURE_MAX_BYTES)
        }
    }

    private fun readSignature(input: DataInputStream, type: MeshPacketType): ByteArray {
        val length = input.readUnsignedByte()
        if (type == MeshPacketType.OPAQUE_DATA) {
            require(length == 0) { "Opaque data packet must not carry an origin signature" }
            return ByteArray(0)
        }
        require(length in SIGNATURE_MIN_BYTES..SIGNATURE_MAX_BYTES) {
            "Route-control packet signature length is invalid"
        }
        return ByteArray(length).also(input::readFully)
    }

    private fun writeBytes(output: DataOutputStream, value: ByteArray, maxBytes: Int) {
        require(value.size in SIGNATURE_MIN_BYTES..maxBytes)
        output.writeByte(value.size)
        output.write(value)
    }

    private fun readBytes(input: DataInputStream, maxBytes: Int): ByteArray {
        val length = input.readUnsignedByte()
        require(length in SIGNATURE_MIN_BYTES..maxBytes)
        return ByteArray(length).also(input::readFully)
    }
}
