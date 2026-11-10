package com.linka.android.account

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class LinkaAccountClientTest {
    @Test
    fun restoreSessionReturnsProfileAndUsesStoredBearerToken() = runBlocking {
        val store = FakeTokenStore(token = "persisted-account-token")
        val transport = FakeTransport(profileResponse())
        val client = client(store, transport)

        val profile = client.restoreSession()

        assertEquals("account-123", profile?.accountId)
        assertEquals("user@example.com", profile?.email)
        assertTrue(client.hasLocalSession())
        val call = transport.calls.single()
        assertEquals("GET", call.method)
        assertEquals("/v1/me", call.path)
        assertEquals("persisted-account-token", call.bearerToken)
    }

    @Test
    fun restoreSessionClearsTokenWhenApiReturnsUnauthorized() = runBlocking {
        val store = FakeTokenStore(token = "revoked-token")
        val transport = FakeTransport().apply {
            failure = LinkaAccountException("session_revoked", "Session revoked.", 401)
        }
        val client = client(store, transport)

        assertThrows(LinkaAccountException::class.java) {
            runBlocking { client.restoreSession() }
        }

        assertNull(store.token)
        assertEquals(1, store.clearCount)
        assertFalse(client.hasLocalSession())
    }

    @Test
    fun restoreSessionPreservesTokenWhenNetworkIsUnavailable() = runBlocking {
        val store = FakeTokenStore(token = "valid-token")
        val transport = FakeTransport().apply {
            failure = LinkaAccountException("network_unavailable", "Network unavailable.")
        }
        val client = client(store, transport)

        assertThrows(LinkaAccountException::class.java) {
            runBlocking { client.restoreSession() }
        }

        assertEquals("valid-token", store.token)
        assertEquals(0, store.clearCount)
    }

    @Test
    fun restoreSessionPreservesTokenForServerErrors() = runBlocking {
        val store = FakeTokenStore(token = "valid-token")
        val transport = FakeTransport().apply {
            failure = LinkaAccountException("server_error", "Try again later.", 503)
        }
        val client = client(store, transport)

        assertThrows(LinkaAccountException::class.java) {
            runBlocking { client.restoreSession() }
        }

        assertEquals("valid-token", store.token)
        assertEquals(0, store.clearCount)
    }

    @Test
    fun logoutClearsTokenOnlyAfterServerRevocationSucceeds() = runBlocking {
        val store = FakeTokenStore(token = "valid-token")
        val transport = FakeTransport(JSONObject())
        val client = client(store, transport)

        client.logout()

        assertNull(store.token)
        val call = transport.calls.single()
        assertEquals("POST", call.method)
        assertEquals("/v1/auth/logout", call.path)
        assertEquals("valid-token", call.bearerToken)
    }

    @Test
    fun logoutPreservesTokenWhenServerCannotRevokeSession() = runBlocking {
        val store = FakeTokenStore(token = "valid-token")
        val transport = FakeTransport().apply {
            failure = LinkaAccountException("server_unavailable", "Try again later.", 503)
        }
        val client = client(store, transport)

        assertThrows(LinkaAccountException::class.java) {
            runBlocking { client.logout() }
        }

        assertEquals("valid-token", store.token)
        assertEquals(0, store.clearCount)
    }

    @Test
    fun acknowledgeInternalMessagesPostsPerDeviceReadReceiptsWithAccountToken() = runBlocking {
        val store = FakeTokenStore(token = "valid-account-token")
        val transport = FakeTransport(JSONObject().put("acknowledged", 2))
        val client = client(store, transport)

        val response = client.acknowledgeInternalMessages(
            conversationId = "conversation-123",
            messageIds = listOf("message-1", "message-2"),
            state = "read"
        )

        assertEquals(2, response.getInt("acknowledged"))
        val call = transport.calls.single()
        assertEquals("POST", call.method)
        assertEquals("/v1/conversations/conversation-123/receipts", call.path)
        assertEquals("valid-account-token", call.bearerToken)
        val body = JSONObject(call.body ?: error("Receipt body was not sent"))
        assertEquals("read", body.getString("state"))
        assertEquals(2, body.getJSONArray("messageIds").length())
        assertEquals("message-1", body.getJSONArray("messageIds").getString(0))
    }

    @Test
    fun listsAccountDevicesUsingTheCurrentAccountToken() = runBlocking {
        val store = FakeTokenStore(token = "valid-account-token")
        val devices = org.json.JSONArray()
            .put(
                JSONObject()
                    .put("deviceId", "device-current")
                    .put("platform", "android")
                    .put("deviceName", "Current phone")
                    .put("createdAt", "2026-11-10T10:00:00.000Z")
                    .put("lastSeenAt", "2026-11-10T11:00:00.000Z")
                    .put("status", "active")
                    .put("activeSessionCount", 1)
                    .put("current", true)
            )
            .put(
                JSONObject()
                    .put("deviceId", "device-old")
                    .put("platform", "web")
                    .put("deviceName", "Old browser")
                    .put("createdAt", "2026-11-09T10:00:00.000Z")
                    .put("lastSeenAt", JSONObject.NULL)
                    .put("revokedAt", JSONObject.NULL)
                    .put("status", "active")
                    .put("activeSessionCount", 1)
                    .put("current", false)
            )
        val transport = FakeTransport(JSONObject().put("devices", devices))
        val client = client(store, transport)

        val result = client.listAccountDevices()

        assertEquals(2, result.size)
        assertEquals("Current phone", result[0].deviceName)
        assertTrue(result[0].current)
        assertEquals("active", result[1].status)
        assertEquals("Old browser", result[1].deviceName)
        val call = transport.calls.single()
        assertEquals("GET", call.method)
        assertEquals("/v1/me/devices", call.path)
        assertEquals("valid-account-token", call.bearerToken)
    }

    @Test
    fun revokesOnlyTheSelectedDeviceAfterUsingAccountAuthentication() = runBlocking {
        val store = FakeTokenStore(token = "valid-account-token")
        val transport = FakeTransport(JSONObject().put("status", "revoked"))
        val client = client(store, transport)

        val response = client.revokeAccountDevice("device-to-revoke")

        assertEquals("revoked", response.getString("status"))
        val call = transport.calls.single()
        assertEquals("DELETE", call.method)
        assertEquals("/v1/me/devices/device-to-revoke", call.path)
        assertNull(call.body)
        assertEquals("valid-account-token", call.bearerToken)
    }

    @Test
    fun revokingBlankDeviceIdDoesNotMakeANetworkRequest() = runBlocking {
        val store = FakeTokenStore(token = "valid-account-token")
        val transport = FakeTransport()
        val client = client(store, transport)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.revokeAccountDevice("  ") }
        }
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun readsSeparateWalletBalancesWithTheAuthenticatedAccountToken() = runBlocking {
        val store = FakeTokenStore(token = "valid-account-token")
        val transport = FakeTransport(
            JSONObject().put(
                "wallet",
                JSONObject()
                    .put("purchased", 120)
                    .put("promotional", 25)
                    .put("sponsored", 5)
                    .put("total", 150)
                    .put("version", 3)
            )
        )
        val client = client(store, transport)

        val wallet = client.getWalletSnapshot()

        assertEquals(120L, wallet.purchased)
        assertEquals(25L, wallet.promotional)
        assertEquals(5L, wallet.sponsored)
        assertEquals(150L, wallet.total)
        assertEquals(3L, wallet.version)
        val call = transport.calls.single()
        assertEquals("GET", call.method)
        assertEquals("/v1/wallet", call.path)
        assertEquals("valid-account-token", call.bearerToken)
    }

    @Test
    fun readsWalletLedgerWithStableCursorPagination() = runBlocking {
        val store = FakeTokenStore(token = "valid-account-token")
        val transport = FakeTransport(
            JSONObject()
                .put(
                    "events",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("eventId", "event-1")
                            .put("eventType", "PURCHASE_CONFIRMED")
                            .put("creditSource", "purchased")
                            .put("amountCreditUnits", 100)
                            .put("createdAt", "2026-11-10T10:00:00.000Z")
                    )
                )
                .put(
                    "nextCursor",
                    JSONObject()
                        .put("before", "2026-11-10T10:00:00.000123Z")
                        .put("beforeEventId", "event-1")
                )
        )
        val client = client(store, transport)

        val page = client.listWalletLedgerEvents(
            limit = 10,
            cursor = LinkaWalletCursor("2026-11-10T10:01:00.000123Z", "event-2")
        )

        assertEquals(1, page.events.size)
        assertEquals("event-1", page.events.single().eventId)
        assertEquals(100L, page.events.single().amountCreditUnits)
        assertEquals("event-1", page.nextCursor?.beforeEventId)
        val call = transport.calls.single()
        assertEquals("GET", call.method)
        assertTrue(call.path.startsWith("/v1/wallet/ledger?limit=10&before="))
        assertTrue(call.path.contains("beforeEventId=event-2"))
        assertEquals("valid-account-token", call.bearerToken)
    }

    @Test
    fun rejectsInvalidWalletPageSizesBeforeNetworkRequest() = runBlocking {
        val store = FakeTokenStore(token = "valid-account-token")
        val transport = FakeTransport()
        val client = client(store, transport)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.listWalletLedgerEvents(limit = 101) }
        }
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun restoreSessionWithoutTokenDoesNotMakeNetworkRequest() = runBlocking {
        val store = FakeTokenStore(token = null)
        val transport = FakeTransport()
        val client = client(store, transport)

        assertNull(client.restoreSession())
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun logoutWithoutLocalSessionDoesNotMakeNetworkRequest() = runBlocking {
        val store = FakeTokenStore(token = null)
        val transport = FakeTransport()
        val client = client(store, transport)

        client.logout()

        assertTrue(transport.calls.isEmpty())
        assertEquals(0, store.clearCount)
    }

    @Test
    fun apiOriginRequiresHttpsExceptForLocalDevelopment() {
        val store = FakeTokenStore(token = null)
        val client = client(store, FakeTransport())

        assertThrows(LinkaAccountException::class.java) {
            client.setServerUrl("http://api.linka.test")
        }
        assertThrows(LinkaAccountException::class.java) {
            client.setServerUrl("https://api.linka.test/v1")
        }
        assertThrows(LinkaAccountException::class.java) {
            client.setServerUrl("https://api.linka.test?debug=true")
        }

        client.setServerUrl("https://api.linka.test/")
        assertEquals("https://api.linka.test", client.serverUrl())

        client.setServerUrl("http://localhost:8080")
        assertEquals("http://localhost:8080", client.serverUrl())
    }

    private fun client(store: FakeTokenStore, transport: FakeTransport) =
        LinkaAccountClient(store, FakeIdentity(), transport)

    private fun profileResponse() = JSONObject()
        .put("accountId", "account-123")
        .put("email", "user@example.com")
        .put("displayName", "LINKA User")
        .put("emailVerified", false)
        .put("deviceId", "device-123")
        .put("deviceFingerprint", "fingerprint-123")
        .put("expiresAt", "2026-12-01T00:00:00.000Z")

    private class FakeTokenStore(
        var token: String?,
        private var baseUrl: String = "https://api.linka.test"
    ) : AccountTokenStore {
        var clearCount = 0

        override fun getServerUrl(): String = baseUrl
        override fun saveServerUrl(value: String) { baseUrl = value }
        override fun saveToken(token: String) { this.token = token }
        override fun readToken(): String? = token
        override fun clearToken() {
            token = null
            clearCount += 1
        }
    }

    private class FakeIdentity : AccountDeviceIdentity {
        override val deviceId = "device-123"
        override val publicKeyBase64 = "cHVibGljLWtleQ=="
        override val publicKeyFingerprint = "fingerprint-123"
        override fun sign(data: ByteArray): ByteArray = byteArrayOf(1, 2, 3)
    }

    private data class ApiCall(
        val method: String,
        val path: String,
        val body: String?,
        val bearerToken: String?
    )

    private class FakeTransport(
        private val response: JSONObject = JSONObject()
    ) : AccountApiTransport {
        val calls = Collections.synchronizedList(mutableListOf<ApiCall>())
        var failure: LinkaAccountException? = null

        override fun execute(
            baseUrl: String,
            method: String,
            path: String,
            body: JSONObject?,
            bearerToken: String?
        ): JSONObject {
            calls += ApiCall(method, path, body?.toString(), bearerToken)
            failure?.let { throw it }
            return JSONObject(response.toString())
        }
    }
}
