/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.send

import org.junit.Assert.assertEquals
import org.junit.Test

class SendPeerPickerLogSanitizationTest {
    @Test
    fun `sanitized log value escapes control characters and quotes`() {
        val raw = "Galaxy\tS26\nReceiver\r\"quoted\"\\tail"

        assertEquals(
            "Galaxy\\tS26\\nReceiver\\r\\\"quoted\\\"\\\\tail",
            raw.toSanitizedLogValue(),
        )
    }

    @Test
    fun `quoted log value wraps sanitized text`() {
        assertEquals("\"Galaxy\\nS26\"", "Galaxy\nS26".toQuotedLogValue())
    }

    @Test
    fun `quoted log value keeps explicit null token`() {
        assertEquals("<null>", (null as String?).toQuotedLogValue(nullToken = "<null>"))
    }
}
