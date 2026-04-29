/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery.wifi.hotspot

import io.github.kyujincho.wvmg.protocol.medium.Medium
import io.github.kyujincho.wvmg.protocol.medium.MediumProvider
import io.github.kyujincho.wvmg.protocol.medium.UpgradePathCredentials
import io.github.kyujincho.wvmg.protocol.medium.UpgradedTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * [MediumProvider] for [Medium.WIFI_HOTSPOT] (Wi-Fi local-only soft-AP).
 *
 * Wraps `WifiManager.startLocalOnlyHotspot` (sender side) and
 * `WifiNetworkSpecifier` + `ConnectivityManager.requestNetwork`
 * (receiver side). Because the Android entry-points are heavyweight
 * and asynchronous, the provider depends on two thin abstractions
 * defined alongside it — [HotspotController] and [HotspotClient] —
 * which production wires to the real platform classes and tests fake
 * with in-memory implementations.
 *
 * ### Server / client roles
 *
 * Quick Share's bandwidth-upgrade dance is symmetric on the medium
 * provider side: the **server role** prepares credentials
 * ([prepareUpgrade]); the **client role** adopts them ([adoptUpgrade]).
 * Mapping back to file-transfer semantics:
 *
 *  * The **receiver** (the side waiting for files) acts as the
 *    bandwidth-upgrade server. It owns the upgrade decision because
 *    it owns the channel selection.
 *  * The **sender** acts as the bandwidth-upgrade client.
 *
 * That orientation is intentional — the spec was designed so the
 * advertiser (receiver) can negotiate higher-bandwidth paths against
 * peers who are mid-transfer, without disrupting the original control
 * plane.
 *
 * ### Hotspot-specific orientation choice
 *
 * In **this** provider we **invert** the bandwidth-upgrade orientation
 * relative to receiver/sender so that the sender brings up the AP and
 * the receiver joins. This matches stock Quick Share behaviour and the
 * acceptance criteria on issue #50:
 *
 *  > Sender creates local-only hotspot, sends SSID + passphrase to
 *  > receiver via current transport, receiver joins.
 *
 * The provider exposes the asymmetry through the conventional
 * `prepareUpgrade` / `adoptUpgrade` shape so the framework code stays
 * generic, but the calling glue (Phase 4 #54 "orchestrator's upgrade
 * hook") is responsible for picking the right instance per role.
 *
 * ### Edge case: device already on a Wi-Fi network
 *
 * On most OEMs `WifiManager.startLocalOnlyHotspot` will tear down the
 * STA association before bringing the AP up; some Samsung / vivo
 * devices instead refuse to start the hotspot until the user manually
 * disconnects from Wi-Fi. The provider surfaces this as
 * `prepareUpgrade() == null` and the framework falls back to staying
 * on the current medium — see the manual interop checklist under
 * `docs/testing/interop-wifi-hotspot.md` for the matrix of OEM
 * behaviour.
 *
 * @param controller Lifecycle for the server-side hotspot. `null`
 *   marks this device as "cannot be the AP" — happens on emulators,
 *   form factors without Wi-Fi (Android TV without dongle), or when
 *   the runtime permission grant is missing.
 * @param client Lifecycle for the client-side join. `null` marks the
 *   device as "cannot join a soft-AP via WifiNetworkSpecifier" —
 *   pre-API-29 devices, devices where the manufacturer disabled
 *   `WifiNetworkSpecifier`, etc.
 * @param available Cheap O(1) capability probe ([isSupported]). Should
 *   reflect "device has Wi-Fi hardware AND the runtime permission
 *   grant we need is currently held". Defaults to `controller != null
 *   || client != null` — at least one role must be possible for the
 *   medium to be useful in the ladder.
 */
public class WifiHotspotMediumProvider(
    private val controller: HotspotController? = null,
    private val client: HotspotClient? = null,
    private val available: () -> Boolean = { controller != null || client != null },
) : MediumProvider {
    override val medium: Medium = Medium.WIFI_HOTSPOT

    private val pendingReservation: AtomicReference<HotspotReservation?> = AtomicReference(null)

    override fun isSupported(): Boolean = available()

    /**
     * **Sender role (bandwidth-upgrade client at the protocol level,
     * but inverted for hotspot — see class KDoc).** Brings up the
     * local-only hotspot and binds a `ServerSocket` on its gateway
     * subnet, returning the credentials the peer needs to join.
     *
     * The reservation kept by [controller] survives until the
     * orchestrator releases it via the teardown callback bundled into
     * the [HotspotReservation]; the framework drops the reservation
     * on transfer-complete / cancel / failure.
     *
     * Returns `null` when:
     *  * No [controller] was injected (this device is configured as
     *    receive-only for hotspot upgrades).
     *  * The platform refuses to bring up the hotspot (radio in use,
     *    OEM consent dialog dismissed, hardware unsupported).
     */
    @Suppress("ReturnCount") // One early return per failure mode keeps the contract readable.
    override suspend fun prepareUpgrade(): UpgradePathCredentials? {
        val ctl = controller ?: return null
        pendingReservation.getAndSet(null)?.teardown?.invoke()
        val reservation = ctl.start() ?: return null
        pendingReservation.set(reservation)
        return reservation.credentials
    }

    @Suppress("SwallowedException")
    override suspend fun acceptUpgrade(): UpgradedTransport? =
        withContext(Dispatchers.IO) {
            val reservation = pendingReservation.getAndSet(null) ?: return@withContext null
            try {
                WifiHotspotTransport(
                    socket = reservation.serverSocket.accept(),
                    teardown = reservation.teardown,
                )
            } catch (
                @Suppress("TooGenericExceptionCaught") t: Throwable,
            ) {
                reservation.teardown()
                null
            }
        }

    override fun cancelPendingUpgrade() {
        pendingReservation.getAndSet(null)?.teardown?.invoke()
    }

    /**
     * **Receiver role (bandwidth-upgrade server at the protocol
     * level, but inverted for hotspot — see class KDoc).** Joins the
     * SSID broadcast by the sender, binds a route to the resulting
     * network, and opens a TCP socket to the sender's gateway / port.
     *
     * Returns `null` when:
     *  * No [client] was injected (this device is configured as
     *    send-only for hotspot upgrades).
     *  * [credentials] is for a different medium (caller bug — the
     *    framework does not enforce the match per [MediumProvider]'s
     *    contract).
     *  * The platform refuses the join (user dismisses the OEM
     *    "Connect to ..." dialog on API 29+, association times out,
     *    server unreachable).
     */
    @Suppress("ReturnCount") // One early return per failure mode keeps the contract readable.
    override suspend fun adoptUpgrade(credentials: UpgradePathCredentials): UpgradedTransport? {
        if (credentials !is UpgradePathCredentials.WifiHotspot) return null
        val cli = client ?: return null
        val joined = cli.join(credentials) ?: return null
        return WifiHotspotTransport(socket = joined.socket, teardown = joined.teardown)
    }
}
