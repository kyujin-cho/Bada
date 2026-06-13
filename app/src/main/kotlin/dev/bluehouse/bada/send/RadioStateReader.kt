/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.send

import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Lightweight Android helper that reads the current Bluetooth and Wi-Fi
 * state without requiring extra permissions (#209).
 *
 * - **Bluetooth**: [BluetoothManager.adapter]?.[isEnabled][android.bluetooth.BluetoothAdapter.isEnabled]
 *   does NOT require BLUETOOTH_CONNECT — no new permission is added. A
 *   null adapter (device has no BT hardware) is treated as "not enabled".
 *
 * - **Wi-Fi**: [ConnectivityManager.getNetworkCapabilities] with
 *   [NetworkCapabilities.TRANSPORT_WIFI] requires only ACCESS_NETWORK_STATE,
 *   which is already declared in the manifest.
 */
internal class RadioStateReader(
    private val context: Context,
) {
    /** `true` when the Bluetooth adapter exists and is currently on. */
    fun isBluetoothEnabled(): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter?.isEnabled == true
    }

    /** `true` when the active network is a Wi-Fi connection. */
    fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        return caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
}
