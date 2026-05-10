/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.send

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendBootstrapRetryPolicyTest {
    @Test
    fun `initial connect failure is retryable on a lower-priority route`() {
        assertTrue(
            SendBootstrapRetryPolicy.isRetryableBootstrapFailure(
                "Initial connect failed: failed to connect to /172.17.142.59 (port 53601)",
            ),
        )
    }

    @Test
    fun `initial handshake timeout is retryable before secure channel exists`() {
        assertTrue(
            SendBootstrapRetryPolicy.isRetryableBootstrapFailure(
                "Initial handshake timed out after 15000ms",
            ),
        )
    }

    @Test
    fun `protocol and payload failures are not retried as bootstrap failures`() {
        assertFalse(
            SendBootstrapRetryPolicy.isRetryableBootstrapFailure(
                "Expected ConnectionResponse, got PAYLOAD_TRANSFER",
            ),
        )
        assertFalse(
            SendBootstrapRetryPolicy.isRetryableBootstrapFailure(
                "Payload stream failed: Broken pipe",
            ),
        )
    }
}
