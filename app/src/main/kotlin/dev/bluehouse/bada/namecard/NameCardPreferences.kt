/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.namecard

import android.content.Context
import android.content.SharedPreferences

/**
 * **Name Card feature on/off preference.** Master switch for the NameDrop-style
 * tap-to-share-contacts feature. Set from the **"Share my card when phones tap"**
 * switch on the My Name Card setup screen ([NameCardSetupActivity]).
 *
 * Read at the entry points so a tap is a complete no-op when OFF:
 *  - [dev.bluehouse.bada.nfc.NameCardHceService] — when OFF it does not answer the
 *    Name Card AID (no token minted, nothing served) so this phone is not
 *    tappable as a card.
 *  - [dev.bluehouse.bada.nfc.BadaNdefApduService] — when OFF the at-rest tap
 *    serves no Name Card NDEF.
 *
 * Default **OFF** (opt-in): sharing a contact card is personal-data disclosure,
 * so it stays disabled until the user explicitly enables it in setup. Backed by
 * a private SharedPreferences file.
 */
internal class NameCardPreferences(
    private val prefs: SharedPreferences,
) {
    /** True when tap-to-share-contacts is enabled. Default false — strictly opt-in. */
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "bada.name_card_prefs"
        private const val KEY_ENABLED = "enabled"

        fun from(context: Context): NameCardPreferences =
            NameCardPreferences(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
    }
}
