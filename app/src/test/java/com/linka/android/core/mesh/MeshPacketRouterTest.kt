package com.linka.android.core.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

class MeshPacketRouterTest {
    private data class Identity(val deviceId: String, val keyPair: KeyPair) {
        fun sign(body: ByteArray): ByteArray =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(body)
                sign()
            }

        fun verify(body: ByteArray, signature: ByteArray): Boolean =
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(keyPair.public)
                update(body)
                verify(signature)
            }
    }

    private fun identity(): Identity {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return Identity(UUID.randomUUID().toString(), generator.generateKeyPair())
    }

    private fun router(
        local: Identity,
        identities: Map<String, Identity>,
        relayEnabled: Boolean
    ) = MeshPacketRouter(
        localDeviceId = local.deviceId,
        relayEnabled = { relayEnabled },
        signOrigin = local::sign,
        verifyOrigin = { source, body, signature ->
            identities[source]?.verify(body, signature) == true
        }
    )

    @Test
    fun discoversRouteAndForwardsOpaquePayloadAcrossOneConsentingRelay() {
        val caller = identity()
        val relay = identity()
        val recipient = identity()
        val identities = listOf(caller, relay, recipient).associateBy { it.deviceId }

        val callerRouter = router(caller, identities, relayEnabled = false)
        val relayRouter = router(relay, identities, relayEnabled = true)
        val recipientRouter = router(recipient, identities, relayEnabled = false)

        callerRouter.registerNeighbor("caller-to-relay", relay.deviceId, relayOptIn = true)
        relayRouter.registerNeighbor("relay-to-caller", caller.deviceId, relayOptIn = false)
        relayRouter.registerNeighbor("relay-to-recipient", recipient.deviceId, relayOptIn = false)
        recipientRouter.registerNeighbor("recipient-to-relay", relay.deviceId, relayOptIn = true)

        val request = callerRouter.startRouteDiscovery(recipient.deviceId).single()
            as MeshRouterAction.SendTo
        assertEquals("caller-to-relay", request.endpointId)

        val forwardedRequest = relayRouter.receive(request.packet, "relay-to-caller").single()
            as MeshRouterAction.SendTo
        assertEquals("relay-to-recipient", forwardedRequest.endpointId)

        val reply = recipientRouter.receive(
            forwardedRequest.packet,
            "recipient-to-relay"
        ).single() as MeshRouterAction.SendTo
        assertEquals("recipient-to-relay", reply.endpointId)

        val forwardedReply = relayRouter.receive(reply.packet, "relay-to-recipient").single()
            as MeshRouterAction.SendTo
        assertEquals("relay-to-caller", forwardedReply.endpointId)

        val established = callerRouter.receive(
            forwardedReply.packet,
            "caller-to-relay"
        ).single() as MeshRouterAction.RouteEstablished
        assertEquals(recipient.deviceId, established.route.destinationDeviceId)
        assertEquals("caller-to-relay", established.route.nextHopEndpointId)
        assertEquals(2, established.route.hopCount)

        val encryptedPayload = ByteArray(180) { (it * 7).toByte() }
        val outgoing = callerRouter.sendOpaqueData(recipient.deviceId, encryptedPayload).single()
            as MeshRouterAction.SendTo
        assertEquals("caller-to-relay", outgoing.endpointId)

        val relayed = relayRouter.receive(outgoing.packet, "relay-to-caller").single()
            as MeshRouterAction.SendTo
        assertEquals("relay-to-recipient", relayed.endpointId)

        val delivered = recipientRouter.receive(relayed.packet, "recipient-to-relay").single()
            as MeshRouterAction.DeliverOpaqueData
        assertEquals(caller.deviceId, delivered.sourceDeviceId)
        assertArrayEquals(encryptedPayload, delivered.payload)
    }


    @Test
    fun discoversAndForwardsBidirectionallyAcrossTwoRelayPhones() {
        val caller = identity()
        val relayA = identity()
        val relayB = identity()
        val recipient = identity()
        val identities = listOf(caller, relayA, relayB, recipient).associateBy { it.deviceId }

        val callerRouter = router(caller, identities, relayEnabled = false)
        val relayARouter = router(relayA, identities, relayEnabled = true)
        val relayBRouter = router(relayB, identities, relayEnabled = true)
        val recipientRouter = router(recipient, identities, relayEnabled = false)

        callerRouter.registerNeighbor("caller-a", relayA.deviceId, relayOptIn = true)
        relayARouter.registerNeighbor("a-caller", caller.deviceId, relayOptIn = false)
        relayARouter.registerNeighbor("a-b", relayB.deviceId, relayOptIn = true)
        relayBRouter.registerNeighbor("b-a", relayA.deviceId, relayOptIn = true)
        relayBRouter.registerNeighbor("b-recipient", recipient.deviceId, relayOptIn = false)
        recipientRouter.registerNeighbor("recipient-b", relayB.deviceId, relayOptIn = true)

        val request = (callerRouter.startRouteDiscovery(recipient.deviceId).single()
            as MeshRouterAction.SendTo).packet
        val atA = relayARouter.receive(request, "a-caller").single() as MeshRouterAction.SendTo
        assertEquals("a-b", atA.endpointId)
        val atB = relayBRouter.receive(atA.packet, "b-a").single() as MeshRouterAction.SendTo
        assertEquals("b-recipient", atB.endpointId)
        val reply = recipientRouter.receive(atB.packet, "recipient-b").single() as MeshRouterAction.SendTo
        val replyAtB = relayBRouter.receive(reply.packet, "b-recipient").single() as MeshRouterAction.SendTo
        assertEquals("b-a", replyAtB.endpointId)
        val replyAtA = relayARouter.receive(replyAtB.packet, "a-b").single() as MeshRouterAction.SendTo
        assertEquals("a-caller", replyAtA.endpointId)
        val route = callerRouter.receive(replyAtA.packet, "caller-a").single() as MeshRouterAction.RouteEstablished
        assertEquals(3, route.route.hopCount)

        val forwardPayload = ByteArray(144) { (it * 3).toByte() }
        val fromCaller = (callerRouter.sendOpaqueData(recipient.deviceId, forwardPayload).single()
            as MeshRouterAction.SendTo).packet
        val forwardAtA = relayARouter.receive(fromCaller, "a-caller").single() as MeshRouterAction.SendTo
        assertEquals("a-b", forwardAtA.endpointId)
        val forwardAtB = relayBRouter.receive(forwardAtA.packet, "b-a").single() as MeshRouterAction.SendTo
        assertEquals("b-recipient", forwardAtB.endpointId)
        val deliveredToRecipient = recipientRouter.receive(
            forwardAtB.packet,
            "recipient-b"
        ).single() as MeshRouterAction.DeliverOpaqueData
        assertEquals(caller.deviceId, deliveredToRecipient.sourceDeviceId)
        assertArrayEquals(forwardPayload, deliveredToRecipient.payload)

        val returnPayload = ByteArray(144) { (it * 5).toByte() }
        val fromRecipient = (recipientRouter.sendOpaqueData(caller.deviceId, returnPayload).single()
            as MeshRouterAction.SendTo).packet
        val returnAtB = relayBRouter.receive(fromRecipient, "b-recipient").single() as MeshRouterAction.SendTo
        assertEquals("b-a", returnAtB.endpointId)
        val returnAtA = relayARouter.receive(returnAtB.packet, "a-b").single() as MeshRouterAction.SendTo
        assertEquals("a-caller", returnAtA.endpointId)
        val deliveredToCaller = callerRouter.receive(
            returnAtA.packet,
            "caller-a"
        ).single() as MeshRouterAction.DeliverOpaqueData
        assertEquals(recipient.deviceId, deliveredToCaller.sourceDeviceId)
        assertArrayEquals(returnPayload, deliveredToCaller.payload)
    }


    @Test
    fun keepsReturnRouteAliveBeyondRouteDiscoveryRequestTtl() {
        val caller = identity()
        val relay = identity()
        val recipient = identity()
        val identities = listOf(caller, relay, recipient).associateBy { it.deviceId }

        val callerRouter = router(caller, identities, relayEnabled = false)
        val relayRouter = router(relay, identities, relayEnabled = true)
        val recipientRouter = router(recipient, identities, relayEnabled = false)
        callerRouter.registerNeighbor("caller-relay", relay.deviceId, relayOptIn = true, nowEpochMs = 1_000)
        relayRouter.registerNeighbor("relay-caller", caller.deviceId, relayOptIn = false, nowEpochMs = 1_000)
        relayRouter.registerNeighbor("relay-recipient", recipient.deviceId, relayOptIn = false, nowEpochMs = 1_000)
        recipientRouter.registerNeighbor("recipient-relay", relay.deviceId, relayOptIn = true, nowEpochMs = 1_000)

        val request = (callerRouter.startRouteDiscovery(recipient.deviceId, nowEpochMs = 1_000).single()
            as MeshRouterAction.SendTo).packet
        val atRelay = relayRouter.receive(request, "relay-caller", nowEpochMs = 1_001).single()
            as MeshRouterAction.SendTo
        val reply = recipientRouter.receive(atRelay.packet, "recipient-relay", nowEpochMs = 1_002).single()
            as MeshRouterAction.SendTo
        val replyAtRelay = relayRouter.receive(reply.packet, "relay-recipient", nowEpochMs = 1_003).single()
            as MeshRouterAction.SendTo
        callerRouter.receive(replyAtRelay.packet, "caller-relay", nowEpochMs = 1_004).single()

        // The route request expires after 15 seconds, but the installed route
        // must remain active while the caller is ringing/starting the call.
        val forwardPayload = byteArrayOf(1, 2, 3, 4)
        val outgoing = callerRouter.sendOpaqueData(
            recipient.deviceId,
            forwardPayload,
            nowEpochMs = 20_000
        ).single() as MeshRouterAction.SendTo
        val forwarded = relayRouter.receive(
            outgoing.packet,
            "relay-caller",
            nowEpochMs = 20_001
        ).single() as MeshRouterAction.SendTo
        val delivered = recipientRouter.receive(
            forwarded.packet,
            "recipient-relay",
            nowEpochMs = 20_002
        ).single() as MeshRouterAction.DeliverOpaqueData
        assertArrayEquals(forwardPayload, delivered.payload)

        // Return traffic must use the reverse route and refresh it too.
        val returnPayload = byteArrayOf(9, 8, 7, 6)
        val returnPacket = recipientRouter.sendOpaqueData(
            caller.deviceId,
            returnPayload,
            nowEpochMs = 20_003
        ).single() as MeshRouterAction.SendTo
        val returnForwarded = relayRouter.receive(
            returnPacket.packet,
            "relay-recipient",
            nowEpochMs = 20_004
        ).single() as MeshRouterAction.SendTo
        val returned = callerRouter.receive(
            returnForwarded.packet,
            "caller-relay",
            nowEpochMs = 20_005
        ).single() as MeshRouterAction.DeliverOpaqueData
        assertArrayEquals(returnPayload, returned.payload)
    }

    @Test
    fun relayRefusesToForwardWhenRelayParticipationIsDisabled() {
        val caller = identity()
        val relay = identity()
        val recipient = identity()
        val identities = listOf(caller, relay, recipient).associateBy { it.deviceId }

        val callerRouter = router(caller, identities, relayEnabled = false)
        val relayRouter = router(relay, identities, relayEnabled = false)
        callerRouter.registerNeighbor("caller-relay", relay.deviceId, relayOptIn = true)
        relayRouter.registerNeighbor("relay-caller", caller.deviceId, relayOptIn = false)
        relayRouter.registerNeighbor("relay-recipient", recipient.deviceId, relayOptIn = false)

        val request = callerRouter.startRouteDiscovery(recipient.deviceId).single()
            as MeshRouterAction.SendTo
        val result = relayRouter.receive(request, "relay-caller").single()
            as MeshRouterAction.Dropped
        assertTrue(result.reason.contains("not enabled"))
    }

    @Test
    fun duplicateRouteRequestsAreSuppressed() {
        val caller = identity()
        val relay = identity()
        val recipient = identity()
        val identities = listOf(caller, relay, recipient).associateBy { it.deviceId }

        val callerRouter = router(caller, identities, relayEnabled = false)
        val relayRouter = router(relay, identities, relayEnabled = true)
        callerRouter.registerNeighbor("caller-relay", relay.deviceId, relayOptIn = true)
        relayRouter.registerNeighbor("relay-caller", caller.deviceId, relayOptIn = false)
        relayRouter.registerNeighbor("relay-recipient", recipient.deviceId, relayOptIn = false)

        val request = (callerRouter.startRouteDiscovery(recipient.deviceId).single()
            as MeshRouterAction.SendTo).packet
        assertTrue(relayRouter.receive(request, "relay-caller").isNotEmpty())
        val duplicate = relayRouter.receive(request, "relay-caller").single()
            as MeshRouterAction.Dropped
        assertTrue(duplicate.reason.contains("Duplicate"))
    }


    @Test
    fun opaqueVoicePayloadsAvoidPerFrameOriginSignatures() {
        val caller = identity()
        val relay = identity()
        val recipient = identity()
        val identities = listOf(caller, relay, recipient).associateBy { it.deviceId }
        val callerRouter = router(caller, identities, relayEnabled = false)
        val relayRouter = router(relay, identities, relayEnabled = true)
        val recipientRouter = router(recipient, identities, relayEnabled = false)

        callerRouter.registerNeighbor("caller-relay", relay.deviceId, relayOptIn = true)
        relayRouter.registerNeighbor("relay-caller", caller.deviceId, relayOptIn = false)
        relayRouter.registerNeighbor("relay-recipient", recipient.deviceId, relayOptIn = false)
        recipientRouter.registerNeighbor("recipient-relay", relay.deviceId, relayOptIn = true)

        val request = (callerRouter.startRouteDiscovery(recipient.deviceId).single()
            as MeshRouterAction.SendTo).packet
        val forwarded = relayRouter.receive(request, "relay-caller").single()
            as MeshRouterAction.SendTo
        val reply = recipientRouter.receive(forwarded.packet, "recipient-relay").single()
            as MeshRouterAction.SendTo
        val replyAtRelay = relayRouter.receive(reply.packet, "relay-recipient").single()
            as MeshRouterAction.SendTo
        callerRouter.receive(replyAtRelay.packet, "caller-relay").single()

        val frameCiphertext = ByteArray(200) { (it * 9).toByte() }
        val data = (callerRouter.sendOpaqueData(recipient.deviceId, frameCiphertext).single()
            as MeshRouterAction.SendTo).packet
        val decoded = MeshPacketCodec.decode(data)
        assertTrue(decoded.signature.isEmpty())
        val relayed = relayRouter.receive(data, "relay-caller").single()
            as MeshRouterAction.SendTo
        val delivered = recipientRouter.receive(
            relayed.packet,
            "recipient-relay"
        ).single() as MeshRouterAction.DeliverOpaqueData
        assertArrayEquals(frameCiphertext, delivered.payload)
    }

    @Test
    fun rejectsTamperedOriginSignature() {
        val caller = identity()
        val relay = identity()
        val recipient = identity()
        val identities = listOf(caller, relay, recipient).associateBy { it.deviceId }

        val callerRouter = router(caller, identities, relayEnabled = false)
        val relayRouter = router(relay, identities, relayEnabled = true)
        callerRouter.registerNeighbor("caller-relay", relay.deviceId, relayOptIn = true)
        relayRouter.registerNeighbor("relay-caller", caller.deviceId, relayOptIn = false)

        val signed = (callerRouter.startRouteDiscovery(recipient.deviceId).single()
            as MeshRouterAction.SendTo).packet
        val decoded = MeshPacketCodec.decode(signed)
        val tampered = decoded.copy(signature = decoded.signature.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        })
        val result = relayRouter.receive(
            MeshPacketCodec.encode(tampered),
            "relay-caller"
        ).single() as MeshRouterAction.Dropped
        assertTrue(result.reason.contains("signature"))
    }


    @Test
    fun rejectsPacketsWithExcessiveLifetimeAndMalformedIdentifiers() {
        val caller = identity()
        val relay = identity()
        val recipient = identity()
        val identities = listOf(caller, relay, recipient).associateBy { it.deviceId }
        val callerRouter = router(caller, identities, relayEnabled = false)
        val relayRouter = router(relay, identities, relayEnabled = true)
        callerRouter.registerNeighbor("caller-relay", relay.deviceId, relayOptIn = true)
        relayRouter.registerNeighbor("relay-caller", caller.deviceId, relayOptIn = false)

        val now = 1_000L
        val initial = (callerRouter.startRouteDiscovery(recipient.deviceId, now).single()
            as MeshRouterAction.SendTo).packet
        val packet = MeshPacketCodec.decode(initial)
        val excessiveLifetime = packet.copy(
            expiresAtEpochMs = now + 120_000L,
            signature = ByteArray(0)
        )
        val signedLongLived = excessiveLifetime.copy(
            signature = caller.sign(MeshPacketCodec.signingBytes(excessiveLifetime))
        )
        val lifetimeResult = relayRouter.receive(
            MeshPacketCodec.encode(signedLongLived),
            "relay-caller",
            nowEpochMs = now
        ).single() as MeshRouterAction.Dropped
        assertTrue(lifetimeResult.reason.contains("expiry"))

        val malformedId = packet.copy(packetId = "not-a-uuid")
        assertThrows(IllegalArgumentException::class.java) {
            MeshPacketCodec.encode(malformedId)
        }
    }

    @Test
    fun rejectsExpiredPacketsAndUnknownIngressNeighbors() {
        val caller = identity()
        val relay = identity()
        val identities = listOf(caller, relay).associateBy { it.deviceId }
        val callerRouter = router(caller, identities, relayEnabled = false)
        val relayRouter = router(relay, identities, relayEnabled = true)
        callerRouter.registerNeighbor("caller-relay", relay.deviceId, relayOptIn = true)
        relayRouter.registerNeighbor("relay-caller", caller.deviceId, relayOptIn = false)

        val signed = (callerRouter.startRouteDiscovery(UUID.randomUUID().toString()).single()
            as MeshRouterAction.SendTo).packet
        val expires = UUID.randomUUID()
        val packet = MeshPacketCodec.decode(signed)
        val expired = packet.copy(
            packetId = expires.toString(),
            requestId = expires.toString(),
            expiresAtEpochMs = 1L,
            signature = ByteArray(0)
        )
        val signedExpired = expired.copy(signature = caller.sign(MeshPacketCodec.signingBytes(expired)))
        val expiredAction = relayRouter.receive(
            MeshPacketCodec.encode(signedExpired),
            "relay-caller",
            nowEpochMs = 2L
        ).single() as MeshRouterAction.Dropped
        assertTrue(expiredAction.reason.contains("Expired"))

        val unknownIngress = relayRouter.receive(signed, "not-a-neighbor").single()
            as MeshRouterAction.Dropped
        assertTrue(unknownIngress.reason.contains("unauthenticated"))
    }
}
