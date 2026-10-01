/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.send

import dev.bluehouse.bada.protocol.connection.TransferItem

/**
 * Pure-JVM classifier for text shared through the system share sheet
 * (#301). Decides which Quick Share text kind a string travels as and
 * picks the title the receiver shows in its consent UI.
 *
 * Only a string that is a single http(s) URL as a whole counts as
 * [TransferItem.Text.Kind.URL]; the receiver then offers to open it.
 * Anything else, including a message with a link inside it, ships as
 * [TransferItem.Text.Kind.PLAIN] so the receiver copies the full text
 * instead of opening part of it.
 *
 * Phone numbers and addresses are left as plain text on purpose. A
 * digits-and-dashes rule would also match dates such as `2026-10-01`
 * and make the receiver offer to dial them, and there is no reliable
 * pattern for postal addresses.
 */
internal object SharedTextClassifier {
    /**
     * Result of [classify].
     *
     * @property body The text to send. Trimmed for a URL so the
     *   receiver's "open" action gets a clean link; plain text is sent
     *   exactly as shared.
     * @property title `TextMetadata.text_title`: the host for a URL
     *   (the same choice NearDrop makes), a short single-line preview
     *   otherwise.
     */
    data class Classified(
        val body: String,
        val title: String,
        val kind: TransferItem.Text.Kind,
    )

    fun classify(text: String): Classified {
        val trimmed = text.trim()
        val host = URL_PATTERN.matchEntire(trimmed)?.groupValues?.get(1)
        return if (host != null) {
            Classified(body = trimmed, title = host, kind = TransferItem.Text.Kind.URL)
        } else {
            Classified(body = text, title = preview(trimmed), kind = TransferItem.Text.Kind.PLAIN)
        }
    }

    /**
     * Collapse whitespace (newlines included) to single spaces and cut
     * the result to [TITLE_MAX_CODE_POINTS], counted in code points so
     * an emoji or other surrogate pair is never split.
     */
    private fun preview(text: String): String {
        val singleLine = text.replace(WHITESPACE_RUN, " ")
        val codePoints = singleLine.codePointCount(0, singleLine.length)
        if (codePoints <= TITLE_MAX_CODE_POINTS) return singleLine
        val end = singleLine.offsetByCodePoints(0, TITLE_MAX_CODE_POINTS)
        return singleLine.substring(0, end).trimEnd() + ELLIPSIS
    }

    /**
     * `http://` or `https://`, optional userinfo, then the host (group
     * 1, a bracketed IPv6 literal or a plain name), an optional port,
     * and an optional path / query / fragment. No whitespace anywhere.
     */
    private val URL_PATTERN =
        Regex(
            """https?://(?:[^\s/?#@]*@)?(\[[^\s\]]+]|[^\s/?#:@\[\]]+)(?::\d+)?(?:[/?#]\S*)?""",
            RegexOption.IGNORE_CASE,
        )

    private val WHITESPACE_RUN = Regex("""\s+""")

    /** Long enough to recognise a message, short enough for one notification line. */
    private const val TITLE_MAX_CODE_POINTS = 32

    private const val ELLIPSIS = "…"
}
