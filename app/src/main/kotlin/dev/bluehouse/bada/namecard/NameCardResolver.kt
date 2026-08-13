/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.namecard

import dev.bluehouse.bada.protocol.namecard.NameCard
import dev.bluehouse.bada.protocol.namecard.NameCardEntry

/**
 * **Name Card source resolver** — decides WHICH card this phone shares when two
 * phones tap (NameDrop-style).
 *
 * Only the card the user explicitly set up in **My Name Card**
 * ([NameCardProfileStore]) is ever shared. There is deliberately NO automatic
 * fallback to the device "Me" contact or the SIM line number: a tap must never
 * disclose the owner's real identity unless the user configured a card. (The
 * setup screen's "Use my phone info" button still PRE-FILLS the form from those
 * sources, but only as an explicit user action — see
 * [NameCardSetupActivity.fillFromDevice] / [AndroidDeviceContactSources].)
 *
 * The stored card is then filtered by the user's "Choose what to share"
 * selection; when nothing is configured (or everything is unchecked), [resolve]
 * returns `null` and no exchange happens. Pure and unit-testable without
 * Android (see `NameCardResolverTest`).
 */
internal class NameCardResolver(
    /** Loads the in-app My Name Card, or `null` if not set up. Normally `store::load`. */
    private val storedCard: () -> NameCard?,
    /**
     * The fields the user chose to share (keys [FIELD_NAME]/[FIELD_PHONE]/[FIELD_EMAIL]),
     * or `null` to share every present field. Normally `store::shareSelection`. An
     * unselected field is dropped from the shared card.
     */
    private val shareSelection: () -> Set<String>? = { null },
) {
    /**
     * Resolve the card to share, or `null` when the user has not set up a card
     * (or unchecked every field) — callers then skip the exchange entirely.
     */
    @Suppress("ReturnCount", "ComplexCondition")
    fun resolve(): NameCard? {
        val stored = storedCard() ?: return null

        val name = clean(stored.displayName)
        val phone = clean(stored.phoneNumber)
        val email = clean(stored.email)
        val entries = stored.entries

        // Drop any field the user unchecked in "Choose what to share" (null = share all).
        val selection = shareSelection()
        val outName = if (selection == null || FIELD_NAME in selection) name else null
        val outPhone = if (selection == null || FIELD_PHONE in selection) phone else null
        val outEmail = if (selection == null || FIELD_EMAIL in selection) email else null
        val outEntries =
            if (selection == null) entries else entries.filterIndexed { i, _ -> entryKey(i) in selection }

        if (outName == null && outPhone == null && outEmail == null && outEntries.isEmpty()) return null
        return NameCard(
            displayName = outName,
            phoneNumber = outPhone,
            email = outEmail,
            entries = outEntries,
        )
    }

    private fun clean(value: String?): String? = value?.trim()?.ifEmpty { null }

    companion object {
        /** Share-selection field keys (persisted in [NameCardProfileStore.shareSelection]). */
        const val FIELD_NAME = "name"
        const val FIELD_PHONE = "phone"
        const val FIELD_EMAIL = "email"

        /** Stable share-selection key for the richer entry at [index] (order as shown in the picker). */
        fun entryKey(index: Int): String = "e$index"
    }
}

/**
 * Device-side sources used by the setup screen's explicit "Use my phone info"
 * pre-fill (never consulted automatically at tap time). Abstracted behind an
 * interface so callers are testable on a plain JVM. The real reads require
 * runtime permissions and may legitimately return `null` (denied, unavailable,
 * eSIM with no readable number, no "Me" contact).
 */
internal interface DeviceContactSources {
    /** The device owner's display name from the "Me"/profile contact, or `null`. */
    fun profileDisplayName(): String?

    /** A phone number from the device "Me"/profile contact, or `null`. */
    fun profilePhoneNumber(): String?

    /** An email address from the device "Me"/profile contact, or `null`. */
    fun profileEmail(): String?

    /**
     * Richer typed fields from the device profile (company, title, address, website,
     * birthday, note, nickname, and any additional phones/emails), or empty.
     */
    fun profileEntries(): List<NameCardEntry>

    /** The SIM/line phone number, or `null` if unavailable/denied. */
    fun simPhoneNumber(): String?
}
