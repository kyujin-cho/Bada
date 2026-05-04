/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:android.annotation.SuppressLint("MissingPermission")
@file:Suppress("MagicNumber") // Wi-Fi reason codes and well-known subnet octets are spec values.

package dev.bluehouse.libredrop.discovery.wifi.hotspot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dev.bluehouse.libredrop.protocol.medium.UpgradePathCredentials
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Production [HotspotController] backed by `WifiManager.startLocalOnlyHotspot`.
 *
 * Only available on API 26+ (the platform method itself is API 26+). The
 * factory in [WifiHotspotMediumProvider]'s wiring layer should null this
 * out on older devices so [WifiHotspotMediumProvider.isSupported] falls
 * through to receiver-only or "unsupported" cleanly.
 *
 * The hotspot's gateway IP is **not** carried in the
 * `LocalOnlyHotspotReservation` API on any current API level; we read it
 * from the network interface created by the OS for the AP. AOSP installs
 * the AP on `wlan1` with a 192.168.49.1/24 subnet on every Pixel and most
 * Samsung devices, but reading the actual interface address is the
 * forward-compatible path (vendors have shipped 192.168.43.x and 10.0.0.x
 * variants in the past).
 *
 * Lifecycle invariant: every successful [start] returns a
 * [HotspotReservation] whose `teardown` callback unregisters the
 * platform reservation **and** closes the bound `ServerSocket`. The
 * orchestrator is required to call it in the transfer-complete /
 * cancel / failure paths so the radio is freed quickly.
 *
 * @param context Application context used for permission checks and to
 *   look up `WifiManager`.
 * @param port The TCP port the server-side socket should attempt to
 *   bind. Pass `0` (default) to let the kernel pick a free ephemeral
 *   port — the chosen port is surfaced back through the
 *   [HotspotReservation.credentials] so the peer knows where to
 *   connect.
 */
@RequiresApi(Build.VERSION_CODES.O)
public class AndroidLocalOnlyHotspotController(
    private val context: Context,
    private val port: Int = 0,
) : HotspotController {
    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    @Suppress("ReturnCount") // Each early return marks a distinct platform-side failure mode.
    override suspend fun start(): HotspotReservation? {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null

        if (!hasRequiredPermissions()) {
            Log.w(TAG, "Cannot start local-only hotspot: missing required runtime permission.")
            return null
        }

        val reservation = bringUp(wifi) ?: return null
        val gatewayIp = resolveGatewayIp() ?: return abortBringUp(reservation, null, "no gateway IP")
        val serverSocket = bindServerSocket(gatewayIp, reservation) ?: return null
        val info = readApInfo(reservation) ?: return abortBringUp(reservation, serverSocket, "SSID/passphrase missing")
        return assembleReservation(info, gatewayIp, serverSocket, reservation)
    }

    private suspend fun bringUp(wifi: WifiManager): WifiManager.LocalOnlyHotspotReservation? =
        try {
            awaitReservation(wifi)
        } catch (
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            Log.w(TAG, "WifiManager.startLocalOnlyHotspot threw", t)
            null
        }

    private fun bindServerSocket(
        gatewayIp: InetAddress,
        reservation: WifiManager.LocalOnlyHotspotReservation,
    ): ServerSocket? =
        try {
            // backlog = 1: only the original sender peer should ever connect.
            ServerSocket(port, 1, gatewayIp)
        } catch (
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            Log.w(TAG, "Failed to bind ServerSocket on hotspot interface", t)
            reservation.close()
            null
        }

    private fun abortBringUp(
        reservation: WifiManager.LocalOnlyHotspotReservation,
        serverSocket: ServerSocket?,
        reason: String,
    ): HotspotReservation? {
        Log.w(TAG, "Aborting hotspot bring-up: $reason")
        if (serverSocket != null) {
            try {
                serverSocket.close()
            } catch (_: Exception) {
                // Best-effort cleanup.
            }
        }
        reservation.close()
        return null
    }

    private fun assembleReservation(
        info: ApInfo,
        gatewayIp: InetAddress,
        serverSocket: ServerSocket,
        reservation: WifiManager.LocalOnlyHotspotReservation,
    ): HotspotReservation {
        val credentials =
            UpgradePathCredentials.WifiHotspot(
                ssid = info.ssid,
                passphrase = info.passphrase,
                port = serverSocket.localPort,
                gateway = gatewayIp.hostAddress ?: UpgradePathCredentials.WifiHotspot.DEFAULT_GATEWAY,
                frequencyMhz = info.frequencyMhz ?: UpgradePathCredentials.WifiHotspot.FREQUENCY_NOT_SET,
            )

        val torndown = AtomicBoolean(false)
        val teardown: () -> Unit = {
            if (torndown.compareAndSet(false, true)) {
                try {
                    serverSocket.close()
                } catch (_: Exception) {
                    // Best-effort cleanup.
                }
                try {
                    reservation.close()
                } catch (_: Exception) {
                    // Best-effort cleanup.
                }
            }
        }

        return HotspotReservation(
            credentials = credentials,
            serverSocket = serverSocket,
            teardown = teardown,
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun awaitReservation(wifi: WifiManager): WifiManager.LocalOnlyHotspotReservation? =
        suspendCancellableCoroutine { cont ->
            val resumed = AtomicBoolean(false)
            val callback =
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                        if (resumed.compareAndSet(false, true)) {
                            cont.resume(reservation)
                        } else {
                            // Continuation already resumed (likely cancelled);
                            // release the platform reservation so we don't leak
                            // a hot AP.
                            try {
                                reservation.close()
                            } catch (_: Exception) {
                                // Best-effort.
                            }
                        }
                    }

                    override fun onStopped() {
                        // Defensive: if the platform stops the AP before
                        // onStarted (rare; usually only happens when the
                        // user disables Wi-Fi mid-bring-up), treat as a
                        // failure to acquire.
                        if (resumed.compareAndSet(false, true)) cont.resume(null)
                    }

                    override fun onFailed(reason: Int) {
                        Log.w(TAG, "Local-only hotspot bring-up failed: reason=$reason")
                        if (resumed.compareAndSet(false, true)) cont.resume(null)
                    }
                }

            cont.invokeOnCancellation {
                // Nothing to unregister here — the platform discards the
                // callback when onFailed/onStopped fires; the worst-case
                // is a no-op resume the next time the OS broadcasts.
            }

            try {
                wifi.startLocalOnlyHotspot(callback, mainHandler)
            } catch (
                @Suppress("TooGenericExceptionCaught") t: Throwable,
            ) {
                Log.w(TAG, "startLocalOnlyHotspot threw synchronously", t)
                if (resumed.compareAndSet(false, true)) cont.resume(null)
            }
        }

    /**
     * Pulls SSID / passphrase / frequency out of a
     * [WifiManager.LocalOnlyHotspotReservation] across API levels.
     *
     *  * API 30+: `softApConfiguration` (the new typed surface).
     *  * API 26-29: `wifiConfiguration` (deprecated but still populated
     *    for backwards-compat on the local-only path; AOSP keeps it
     *    even though general `WifiConfiguration` use was removed).
     */
    @Suppress(
        "DEPRECATION", // wifiConfiguration is the only API <= 29 source for SSID/passphrase.
        "ReturnCount", // Each early return is a distinct API-level / null-field path.
    )
    private fun readApInfo(reservation: WifiManager.LocalOnlyHotspotReservation): ApInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cfg = reservation.softApConfiguration
            val ssid = cfg.ssid?.removeSurrounding("\"") ?: return null
            val passphrase = cfg.passphrase ?: return null
            // SoftApConfiguration does not expose the actual operating
            // frequency on any current API level — `getChannels` (API 31+)
            // returns the configured band/channel hints, not the realised
            // value. Leave the frequency hint unset; the proto's `-1`
            // sentinel makes that explicit on the wire.
            return ApInfo(ssid = ssid, passphrase = passphrase, frequencyMhz = null)
        }
        val cfg = reservation.wifiConfiguration ?: return null
        val ssid = cfg.SSID?.removeSurrounding("\"") ?: return null
        val passphrase = cfg.preSharedKey?.removeSurrounding("\"") ?: return null
        return ApInfo(ssid = ssid, passphrase = passphrase, frequencyMhz = null)
    }

    /**
     * Locate the IPv4 address the OS assigned to the local-only-hotspot
     * interface. Most devices use `wlan1`; we walk every up + non-
     * loopback interface and pick the one in the well-known
     * `192.168.0.0/16` range that the AP framework reserves, falling
     * back to the first non-loopback IPv4 if no match is found.
     */
    @Suppress("LoopWithTooManyJumpStatements") // The two `continue`s reject distinct, non-overlapping cases.
    private fun resolveGatewayIp(): InetAddress? {
        val candidates = mutableListOf<InetAddress>()
        for (iface in NetworkInterface.getNetworkInterfaces()) {
            if (iface.isLoopback || !iface.isUp) continue
            for (addr in iface.inetAddresses) {
                if (addr.isLoopbackAddress) continue
                if (addr.address.size != 4) continue
                candidates += addr
            }
        }
        // Prefer the AP framework's documented 192.168.x/y subnet.
        return candidates.firstOrNull { addr ->
            val bytes = addr.address
            bytes[0] == 192.toByte() && bytes[1] == 168.toByte()
        } ?: candidates.firstOrNull()
    }

    @Suppress("ReturnCount") // One early return per granted-permission shortcut.
    private fun hasRequiredPermissions(): Boolean {
        // ACCESS_FINE_LOCATION is the historical hotspot permission
        // required since API 26. NEARBY_WIFI_DEVICES (API 33+) can
        // substitute on newer devices but the platform still falls
        // back to the location grant for the local-only path. The
        // request happens in :app's onboarding (#20).
        val fineLocation =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (fineLocation) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val nearby =
                ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                    PackageManager.PERMISSION_GRANTED
            if (nearby) return true
        }
        return false
    }

    private data class ApInfo(
        val ssid: String,
        val passphrase: String,
        val frequencyMhz: Int?,
    )

    private companion object {
        const val TAG = "LibreDropWifiHotspot"
    }
}
