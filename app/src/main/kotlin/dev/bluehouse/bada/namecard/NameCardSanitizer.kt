/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.namecard

import dev.bluehouse.bada.protocol.namecard.NameCard
import dev.bluehouse.bada.protocol.namecard.NameCardEntry

/**
 * **Peer-card sanitizer** — applied at the exchange boundary ([NameCardBleExchange]) to every card
 * received from a peer BEFORE it reaches the UI or the contact saver. The peer is another build we
 * don't control, so its card is untrusted input:
 *
 *  - Cards carrying more than [MAX_ENTRIES] typed/unknown records are rejected outright (a hostile
 *    peer must not stuff the render/save path).
 *  - Every string field is stripped of ISO control characters and Unicode bidi-override /
 *    directionality codepoints (an RLO in a display name could visually spoof the whole card).
 *  - String fields are capped at [MAX_FIELD_CHARS] characters so one field can't flood the screen.
 *
 * Pure JVM; unit-tested in `NameCardSanitizerTest`.
 */
internal object NameCardSanitizer {
    /** Reject any card carrying more typed + unknown records than this. */
    const val MAX_ENTRIES: Int = 32

    /** Hard cap on each sanitized string field's length (code units). */
    const val MAX_FIELD_CHARS: Int = 256

    /**
     * Bidi control / directionality-override codepoints stripped from peer strings:
     * ALM, LRM/RLM, LRE/RLE/PDF/LRO/RLO, LRI/RLI/FSI/PDI.
     */
    private val BIDI_CONTROL: Set<Char> =
        setOf(
            '\u061C', // ALM
            '\u200E', // LRM
            '\u200F', // RLM
            '\u202A', // LRE
            '\u202B', // RLE
            '\u202C', // PDF
            '\u202D', // LRO
            '\u202E', // RLO
            '\u2066', // LRI
            '\u2067', // RLI
            '\u2068', // FSI
            '\u2069', // PDI
        )

    /**
     * Sanitize a parsed peer [card]. Returns the cleaned card, or `null` when the card must be
     * rejected (entry-count bound exceeded, or nothing displayable survives cleaning).
     */
    fun sanitize(card: NameCard): NameCard? {
        if (card.entries.size + card.extraFields.size > MAX_ENTRIES) return null
        return runCatching {
            NameCard(
                version = card.version,
                displayName = cleanText(card.displayName),
                phoneNumber = cleanText(card.phoneNumber),
                email = cleanText(card.email),
                entries =
                    card.entries.mapNotNull { entry ->
                        cleanText(entry.value)?.let { NameCardEntry(entry.kind, it) }
                    },
                extraFields = card.extraFields,
            )
        }.getOrNull() // all fields cleaned away -> the NameCard invariant throws -> reject
    }

    /**
     * Strip control + bidi-override characters from [value], trim, and cap the length.
     * Returns `null` when nothing displayable remains.
     */
    fun cleanText(value: String?): String? {
        val stripped =
            value
                ?.filterNot { ch -> ch.isISOControl() || ch in BIDI_CONTROL }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null
        return if (stripped.length > MAX_FIELD_CHARS) stripped.substring(0, MAX_FIELD_CHARS) else stripped
    }
}
