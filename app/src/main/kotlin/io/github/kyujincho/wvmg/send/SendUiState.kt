/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.send

import io.github.kyujincho.wvmg.discovery.DiscoveredService
import io.github.kyujincho.wvmg.protocol.connection.OutboundConnectionState

/**
 * UI state for the share-intent flow rendered by [SendActivity].
 *
 * Modelled as a sealed hierarchy so the Activity's render path is a
 * single exhaustive `when`. The flow is roughly:
 *
 * ```
 *   PreparingInputs            (parsing the incoming share intent)
 *     -> PickingPeer           (discovery is running, user has not picked a peer yet)
 *     -> NoSupportedPayload    (terminal — the intent had nothing we can send)
 *
 *   PickingPeer
 *     -> Connecting            (user tapped a peer; OutboundConnection starting)
 *
 *   Connecting / AwaitingAcceptance / Sending  (mirrored from
 *     OutboundConnectionState; the Activity exposes the PIN here)
 *
 *   AwaitingAcceptance / Sending
 *     -> Completed / Rejected / Cancelled / Failed   (terminal)
 * ```
 *
 * `OutboundConnectionState` itself already models the connection-side
 * phases; this wrapper just adds the extra UI-only phases ("we haven't
 * picked a peer yet", "the intent is unsendable") that aren't part of
 * the core protocol surface.
 */
public sealed interface SendUiState {
    /**
     * Initial state — the Activity has just received the share intent
     * and is materialising it into a payload list.
     */
    public object PreparingInputs : SendUiState

    /**
     * Discovery is running and the user has not yet picked a peer.
     *
     * @property peers Distinct discovered peers, ordered by first-seen.
     *   The list is updated as discovery yields new peers; the Activity
     *   re-renders every time it changes.
     * @property payloadSummary Short human-readable description of what
     *   the user is about to send (e.g. "1 file (4.2 MB)").
     */
    public data class PickingPeer(
        val peers: List<DiscoveredService>,
        val payloadSummary: String,
    ) : SendUiState

    /**
     * Terminal — the share intent did not carry a payload we know how
     * to ship. Examples: an `ACTION_SEND` with no `EXTRA_STREAM` and
     * no `EXTRA_TEXT`, or an `ACTION_SEND_MULTIPLE` with an empty list.
     */
    public object NoSupportedPayload : SendUiState

    /**
     * The outbound connection is in flight. The wrapped
     * [OutboundConnectionState] carries the protocol-level phase and
     * (once known) the 4-digit confirmation PIN. The Activity reads
     * those fields off directly when rendering.
     *
     * @property connectionState Current snapshot from
     *   [io.github.kyujincho.wvmg.protocol.connection.OutboundConnection.state].
     * @property targetLabel Display label for the peer the user picked
     *   — the device name from the [DiscoveredService.endpointInfo] if
     *   present, otherwise the encoded instance name.
     */
    public data class Transferring(
        val connectionState: OutboundConnectionState,
        val targetLabel: String,
    ) : SendUiState
}
