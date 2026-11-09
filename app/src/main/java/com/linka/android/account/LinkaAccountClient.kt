package com.linka.android.account

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.linka.android.core.identity.LocalDeviceIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.linka.android.messaging.MessagingDevicePublicKey
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class LinkaAccountProfile(
    val accountId: String,
    val email: String,
    val displayName: String,
    val emailVerified: Boolean,
    val deviceId: String,
    val deviceFingerprint: String,
    val expiresAt: String
)

class LinkaAccountException(
    val errorCode: String,
    message: String,
    val httpStatus: Int? = null
) : Exception(message)

internal interface AccountTokenStore {
    fun getServerUrl(): String
    fun saveServerUrl(value: String)
    fun saveToken(token: String)
    fun readToken(): String?
    fun clearToken()
}

internal interface AccountDeviceIdentity {
    val deviceId: String
    val publicKeyBase64: String
    val publicKeyFingerprint: String
    fun sign(data: ByteArray): ByteArray
}

internal interface AccountApiTransport {
    fun execute(
        baseUrl: String,
        method: String,
        path: String,
        body: JSONObject? = null,
        bearerToken: String? = null
    ): JSONObject
}

private class AndroidAccountDeviceIdentity(
    private val identity: LocalDeviceIdentity
) : AccountDeviceIdentity {
    override val deviceId: String get() = identity.deviceId
    override val publicKeyBase64: String get() = identity.publicKeyBase64
    override val publicKeyFingerprint: String get() = identity.publicKeyFingerprint
    override fun sign(data: ByteArray): ByteArray = identity.sign(data)
}

private class EncryptedAccountTokenStore(context: Context) : AccountTokenStore {
    private val preferences = context.applicationContext
        .getSharedPreferences("linka_account_session", Context.MODE_PRIVATE)

    override fun getServerUrl(): String =
        preferences.getString("api_base_url", EMULATOR_API_URL) ?: EMULATOR_API_URL

    override fun saveServerUrl(value: String) {
        check(preferences.edit().putString("api_base_url", value.trim()).commit()) {
            "Unable to save LINKA API address."
        }
    }

    override fun saveToken(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8))
        val editor = preferences.edit()
            .putString("token_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("token_ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        check(editor.commit()) { "Unable to securely save the LINKA account session." }
    }

    override fun readToken(): String? {
        val ivValue = preferences.getString("token_iv", null) ?: return null
        val encryptedValue = preferences.getString("token_ciphertext", null) ?: return null
        return runCatching {
            val iv = Base64.decode(ivValue, Base64.NO_WRAP)
            val encrypted = Base64.decode(encryptedValue, Base64.NO_WRAP)
            if (iv.size != 12 || encrypted.size < 16) error("Invalid encrypted session.")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(encrypted).toString(StandardCharsets.UTF_8)
        }.getOrElse {
            clearToken()
            null
        }
    }

    override fun clearToken() {
        check(
            preferences.edit()
                .remove("token_iv")
                .remove("token_ciphertext")
                .commit()
        ) { "Unable to clear the local LINKA account session." }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = KEY_ALIAS
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        const val EMULATOR_API_URL = "http://10.0.2.2:8080"
        private const val KEY_ALIAS = "linka.account.session.aes.v1"
    }
}

private class HttpUrlConnectionAccountTransport : AccountApiTransport {
    override fun execute(
        baseUrl: String,
        method: String,
        path: String,
        body: JSONObject?,
        bearerToken: String?
    ): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            useCaches = false
            doInput = true
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            if (bearerToken != null) setRequestProperty("Authorization", "Bearer " + bearerToken)
        }

        try {
            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(responseText) }.getOrElse { JSONObject() }
            if (status !in 200..299) {
                val code = json.optString("error").ifBlank { "request_failed" }
                throw LinkaAccountException(code, messageFor(code), status)
            }
            return json
        } catch (error: LinkaAccountException) {
            throw error
        } catch (error: Exception) {
            throw LinkaAccountException(
                "network_unavailable",
                "Cannot reach LINKA at $baseUrl. Check the server address and network."
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun messageFor(code: String): String = when (code) {
        "invalid_credentials" -> "Email or password is incorrect."
        "email_already_registered" -> "An account already exists for this email."
        "device_already_linked" -> "This LINKA device is already linked to another account."
        "device_revoked" -> "This device has been revoked. Contact LINKA support."
        "rate_limited" -> "Too many attempts. Try again later."
        "database_not_configured" -> "Account service is not available on this server yet."
        "account_recovery_unavailable" -> "Password recovery is not enabled because email delivery is not configured."
        else -> "LINKA could not complete the request ($code)."
    }
}

/**
 * Calls the existing signed-device account API. Device signing remains in the
 * Android Keystore-backed LocalDeviceIdentity; account tokens are AES-GCM
 * encrypted with a separate Android Keystore key before SharedPreferences use.
 *
 * The internal constructor accepts test doubles for session storage, device
 * identity and transport so restore/logout behavior can be tested without
 * pretending that a local JVM has an Android Keystore.
 */
class LinkaAccountClient internal constructor(
    private val store: AccountTokenStore,
    private val identity: AccountDeviceIdentity,
    private val transport: AccountApiTransport
) {
    constructor(context: Context, identity: LocalDeviceIdentity) : this(
        EncryptedAccountTokenStore(context),
        AndroidAccountDeviceIdentity(identity),
        HttpUrlConnectionAccountTransport()
    )

    fun serverUrl(): String = store.getServerUrl()

    fun setServerUrl(value: String) {
        store.saveServerUrl(normalizeBaseUrl(value))
    }

    fun hasLocalSession(): Boolean = store.readToken() != null

    suspend fun register(
        email: String,
        password: String,
        displayName: String,
        serverUrl: String = store.getServerUrl()
    ): LinkaAccountProfile = withContext(Dispatchers.IO) {
        val baseUrl = normalizeBaseUrl(serverUrl)
        store.saveServerUrl(baseUrl)
        val deviceToken = createDeviceSession(baseUrl)
        val body = JSONObject()
            .put("email", email)
            .put("password", password)
            .put("displayName", displayName)
            .put("platform", "android")
            .put("deviceName", listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .take(100))
        val result = request(baseUrl, "POST", "/v1/accounts/register", body, deviceToken)
        saveAccountToken(result)
        profileFrom(result)
    }

    suspend fun login(
        email: String,
        password: String,
        serverUrl: String = store.getServerUrl()
    ): LinkaAccountProfile = withContext(Dispatchers.IO) {
        val baseUrl = normalizeBaseUrl(serverUrl)
        store.saveServerUrl(baseUrl)
        val deviceToken = createDeviceSession(baseUrl)
        val body = JSONObject()
            .put("email", email)
            .put("password", password)
            .put("platform", "android")
            .put("deviceName", listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .take(100))
        val result = request(baseUrl, "POST", "/v1/accounts/login", body, deviceToken)
        saveAccountToken(result)
        profileFrom(result)
    }

    suspend fun restoreSession(): LinkaAccountProfile? = withContext(Dispatchers.IO) {
        val token = store.readToken() ?: return@withContext null
        try {
            val result = request(store.getServerUrl(), "GET", "/v1/me", null, token)
            profileFrom(result)
        } catch (error: LinkaAccountException) {
            if (error.httpStatus == 401) store.clearToken()
            throw error
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        val token = store.readToken() ?: return@withContext
        request(store.getServerUrl(), "POST", "/v1/auth/logout", JSONObject(), token)
        store.clearToken()
    }

    suspend fun registerMessagingEncryptionKey(body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        request(
            store.getServerUrl(),
            "PUT",
            "/v1/me/encryption-key",
            body,
            requireMessagingToken()
        )
    }

    suspend fun listConversations(): JSONArray = withContext(Dispatchers.IO) {
        request(
            store.getServerUrl(),
            "GET",
            "/v1/conversations?limit=50",
            null,
            requireMessagingToken()
        ).optJSONArray("conversations") ?: JSONArray()
    }

    suspend fun createConversation(participantAccountIds: List<String>): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("kind", if (participantAccountIds.size == 1) "direct" else "group")
            .put("participantAccountIds", JSONArray(participantAccountIds))
        request(
            store.getServerUrl(),
            "POST",
            "/v1/conversations",
            body,
            requireMessagingToken()
        )
    }

    suspend fun listConversationEncryptionDevices(conversationId: String): List<MessagingDevicePublicKey> =
        withContext(Dispatchers.IO) {
            val body = request(
                store.getServerUrl(),
                "GET",
                "/v1/conversations/" + encodePathSegment(conversationId) + "/encryption-devices",
                null,
                requireMessagingToken()
            )
            val devices = body.optJSONArray("devices") ?: JSONArray()
            (0 until devices.length()).mapNotNull { index ->
                val item = devices.optJSONObject(index) ?: return@mapNotNull null
                val version = item.optInt("keyVersion", 0).takeIf { it > 0 }
                val algorithm = item.optString("algorithm").takeIf { it.isNotBlank() && it != "null" }
                val publicKey = item.optString("publicKeyBase64").takeIf { it.isNotBlank() && it != "null" }
                MessagingDevicePublicKey(
                    deviceId = item.optString("deviceId"),
                    accountId = item.optString("accountId"),
                    keyVersion = version,
                    algorithm = algorithm,
                    publicKeyBase64 = publicKey
                )
            }
        }

    suspend fun sendEncryptedMessage(conversationId: String, body: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            request(
                store.getServerUrl(),
                "POST",
                "/v1/conversations/" + encodePathSegment(conversationId) + "/messages",
                body,
                requireMessagingToken()
            )
        }

    suspend fun listInternalMessages(conversationId: String): JSONArray = withContext(Dispatchers.IO) {
        request(
            store.getServerUrl(),
            "GET",
            "/v1/conversations/" + encodePathSegment(conversationId) + "/messages?limit=100",
            null,
            requireMessagingToken()
        ).optJSONArray("messages") ?: JSONArray()
    }

    private fun requireMessagingToken(): String =
        store.readToken() ?: throw LinkaAccountException(
            "unauthorized",
            "Sign in to your LINKA account before opening encrypted messages.",
            401
        )

    private fun encodePathSegment(value: String): String =
        java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun saveAccountToken(result: JSONObject) {
        val token = result.optString("accessToken")
        if (token.length != 43 || token.any { !(it.isLetterOrDigit() || it == '_' || it == '-') }) {
            throw LinkaAccountException("invalid_server_response", "LINKA returned an invalid account session.")
        }
        store.saveToken(token)
    }

    private suspend fun createDeviceSession(baseUrl: String): String {
        val challenge = request(
            baseUrl, "POST", "/v1/auth/challenge",
            JSONObject().put("deviceId", identity.deviceId)
        )
        val challengeId = challenge.getString("challengeId")
        val nonce = challenge.getString("nonce")
        val issuedAt = challenge.getLong("issuedAt")
        val statement = listOf(
            "LINKA-SERVER-AUTH-V1",
            challengeId,
            nonce,
            identity.deviceId,
            issuedAt.toString()
        ).joinToString("\n").toByteArray(StandardCharsets.UTF_8)
        val signature = Base64.encodeToString(identity.sign(statement), Base64.NO_WRAP)
        val verified = request(
            baseUrl, "POST", "/v1/auth/verify",
            JSONObject()
                .put("challengeId", challengeId)
                .put("deviceId", identity.deviceId)
                .put("publicKeyBase64", identity.publicKeyBase64)
                .put("signatureBase64", signature)
        )
        val token = verified.optString("accessToken")
        if (token.length != 43) {
            throw LinkaAccountException("invalid_device_session", "The device verification response was invalid.")
        }
        return token
    }

    private fun request(
        baseUrl: String,
        method: String,
        path: String,
        body: JSONObject? = null,
        bearerToken: String? = null
    ): JSONObject = transport.execute(baseUrl, method, path, body, bearerToken)

    private fun profileFrom(json: JSONObject): LinkaAccountProfile {
        val accountId = json.optString("accountId")
        val email = json.optString("email")
        if (accountId.isBlank() || email.isBlank()) {
            throw LinkaAccountException("invalid_account_profile", "LINKA returned an incomplete account profile.")
        }
        return LinkaAccountProfile(
            accountId = accountId,
            email = email,
            displayName = json.optString("displayName"),
            emailVerified = json.optBoolean("emailVerified", false),
            deviceId = json.optString("deviceId", identity.deviceId),
            deviceFingerprint = json.optString("deviceFingerprint", identity.publicKeyFingerprint),
            expiresAt = json.optString("expiresAt")
        )
    }

    private fun normalizeBaseUrl(value: String): String {
        val raw = value.trim().trimEnd('/')
        val uri = runCatching { URI(raw) }.getOrElse {
            throw LinkaAccountException("invalid_server_url", "Enter a valid HTTPS LINKA API address.")
        }
        val host = uri.host?.lowercase().orEmpty()
        val localHttpAllowed = host in setOf("10.0.2.2", "127.0.0.1", "localhost")
        if (
            uri.userInfo != null ||
            uri.query != null ||
            uri.fragment != null ||
            uri.path !in listOf("", "/") ||
            uri.host.isNullOrBlank() ||
            !(uri.scheme.equals("https", true) || (uri.scheme.equals("http", true) && localHttpAllowed))
        ) {
            throw LinkaAccountException(
                "invalid_server_url",
                "Use an HTTPS API address. HTTP is allowed only for the Android emulator or localhost."
            )
        }
        return raw
    }
}
