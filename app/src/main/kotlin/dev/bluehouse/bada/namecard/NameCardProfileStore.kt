/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.namecard

import android.content.Context
import android.content.SharedPreferences
import dev.bluehouse.bada.protocol.namecard.NameCard
import dev.bluehouse.bada.protocol.namecard.NameCardEntry
import dev.bluehouse.bada.protocol.namecard.NameCardEntryKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * **My Name Card profile store** — persists the contact card the user sets up
 * in the "Name Card" settings screen ([NameCardSetupActivity]) and shares by
 * tapping phones (NameDrop-style).
 *
 * Backed by a private [SharedPreferences] file. Each field (name / phone /
 * email) is stored independently and may be blank; [load] assembles them into a
 * [NameCard], or returns `null` when the user has set up nothing (no name, no
 * phone, no email) so callers can fall back to device sources via
 * [NameCardResolver].
 *
 * Blank strings are normalised to "unset" (trimmed-empty → `null`) so an
 * accidentally-saved space never produces an empty TLV on the wire.
 *
 * Exercised indirectly by [NameCardResolver] tests (which inject a fake card).
 */
internal class NameCardProfileStore(
    private val prefs: SharedPreferences,
) {
    /** The saved display name, or `null`/blank if unset. */
    fun displayName(): String? = prefs.getString(KEY_NAME, null)?.trimToNull()

    /** The saved phone number, or `null`/blank if unset. */
    fun phoneNumber(): String? = prefs.getString(KEY_PHONE, null)?.trimToNull()

    /** The saved email, or `null`/blank if unset. */
    fun email(): String? = prefs.getString(KEY_EMAIL, null)?.trimToNull()

    /**
     * The saved richer typed fields (company, title, address, website, birthday,
     * note, nickname, additional phones/emails) — normally imported from the
     * device Contacts profile via "Use my phone info". Empty if none.
     */
    fun entries(): List<NameCardEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val kind = runCatching { NameCardEntryKind.valueOf(o.getString("k")) }.getOrNull()
                val value = o.optString("v", "")
                if (kind != null && value.isNotEmpty()) NameCardEntry(kind, value) else null
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Persist the richer typed fields (replaces any previously stored set). */
    fun saveEntries(entries: List<NameCardEntry>) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().put("k", e.kind.name).put("v", e.value))
        }
        prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }

    /** True once the user has entered at least one of name / phone / email / a typed entry. */
    fun isConfigured(): Boolean =
        displayName() != null || phoneNumber() != null || email() != null || entries().isNotEmpty()

    /**
     * Which fields the user chose to share via the "Choose what to share" picker,
     * or `null` when they never opened it (→ share every present field). A set of
     * the field keys [NameCardResolver.FIELD_NAME]/[NameCardResolver.FIELD_PHONE]/
     * [NameCardResolver.FIELD_EMAIL]. Consumed by [NameCardResolver.resolve].
     */
    fun shareSelection(): Set<String>? {
        val raw = prefs.getString(KEY_SHARES, null) ?: return null
        return raw.split(",").filter { it.isNotEmpty() }.toSet()
    }

    /** Persist the picked share fields (empty = share nothing until re-picked). */
    fun saveShareSelection(fields: Set<String>) {
        prefs.edit().putString(KEY_SHARES, fields.joinToString(",")).apply()
    }

    /**
     * Assemble the saved fields into a [NameCard], or `null` if nothing is set.
     * Never throws: a card requires ≥1 field, which [isConfigured] guarantees
     * before we construct it.
     */
    fun load(): NameCard? {
        if (!isConfigured()) return null
        return NameCard(
            displayName = displayName(),
            phoneNumber = phoneNumber(),
            email = email(),
            entries = entries(),
        )
    }

    /** Persist the three fields (each blank → cleared). Applied asynchronously. */
    fun save(
        name: String?,
        phone: String?,
        email: String?,
    ) {
        prefs
            .edit()
            .putString(KEY_NAME, name?.trimToNull())
            .putString(KEY_PHONE, phone?.trimToNull())
            .putString(KEY_EMAIL, email?.trimToNull())
            .apply()
    }

    /** Wipe the saved card. */
    fun clear() {
        prefs
            .edit()
            .remove(KEY_NAME)
            .remove(KEY_PHONE)
            .remove(KEY_EMAIL)
            .remove(KEY_ENTRIES)
            .remove(KEY_SHARES)
            .apply()
    }

    private fun String.trimToNull(): String? = trim().ifEmpty { null }

    companion object {
        private const val PREFS_NAME = "bada.name_card_profile"
        private const val KEY_NAME = "name"
        private const val KEY_PHONE = "phone"
        private const val KEY_EMAIL = "email"
        private const val KEY_ENTRIES = "entries"
        private const val KEY_SHARES = "shares"

        fun from(context: Context): NameCardProfileStore =
            NameCardProfileStore(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
    }
}
