package com.linka.android.core.mesh

import android.content.Context

/**
 * Explicit, persistent relay-consent setting. Relay participation is disabled
 * by default and may be withdrawn at any time by the phone owner.
 */
class RelayConsentStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = preferences.getBoolean(RELAY_ENABLED_KEY, false)

    fun setEnabled(enabled: Boolean) {
        check(
            preferences.edit()
                .putBoolean(RELAY_ENABLED_KEY, enabled)
                .commit()
        ) { "Unable to save LINKA relay consent" }
    }

    companion object {
        private const val PREFERENCES = "linka_relay_consent"
        private const val RELAY_ENABLED_KEY = "relay_enabled_v1"
    }
}
