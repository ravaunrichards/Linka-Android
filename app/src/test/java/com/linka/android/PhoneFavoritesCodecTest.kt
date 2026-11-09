package com.linka.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneFavoritesCodecTest {
    @Test
    fun normalizesCommonPhoneNumberSeparators() {
        assertEquals("+18765550100", PhoneFavoritesCodec.normalizePhoneNumber("+1 (876) 555-0100"))
    }

    @Test
    fun rejectsInvalidPhoneNumbersAndNames() {
        assertNull(PhoneFavoritesCodec.createFavorite("id-1", "Invalid", "call-me"))
        assertNull(PhoneFavoritesCodec.createFavorite("id-1", "   ", "+18765550100"))
        assertNull(PhoneFavoritesCodec.createFavorite("id-1", "Too many characters".repeat(3), "+18765550100"))
        assertNull(PhoneFavoritesCodec.normalizePhoneNumber("++18765550100"))
        assertNull(PhoneFavoritesCodec.normalizePhoneNumber("12"))
    }

    @Test
    fun encodeAndDecodeRoundTripOnlyUserSuppliedFavourites() {
        val original = listOf(
            PhoneFavoritesCodec.createFavorite("id-1", "Family", "+1 876 555 0100")!!,
            PhoneFavoritesCodec.createFavorite("id-2", "Work", "876-555-0101")!!
        )

        assertEquals(original, PhoneFavoritesCodec.decode(PhoneFavoritesCodec.encode(original)))
    }

    @Test
    fun malformedStoredDataDoesNotCrashThePhoneScreen() {
        assertTrue(PhoneFavoritesCodec.decode("not-json").isEmpty())
        assertTrue(PhoneFavoritesCodec.decode(null).isEmpty())
        assertTrue(PhoneFavoritesCodec.decode("").isEmpty())
    }

    @Test
    fun addingAnExistingNumberReplacesTheOldFavourite() {
        val first = PhoneFavoritesCodec.createFavorite("id-1", "Old name", "+18765550100")!!
        val replacement = PhoneFavoritesCodec.createFavorite("id-2", "New name", "+1 (876) 555-0100")!!

        val result = PhoneFavoritesCodec.add(listOf(first), replacement)

        assertEquals(1, result.size)
        assertEquals(replacement, result.single())
    }

    @Test
    fun favouritesAreBoundedToFiftyEntries() {
        val favourites = (1..51).map { index ->
            PhoneFavoritesCodec.createFavorite(
                id = "id-$index",
                name = "Contact $index",
                number = "876555" + index.toString().padStart(4, '0')
            )!!
        }

        val result = favourites.fold(emptyList<PhoneFavorite>()) { current, favorite ->
            PhoneFavoritesCodec.add(current, favorite)
        }

        assertEquals(PhoneFavoritesCodec.MAX_FAVORITES, result.size)
        assertEquals("id-2", result.first().id)
        assertEquals("id-51", result.last().id)
    }
}
