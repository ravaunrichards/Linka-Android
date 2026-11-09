package com.linka.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class PhoneFavorite(
    val id: String,
    val name: String,
    val number: String
)

/**
 * Validates and serializes user-created phone favourites.
 *
 * This codec is Android-independent so normalization and persistence format
 * can be tested on the JVM without a real Android Keystore.
 */
internal object PhoneFavoritesCodec {
    const val MAX_FAVORITES = 50
    private const val MAX_NAME_LENGTH = 40
    private const val MAX_ID_LENGTH = 64

    fun normalizePhoneNumber(value: String): String? {
        val normalized = value.trim().filterNot { it.isWhitespace() || it in "-()." }
        if (normalized.length !in 3..24) return null
        if (!normalized.matches(Regex("\\+?[0-9*#]{3,24}"))) return null
        if (normalized.count(Char::isDigit) < 3) return null
        return normalized
    }

    fun createFavorite(id: String, name: String, number: String): PhoneFavorite? {
        val cleanId = id.trim()
        val cleanName = name.trim().replace(Regex("\\s+"), " ")
        val cleanNumber = normalizePhoneNumber(number) ?: return null
        if (cleanId.isBlank() || cleanId.length > MAX_ID_LENGTH) return null
        if (cleanName.isBlank() || cleanName.length > MAX_NAME_LENGTH) return null
        return PhoneFavorite(cleanId, cleanName, cleanNumber)
    }

    fun add(existing: List<PhoneFavorite>, favorite: PhoneFavorite): List<PhoneFavorite> {
        val withoutDuplicate = existing.filterNot {
            it.id == favorite.id || it.number == favorite.number
        }
        return (withoutDuplicate + favorite).takeLast(MAX_FAVORITES)
    }

    fun encode(favorites: List<PhoneFavorite>): String {
        val array = JSONArray()
        favorites.takeLast(MAX_FAVORITES).forEach { favorite ->
            val checked = createFavorite(favorite.id, favorite.name, favorite.number) ?: return@forEach
            array.put(
                JSONObject()
                    .put("id", checked.id)
                    .put("name", checked.name)
                    .put("number", checked.number)
            )
        }
        return array.toString()
    }

    fun decode(raw: String?): List<PhoneFavorite> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            val decoded = mutableListOf<PhoneFavorite>()
            val seenIds = mutableSetOf<String>()
            val seenNumbers = mutableSetOf<String>()
            for (index in 0 until array.length()) {
                if (decoded.size >= MAX_FAVORITES) break
                val value = array.optJSONObject(index) ?: continue
                val favorite = createFavorite(
                    value.optString("id"),
                    value.optString("name"),
                    value.optString("number")
                ) ?: continue
                if (seenIds.add(favorite.id) && seenNumbers.add(favorite.number)) {
                    decoded += favorite
                }
            }
            decoded
        }.getOrDefault(emptyList())
    }
}

/**
 * Stores the user's locally managed favourites as AES-GCM ciphertext. The
 * encryption key is non-exportable and held by Android Keystore; plaintext
 * numbers are never written to the preferences file.
 */
internal class PhoneFavoritesStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): List<PhoneFavorite> {
        val ivValue = preferences.getString(KEY_IV, null) ?: return emptyList()
        val ciphertextValue = preferences.getString(KEY_CIPHERTEXT, null) ?: return emptyList()
        return runCatching {
            val iv = Base64.decode(ivValue, Base64.NO_WRAP)
            val ciphertext = Base64.decode(ciphertextValue, Base64.NO_WRAP)
            require(iv.size == GCM_IV_BYTES && ciphertext.size >= GCM_TAG_BYTES) {
                "Invalid encrypted favourites."
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val plaintext = cipher.doFinal(ciphertext).toString(StandardCharsets.UTF_8)
            PhoneFavoritesCodec.decode(plaintext)
        }.getOrElse {
            preferences.edit().remove(KEY_IV).remove(KEY_CIPHERTEXT).commit()
            emptyList()
        }
    }

    fun save(favorites: List<PhoneFavorite>) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val plaintext = PhoneFavoritesCodec.encode(favorites).toByteArray(StandardCharsets.UTF_8)
        val ciphertext = cipher.doFinal(plaintext)
        check(
            preferences.edit()
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .commit()
        ) { "Unable to securely save LINKA favourites." }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
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

    private companion object {
        const val PREFERENCES_NAME = "linka_phone_favorites"
        const val KEY_IV = "favorites_iv"
        const val KEY_CIPHERTEXT = "favorites_ciphertext"
        const val KEY_ALIAS = "linka.phone.favorites.aes.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = 16
    }
}
