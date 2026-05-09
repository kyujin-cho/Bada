/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.medium

import dev.bluehouse.bada.protocol.transport.ConnectedTransport
import dev.bluehouse.bada.protocol.transport.FramedConnection
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Pluggable per-medium adapter. One implementation per Quick Share
 * transport (Wi-Fi LAN, Wi-Fi Direct, Wi-Fi Hotspot, Bluetooth RFCOMM,
 * BLE L2CAP, Wi-Fi Aware, …).
 *
 * The framework — [dev.bluehouse.bada.protocol.connection.OutboundConnection]
 * and [dev.bluehouse.bada.protocol.connection.InboundConnection],
 * via [MediumRegistry] — calls into a provider in three places:
 *
 *  1. **Capability advertisement** ([isSupported]) at connection start.
 *     Drives `ConnectionRequestFrame.mediums` on the sender side and
 *     intersection on the receiver side.
 *  2. **Upgrade preparation** ([prepareUpgrade], server role only) when
 *     the receiver decides to switch transports. The provider returns
 *     the credentials the sender will need to reconnect on the new
 *     medium; the framework wraps them in
 *     `BANDWIDTH_UPGRADE_NEGOTIATION{UPGRADE_PATH_AVAILABLE}`.
 *  3. **Upgrade adoption** ([adoptUpgrade], client role only) once the
 *     sender has parsed an `UPGRADE_PATH_AVAILABLE` frame. The
 *     provider does whatever IO it needs (open a Wi-Fi Direct group,
 *     connect to the new SSID, open the RFCOMM socket, …) and returns
 *     a fresh transport for the framework to swap in.
 *
 * Phase 4 sub-issues #49–#53 each ship a single provider for their
 * medium. None of them touches the orchestrator — the registry
 * dispatch keeps the framework code constant as the medium count
 * grows.
 *
 * ### Why this lives in :core-protocol (no `android.*`)
 *
 * The interface itself never opens a socket, never pokes Wi-Fi, never
 * looks at Bluetooth — it just asks "are you available?" / "give me
 * credentials" / "use these credentials". Concrete implementations
 * are perfectly free to live in `:discovery-android` (or another
 * Android-only module) and depend on `WifiManager`, `BluetoothManager`,
 * etc. The framework consumes them through this interface and never
 * sees the Android types.
 *
 * The single transport the provider returns from [adoptUpgrade] is
 * declared as a sealed marker type ([UpgradedTransport]) so per-medium
 * implementations can ship their own concrete subclass without leaking
 * an Android socket type into `:core-protocol`. Phase 4 sub-issues
 * (#49–#53) extend the marker as needed.
 */
public interface MediumProvider {
    /** The medium this provider implements. Stable across the JVM lifetime. */
    public val medium: Medium

    /**
     * Returns true iff this device is currently capable of using
     * [medium]. Examples:
     *
     *  - Wi-Fi LAN: device is on a Wi-Fi network with a routable IP.
     *  - Wi-Fi Direct: hardware supports P2P and the user has not
     *    revoked NEARBY_WIFI_DEVICES permission.
     *  - Bluetooth RFCOMM: Bluetooth is on, the local adapter is
     *    discoverable, and BLUETOOTH_CONNECT is granted.
     *
     * The framework calls this at the start of every connection
     * lifecycle (cheap, must be O(1)). Providers MUST NOT block; if a
     * capability check needs an async probe, the provider should keep
     * a cached result that the OS update broadcasts invalidate.
     */
    public fun isSupported(): Boolean

    /**
     * **Server role.** Stand up the medium and produce the credentials
     * the peer needs to connect.
     *
     * Called by the framework after the receiver has decided to send
     * `UPGRADE_PATH_AVAILABLE`. Returning `null` means "I cannot
     * upgrade right now after all" and causes the framework to fall
     * back to the next medium in the ladder, or stay on the current
     * medium if no fallback is possible.
     *
     * The framework wraps the returned credentials in an
     * `UpgradePathInfo` proto via the per-medium serializer in
     * [dev.bluehouse.bada.protocol.connection.BandwidthUpgradeFrames];
     * sub-issues #49–#53 each add a new arm there.
     *
     * Default: returns `null` (provider does not support being the
     * server side of an upgrade). Wi-Fi LAN's provider trivially
     * implements this by reading the receiver-side bind address; other
     * providers do real I/O (open a soft-AP, advertise an RFCOMM
     * service record, etc.).
     */
    public suspend fun prepareUpgrade(): UpgradePathCredentials? = null

    /**
     * **Client role.** Adopt the credentials the peer just sent, do
     * whatever bring-up the medium requires, and hand the framework
     * back a transport ready for the SecureChannel to wrap.
     *
     * Called by the framework after the sender has parsed
     * `UPGRADE_PATH_AVAILABLE`. Returning `null` aborts the upgrade
     * (the framework stays on the current medium and surfaces an
     * `UPGRADE_FAILURE` to the peer).
     *
     * Default: returns `null`. The Wi-Fi LAN provider implements this
     * by re-resolving the receiver-side IP/port and opening a fresh
     * `Socket`; other providers do their medium-specific bring-up.
     *
     * @param credentials The peer-supplied bring-up parameters. The
     *   provider MUST validate that
     *   `credentials.medium == this.medium`; the framework does not
     *   enforce the match because intermediate code paths could
     *   theoretically route mismatched values during a fallback.
     */
    public suspend fun adoptUpgrade(credentials: UpgradePathCredentials): UpgradedTransport? = null

    /**
     * **Server role.** Accept the peer's connection on the medium that
     * [prepareUpgrade] just stood up and return the connected transport.
     *
     * Implementations that need a blocking accept must dispatch it onto
     * their own IO context or document that callers should invoke this
     * from an IO dispatcher. Returning `null` aborts the upgrade while
     * keeping the original transport alive.
     */
    public suspend fun acceptUpgrade(): UpgradedTransport? = null

    /**
     * Best-effort cleanup for any pending medium resources allocated by
     * [prepareUpgrade] or [adoptUpgrade]. Called when the orchestrator
     * falls back to the previous transport or the connection terminates.
     */
    public fun cancelPendingUpgrade() {}
}

/**
 * Marker type for a transport produced by [MediumProvider.adoptUpgrade].
 *
 * Concrete subtypes live in the per-medium adapter modules (e.g.
 * `:discovery-android` for Phase 4 sub-issues) so this module never
 * imports `android.*` or `java.net.Socket`-derived types it cannot
 * own. The framework treats every value here as opaque until the
 * SecureChannel is rebuilt around it — that's the responsibility of
 * the orchestrator's upgrade hook (added in #54 once at least one
 * non-Wi-Fi-LAN provider exists).
 *
 * Implementations SHOULD be `data class` / `value class` shaped so
 * they round-trip cleanly through equality checks in tests. The
 * minimum useful subtype is described by [Generic] which simply
 * wraps a [Medium] — convenient for the unit tests in this module
 * and as a placeholder while the per-medium adapters land.
 */
public interface UpgradedTransport : ConnectedTransport {
    /**
     * Stub transport carrying nothing more than the medium type.
     *
     * Used by [MediumProvider]s that do not yet have a real socket to
     * return (e.g. fixtures, partial Phase 4 implementations). Real
     * adapters will return their own subtype carrying a `Socket` /
     * `BluetoothSocket` / `L2CapChannel` / etc.
     */
    public data class Generic(
        override val medium: Medium,
    ) : UpgradedTransport {
        override val inputStream: InputStream
            get() = error("Generic upgraded transport has no input stream")

        override val outputStream: OutputStream
            get() = error("Generic upgraded transport has no output stream")

        override fun close() {}
    }

    /**
     * JVM socket-backed transport, useful for Wi-Fi-based providers and
     * loopback tests. Android-specific providers may expose their own
     * subtype when they need additional teardown state, as long as they
     * implement [inputStream], [outputStream], and [close].
     */
    public class SocketBacked(
        override val medium: Medium,
        public val socket: Socket,
        private val onClose: () -> Unit = {},
    ) : UpgradedTransport {
        override val inputStream: InputStream
            get() = socket.getInputStream()

        override val outputStream: OutputStream
            get() = socket.getOutputStream()

        override fun close() {
            runCatching { socket.close() }
            runCatching { onClose() }
        }
    }
}

/**
 * Wrap an upgraded transport in the standard Quick Share length-prefixed
 * framing layer.
 */
public fun UpgradedTransport.asFramedConnection(): FramedConnection = FramedConnection(this)
