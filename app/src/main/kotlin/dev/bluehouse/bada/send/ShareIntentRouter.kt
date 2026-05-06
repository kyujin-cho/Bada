/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.send

import android.content.Intent

/**
 * Pure-JVM-testable router that walks a share-sheet [Intent] and emits
 * the canonical list of inputs the sender flow should ship.
 *
 * The Android share flow has three relevant action shapes:
 *
 *  - [Intent.ACTION_SEND] with `EXTRA_STREAM` carrying a single
 *    `android.net.Uri` — single attachment.
 *  - [Intent.ACTION_SEND] with `EXTRA_TEXT` carrying a `CharSequence`
 *    — plain-text share. Maps to a Quick Share text payload (Phase 1
 *    minimum: PLAIN; URL/ADDRESS/PHONE_NUMBER detection is left to a
 *    follow-up).
 *  - [Intent.ACTION_SEND_MULTIPLE] with `EXTRA_STREAM` carrying an
 *    `ArrayList<Uri>` — multiple attachments at once.
 *
 * The sealed [ShareIntentInput] hierarchy below captures each shape so
 * the Activity can branch on it once and forward to the conversion
 * layer ([UriFileSourceFactory]) without having to know the intent
 * action constants.
 *
 * The router intentionally does NOT itself create [dev.bluehouse.bada.protocol.connection.FileSource]s.
 * That keeps the routing logic — a string-action match plus extras
 * extraction — testable on a plain JVM with a fake [Intent]-equivalent,
 * because constructing a real `FileSource` would require a
 * `ContentResolver` (Android-only).
 */
public object ShareIntentRouter {
    /**
     * Inspect [intent] and return the parsed share input, or `null` if
     * the intent does not carry a recognisable share payload.
     *
     * Recognised inputs:
     *  - `ACTION_SEND` + `EXTRA_STREAM` (single Uri) → [ShareIntentInput.SingleUri]
     *  - `ACTION_SEND` + `EXTRA_TEXT` (CharSequence) → [ShareIntentInput.Text]
     *  - `ACTION_SEND_MULTIPLE` + `EXTRA_STREAM` (ArrayList<Uri>) →
     *    [ShareIntentInput.MultipleUris]
     *
     * If `ACTION_SEND` carries both a stream and a text, the stream
     * wins — that matches Android's share-sheet semantics where the
     * primary attachment is the file and the text is auxiliary
     * (e.g. an email subject). A text-only share has no stream.
     *
     * `ACTION_SEND_MULTIPLE` with no streams returns `null`; an empty
     * list is not a sensible share. Likewise an empty/blank `EXTRA_TEXT`
     * for `ACTION_SEND` returns `null`.
     */
    public fun route(intent: ShareIntent): ShareIntentInput? =
        when (intent.action) {
            Intent.ACTION_SEND -> routeSingle(intent)
            Intent.ACTION_SEND_MULTIPLE -> routeMultiple(intent)
            else -> null
        }

    private fun routeSingle(intent: ShareIntent): ShareIntentInput? {
        // Order matters: stream wins over text when both are present
        // (matches Android share-sheet semantics — the text is the
        // subject hint, the stream is the primary attachment).
        val stream = intent.streamUri
        if (stream != null) return ShareIntentInput.SingleUri(stream)
        val text = intent.textExtra
        return if (!text.isNullOrBlank()) ShareIntentInput.Text(text.toString()) else null
    }

    private fun routeMultiple(intent: ShareIntent): ShareIntentInput? {
        val uris = intent.streamUris
        return if (uris.isNullOrEmpty()) null else ShareIntentInput.MultipleUris(uris)
    }
}

/**
 * Platform-agnostic façade over the bits of [Intent] the router needs.
 *
 * The router never sees a real [Intent] in tests — instead, the call
 * site (the Activity) wraps the live intent into a [ShareIntent] with
 * [ShareIntent.fromAndroidIntent], and tests construct [ShareIntent]
 * instances directly. This lets us exercise `ACTION_SEND` vs
 * `ACTION_SEND_MULTIPLE` routing on a pure JVM without Robolectric.
 *
 * @property action The intent action, e.g. [Intent.ACTION_SEND]. May
 *   be `null` (Android allows it though it is rare).
 * @property streamUri `EXTRA_STREAM` when the action is single-send;
 *   `null` if the extra is missing or carries a list.
 * @property streamUris `EXTRA_STREAM` when the action is
 *   `ACTION_SEND_MULTIPLE`; `null` if the extra is missing.
 * @property textExtra `EXTRA_TEXT` when present; `null` otherwise.
 */
public data class ShareIntent(
    val action: String?,
    val streamUri: Any? = null,
    val streamUris: List<Any>? = null,
    val textExtra: CharSequence? = null,
)

/**
 * Parsed share-intent payload.
 *
 * Kept as a sealed hierarchy because consumers benefit from exhaustive
 * `when` dispatch on the kind. The `Uri`-typed members carry `Any`
 * here so the router stays platform-agnostic; the Activity casts back
 * to `android.net.Uri` at the call site.
 */
public sealed interface ShareIntentInput {
    /** A single attachment. */
    public data class SingleUri(
        val uri: Any,
    ) : ShareIntentInput

    /** Multiple attachments. The list is non-empty by construction. */
    public data class MultipleUris(
        val uris: List<Any>,
    ) : ShareIntentInput

    /** A plain-text share. The text is non-blank by construction. */
    public data class Text(
        val text: String,
    ) : ShareIntentInput
}
