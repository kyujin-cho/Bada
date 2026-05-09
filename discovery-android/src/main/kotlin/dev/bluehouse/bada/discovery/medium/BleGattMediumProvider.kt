/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:android.annotation.SuppressLint("MissingPermission")
@file:Suppress("ReturnCount")

package dev.bluehouse.bada.discovery.medium

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dev.bluehouse.bada.protocol.medium.Medium
import dev.bluehouse.bada.protocol.medium.MediumProvider

/**
 * Capability-only provider for the BLE GATT initial-control path.
 *
 * BLE GATT is not a bandwidth-upgrade target in Bada; it is the already-open
 * bootstrap socket used before the normal Nearby negotiation can move the
 * transfer to Wi-Fi Direct / Hotspot / LAN. Registering this provider lets the
 * connection request accurately advertise that the current initial transport
 * is BLE.
 */
public class BleGattMediumProvider(
    private val context: Context,
) : MediumProvider {
    override val medium: Medium = Medium.BLE

    override fun isSupported(): Boolean {
        val appContext = context.applicationContext
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasConnectPermission(appContext)) return false
        val manager = appContext.getSystemService(BluetoothManager::class.java) ?: return false
        val adapter = manager.adapter ?: return false
        return adapter.isEnabled
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun hasConnectPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
}
