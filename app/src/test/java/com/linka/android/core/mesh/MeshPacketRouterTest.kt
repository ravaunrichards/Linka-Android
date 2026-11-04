package com.linka.android.core.mesh

import org.junit.Assert.assertArrayEquals
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
        val result = relayRouter.receive(request.packet, "relay-caller").single()
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
        val packet = MeshPacketCodec.decode(signed.packet)
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

        val unknownIngress = relayRouter.receive(signed.packet, "not-a-neighbor").single()
            as MeshRouterAction.Dropped
        assertTrue(unknownIngress.reason.contains("unauthenticated"))
    }
}
