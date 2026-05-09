/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.send

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the [ShareIntentRouter] action dispatch.
 *
 * `:app` is an Android module, but [ShareIntentRouter] is intentionally
 * platform-agnostic at the seam — it consumes a [ShareIntent] data
 * class instead of an `android.content.Intent`, so we can unit-test it
 * on a host JVM without Robolectric (per the issue body's testing
 * guidance for #24).
 *
 * The only Android API we touch in this test is the **string constant**
 * for the action name (`Intent.ACTION_SEND` / `Intent.ACTION_SEND_MULTIPLE`).
 * Those resolve to plain Java string literals at compile time and are
 * available in unit tests via the Android JAR shipped with the build,
 * so no mocking is required.
 */
class ShareIntentRouterTest {
    @Test
    fun `routes single send with stream extra to SingleUri`() {
        val uri = "content://example/files/1"
        val intent = ShareIntent(action = Intent.ACTION_SEND, streamUri = uri)

        val result = ShareIntentRouter.route(intent)

        assertEquals(ShareIntentInput.SingleUri(uri), result)
    }

    @Test
    fun `routes send multiple with stream list to MultipleUris`() {
        val uris = listOf<Any>("content://a", "content://b")
        val intent = ShareIntent(action = Intent.ACTION_SEND_MULTIPLE, streamUris = uris)

        val result = ShareIntentRouter.route(intent)

        assertEquals(ShareIntentInput.MultipleUris(uris), result)
    }

    @Test
    fun `prefers stream over text when single send carries both`() {
        // The Android share-sheet semantics: when both EXTRA_STREAM and
        // EXTRA_TEXT are present, the stream is the primary payload
        // (the text is a subject hint). We mirror that.
        val intent =
            ShareIntent(
                action = Intent.ACTION_SEND,
                streamUri = "content://primary",
                textExtra = "subject hint",
            )

        val result = ShareIntentRouter.route(intent)

        assertEquals(ShareIntentInput.SingleUri("content://primary"), result)
    }

    @Test
    fun `routes single send with only text extra to Text`() {
        val intent = ShareIntent(action = Intent.ACTION_SEND, textExtra = "hello")

        val result = ShareIntentRouter.route(intent)

        assertEquals(ShareIntentInput.Text("hello"), result)
    }

    @Test
    fun `single send with neither stream nor text returns null`() {
        val intent = ShareIntent(action = Intent.ACTION_SEND)

        assertNull(ShareIntentRouter.route(intent))
    }

    @Test
    fun `single send with blank text only returns null`() {
        // Blank text is not a sendable payload — surface it the same
        // way as a missing extra.
        val intent = ShareIntent(action = Intent.ACTION_SEND, textExtra = "   \n  ")

        assertNull(ShareIntentRouter.route(intent))
    }

    @Test
    fun `send multiple with empty list returns null`() {
        val intent = ShareIntent(action = Intent.ACTION_SEND_MULTIPLE, streamUris = emptyList())

        assertNull(ShareIntentRouter.route(intent))
    }

    @Test
    fun `send multiple with missing list returns null`() {
        val intent = ShareIntent(action = Intent.ACTION_SEND_MULTIPLE, streamUris = null)

        assertNull(ShareIntentRouter.route(intent))
    }

    @Test
    fun `send multiple ignores text extra`() {
        // ACTION_SEND_MULTIPLE only carries streams; the EXTRA_TEXT
        // shape is specific to ACTION_SEND.
        val intent =
            ShareIntent(
                action = Intent.ACTION_SEND_MULTIPLE,
                streamUris = null,
                textExtra = "ignored",
            )

        assertNull(ShareIntentRouter.route(intent))
    }

    @Test
    fun `unknown action returns null`() {
        val intent = ShareIntent(action = Intent.ACTION_VIEW, streamUri = "content://x")

        assertNull(ShareIntentRouter.route(intent))
    }

    @Test
    fun `null action returns null`() {
        val intent = ShareIntent(action = null, streamUri = "content://x")

        assertNull(ShareIntentRouter.route(intent))
    }

    @Test
    fun `multiple-uris result preserves order`() {
        val ordered = listOf<Any>("content://1", "content://2", "content://3")
        val intent = ShareIntent(action = Intent.ACTION_SEND_MULTIPLE, streamUris = ordered)

        val result = ShareIntentRouter.route(intent) as ShareIntentInput.MultipleUris

        // The protocol `payload_id` ordering is announced from this
        // list, so order preservation matters for receiver display.
        assertTrue(result.uris == ordered)
    }
}
