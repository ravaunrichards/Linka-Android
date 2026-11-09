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
            calls += ApiCall(method, path, bearerToken)
            failure?.let { throw it }
            return JSONObject(response.toString())
        }
    }
}
