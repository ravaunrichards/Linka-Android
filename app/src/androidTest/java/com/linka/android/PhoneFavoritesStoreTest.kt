package com.linka.android

import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import javax.crypto.SecretKey

@RunWith(AndroidJUnit4::class)
class PhoneFavoritesStoreTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val preferences
        get() = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Before
    fun clearPreviousState() {
        preferences.edit().clear().commit()
        deleteEncryptionKey()
    }

    @After
    fun removeTestState() {
        preferences.edit().clear().commit()
        deleteEncryptionKey()
    }

    @Test
    fun savesAndRestoresFavouritesAsCiphertextOnly() {
        val favourites = listOf(
            PhoneFavoritesCodec.createFavorite("family-1", "Family", "+1 (876) 555-0100")!!,
            PhoneFavoritesCodec.createFavorite("work-1", "Work", "+1 876 555 0101")!!
        )

        PhoneFavoritesStore(context).save(favourites)

        val stored = preferences.all
        assertEquals(setOf(KEY_IV, KEY_CIPHERTEXT), stored.keys)
        assertEquals(12, Base64.decode(stored[KEY_IV] as String, Base64.NO_WRAP).size)
        val ciphertext = stored[KEY_CIPHERTEXT] as String
        assertFalse(ciphertext.contains("Family", ignoreCase = true))
        assertFalse(ciphertext.contains("Work", ignoreCase = true))
        assertFalse(ciphertext.contains("+18765550100"))
        assertEquals(favourites, PhoneFavoritesStore(context).load())
    }

    @Test
    fun keystoreKeyIsNonExportable() {
        val favourite = PhoneFavoritesCodec.createFavorite(
            "family-1", "Family", "+18765550100"
        )!!
        PhoneFavoritesStore(context).save(listOf(favourite))

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        assertTrue(keyStore.containsAlias(KEY_ALIAS))
        val key = keyStore.getKey(KEY_ALIAS, null) as SecretKey
        assertNull("Android Keystore must not export the AES key", key.encoded)
    }

    @Test
    fun modifiedCiphertextFailsClosedAndClearsStoredPayload() {
        PhoneFavoritesStore(context).save(
            listOf(PhoneFavoritesCodec.createFavorite("family-1", "Family", "+18765550100")!!)
        )
        val payload = Base64.decode(
            preferences.getString(KEY_CIPHERTEXT, null),
            Base64.NO_WRAP
        )
        payload[payload.lastIndex] = (payload.last().toInt() xor 0x01).toByte()
        preferences.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(payload, Base64.NO_WRAP))
            .commit()

        assertTrue(PhoneFavoritesStore(context).load().isEmpty())
        assertTrue("Unreadable ciphertext should be removed", preferences.all.isEmpty())
    }

    @Test
    fun incompleteEncryptedRecordFailsClosedAndClearsBothKeys() {
        preferences.edit().putString(KEY_IV, Base64.encodeToString(ByteArray(12), Base64.NO_WRAP)).commit()

        assertTrue(PhoneFavoritesStore(context).load().isEmpty())
        assertTrue("Partial encrypted record should be cleared", preferences.all.isEmpty())
    }

    @Test
    fun missingKeystoreKeyFailsClosedAndClearsOldCiphertext() {
        PhoneFavoritesStore(context).save(
            listOf(PhoneFavoritesCodec.createFavorite("family-1", "Family", "+18765550100")!!)
        )
        deleteEncryptionKey()

        assertTrue(PhoneFavoritesStore(context).load().isEmpty())
        assertTrue("Ciphertext from a missing key must not be retained", preferences.all.isEmpty())
    }

    @Test
    fun updatedListPersistsRemovalAcrossStoreInstances() {
        val family = PhoneFavoritesCodec.createFavorite("family-1", "Family", "+18765550100")!!
        val work = PhoneFavoritesCodec.createFavorite("work-1", "Work", "+18765550101")!!
        val store = PhoneFavoritesStore(context)
        store.save(listOf(family, work))

        store.save(listOf(family))

        assertEquals(listOf(family), PhoneFavoritesStore(context).load())
    }

    @Test
    fun favouriteDialHandoffUsesSystemDiallerInsteadOfPlacingCarrierCall() {
        val intent = PhoneFavoriteIntents.dial("+1 (876) 555-0100")!!

        assertEquals(Intent.ACTION_DIAL, intent.action)
        assertEquals("tel", intent.data?.scheme)
        assertEquals("+18765550100", intent.data?.schemeSpecificPart)
        assertFalse(intent.action == Intent.ACTION_CALL)
    }

    @Test
    fun favouriteMessageHandoffUsesSystemSmsComposer() {
        val intent = PhoneFavoriteIntents.message("+1 (876) 555-0100")!!

        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("smsto", intent.data?.scheme)
        assertEquals("+18765550100", intent.data?.schemeSpecificPart)
    }

    @Test
    fun invalidFavouriteNumbersCannotProduceCarrierHandoffIntents() {
        assertNull(PhoneFavoriteIntents.dial("not a phone number"))
        assertNull(PhoneFavoriteIntents.message("not a phone number"))
    }

    private fun deleteEncryptionKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private companion object {
        const val PREFERENCES_NAME = "linka_phone_favorites"
        const val KEY_IV = "favorites_iv"
        const val KEY_CIPHERTEXT = "favorites_ciphertext"
        const val KEY_ALIAS = "linka.phone.favorites.aes.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
