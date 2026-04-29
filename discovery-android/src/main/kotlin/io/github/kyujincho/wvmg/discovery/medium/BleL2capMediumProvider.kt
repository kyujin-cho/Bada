/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:Suppress(
    "MagicNumber", // 0xFF in the MAC formatter is a well-known byte mask.
    "ReturnCount", // Validation pipelines read cleanest with early `null` returns.
    "SwallowedException", // BleL2cap init failures collapse to a clean fallback path.
)

package io.github.kyujincho.wvmg.discovery.medium

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.kyujincho.wvmg.protocol.medium.Medium
import io.github.kyujincho.wvmg.protocol.medium.MediumProvider
import io.github.kyujincho.wvmg.protocol.medium.UpgradePathCredentials
import io.github.kyujincho.wvmg.protocol.medium.UpgradedTransport

/**
 * [MediumProvider] for the BLE L2CAP CoC (Connection-Oriented Channel)
 * medium — Phase 4 sub-issue #52.
 *
 * BLE L2CAP exposes a stream-socket-like API on the LE radio with a
 * few-Mbps ceiling and very low setup latency relative to classic
 * Bluetooth RFCOMM. The provider is gated on `Build.VERSION.SDK_INT >=
 * Q (29)` because `BluetoothAdapter.listenUsingInsecureL2capChannel()`
 * and `BluetoothDevice.createInsecureL2capChannel()` are API 29+.
 *
 * ### Server role ([prepareUpgrade])
 *
 * 1. Open a listening L2CAP server socket via
 *    `BluetoothAdapter.listenUsingInsecureL2capChannel()`.
 * 2. Read the kernel-assigned PSM via `BluetoothServerSocket.getPsm()`.
 * 3. Pair the PSM with the local adapter MAC address and return them
 *    as [UpgradePathCredentials.BleL2cap]. The credentials encoder in
 *    `:core-protocol` packs both into the BLUETOOTH wire slot (since
 *    `UpgradePathInfo.Medium` reserves wire 10 for BLE_L2CAP) using a
 *    `service_name` discriminator.
 *
 * The accepted [BluetoothSocket] is held alongside the listener and
 * surfaced through [BleL2capUpgradedTransport.acceptedSocketFactory] so
 * the orchestrator hook (#54) can drive `accept()` from its own
 * coroutine and wire the SecureChannel onto it.
 *
 * ### Client role ([adoptUpgrade])
 *
 * Resolves the peer-supplied MAC + PSM and calls
 * `BluetoothDevice.createInsecureL2capChannel(psm).connect()`. Returns
 * an [BleL2capUpgradedTransport] holding the live `BluetoothSocket` for
 * the framework to swap in.
 *
 * ### Throughput
 *
 * Empirically a few Mbps on BT 5+ hardware with a properly tuned MTU
 * + connection interval. The medium is preferred over RFCOMM for
 * file-transfer workloads (RFCOMM caps closer to 700 kbps) but worse
 * than Wi-Fi LAN / Hotspot / Direct. See `docs/testing/medium-ble-l2cap.md`
 * for the manual on-device measurement procedure.
 */
public class BleL2capMediumProvider(
    private val io: BluetoothL2capIo,
) {
    public constructor(context: Context) : this(DefaultBluetoothL2capIo(context))

    /**
     * Wrapped [MediumProvider] surface. The interface is exposed via
     * [asProvider] rather than direct inheritance so callers see the
     * BLE-L2CAP-specific constructor parameters at the type level
     * (instead of an opaque `MediumProvider`); the registration glue in
     * `:service-android` and `:app` calls [asProvider] when handing the
     * provider to [io.github.kyujincho.wvmg.protocol.medium.MediumRegistry].
     */
    public fun asProvider(): MediumProvider = providerImpl

    private val providerImpl =
        object : MediumProvider {
            override val medium: Medium = Medium.BLE_L2CAP

            override fun isSupported(): Boolean {
                // Hardware + permission + adapter-state pre-flight. The
                // API 29 gate is the load-bearing one — every other
                // check is a runtime nicety. We deliberately read
                // SDK_INT through [BluetoothL2capIo.apiLevel] instead
                // of `Build.VERSION.SDK_INT` so JVM unit tests can
                // simulate the gate; the registry calls every
                // provider's isSupported regardless of API level.
                if (io.apiLevel < Build.VERSION_CODES.Q) return false
                if (!io.hasBleHardware()) return false
                if (!io.hasConnectPermission()) return false
                if (!io.isBluetoothEnabled()) return false
                return true
            }

            override suspend fun prepareUpgrade(): UpgradePathCredentials? {
                if (!isSupported()) return null
                val mac = io.localMacBytes() ?: return null
                val listener = listenOnQ() ?: return null
                return try {
                    UpgradePathCredentials.BleL2cap(macAddress = mac, psm = listener.psm)
                } catch (
                    // BleL2cap's init validates MAC length and PSM
                    // range — if the platform handed us something out
                    // of bounds (e.g. PSM 0 from a broken kernel), we
                    // bail rather than surface a malformed upgrade.
                    @Suppress("TooGenericExceptionCaught") t: IllegalArgumentException,
                ) {
                    listener.close()
                    null
                }
            }

            @RequiresApi(Build.VERSION_CODES.Q)
            private fun listenOnQ(): BluetoothL2capIo.Listener? = io.listen()

            override suspend fun adoptUpgrade(credentials: UpgradePathCredentials): UpgradedTransport? {
                if (credentials !is UpgradePathCredentials.BleL2cap) return null
                if (!isSupported()) return null
                val macString = formatMacAddress(credentials.macAddress)
                val channel = connectOnQ(macString, credentials.psm) ?: return null
                return BleL2capUpgradedTransport(channel)
            }

            @RequiresApi(Build.VERSION_CODES.Q)
            private fun connectOnQ(
                macAddress: String,
                psm: Int,
            ): L2capChannel? = io.connect(macAddress, psm)
        }

    private companion object {
        /**
         * Format 6 raw bytes as `"AA:BB:CC:DD:EE:FF"` (uppercase, ROOT
         * locale so the hex digits never localize differently — e.g.
         * Turkish locale's dotted I issue).
         */
        fun formatMacAddress(bytes: ByteArray): String =
            bytes.joinToString(":") {
                String.format(java.util.Locale.ROOT, "%02X", it.toInt() and 0xFF)
            }
    }
}

/**
 * [UpgradedTransport] returned by [BleL2capMediumProvider.adoptUpgrade]
 * for the client (sender) side. Wraps an opened L2CAP CoC [L2capChannel]
 * (under the hood, a `BluetoothSocket` from
 * `BluetoothDevice.createInsecureL2capChannel(psm).connect()`).
 *
 * The orchestrator hook (#54) reads/writes through the channel's I/O
 * streams when rebuilding the SecureChannel around it.
 */
public data class BleL2capUpgradedTransport(
    /**
     * The opened L2CAP channel. The framework owns this once it has
     * been handed back; calling [L2capChannel.close] tears down the
     * underlying socket.
     */
    val channel: L2capChannel,
) : UpgradedTransport {
    override val medium: Medium = Medium.BLE_L2CAP
}
