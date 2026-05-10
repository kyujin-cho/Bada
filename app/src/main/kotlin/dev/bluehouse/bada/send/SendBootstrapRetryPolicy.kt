/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.send

internal object SendBootstrapRetryPolicy {
    private const val INITIAL_CONNECT_FAILED: String = "Initial connect failed:"
    private const val INITIAL_HANDSHAKE_TIMED_OUT: String = "Initial handshake timed out after"

    /**
     * `true` when [reason] describes a failed initial-control route
     * before the SecureChannel exists. The next-priority route may avoid
     * these failures because stock receivers can publish both same-Wi-Fi
     * LAN and BLE bootstrap surfaces while only one is actively accepting
     * the Nearby stream.
     *
     * Failures after the secure channel is up intentionally fall through
     * so the sender does not hide peer rejection, UKEY2 incompatibility,
     * or payload-streaming errors behind a retry.
     */
    fun isRetryableBootstrapFailure(reason: String): Boolean =
        reason.startsWith(INITIAL_CONNECT_FAILED) ||
            reason.startsWith(INITIAL_HANDSHAKE_TIMED_OUT)
}
