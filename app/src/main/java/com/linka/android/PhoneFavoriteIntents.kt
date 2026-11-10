package com.linka.android

import android.content.Intent
import android.net.Uri

/**
 * Builds explicit Android system-app handoffs for carrier dialing and SMS.
 * LINKA never starts ACTION_CALL or sends an SMS itself.
 */
internal object PhoneFavoriteIntents {
    fun dial(number: String): Intent? {
        val normalized = PhoneFavoritesCodec.normalizePhoneNumber(number) ?: return null
        return Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", normalized, null))
    }

    fun message(number: String): Intent? {
        val normalized = PhoneFavoritesCodec.normalizePhoneNumber(number) ?: return null
        return Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", normalized, null))
    }
}
