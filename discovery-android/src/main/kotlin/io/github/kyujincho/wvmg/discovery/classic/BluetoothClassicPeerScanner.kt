/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:Suppress("ReturnCount")

@file:android.annotation.SuppressLint("MissingPermission")

package io.github.kyujincho.wvmg.discovery.classic

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import io.github.kyujincho.wvmg.protocol.endpoint.BluetoothDeviceName
import io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo
import io.github.kyujincho.wvmg.protocol.endpoint.NearbyServiceId
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Bluetooth Classic discovery scanner for Nearby / Quick Share device-name
 * advertisements.
 *
 * Stock Quick Share can expose sender/receiver identity through the local
 * adapter name. This scanner watches the platform discovery broadcast stream,
 * parses Nearby-shaped device names, and surfaces the MAC address needed for
 * the RFCOMM bootstrap client.
 */
public class BluetoothClassicPeerScanner internal constructor(
    private val bluetooth: BluetoothClassicPeerScannerIo,
) {
    public constructor(context: Context) : this(
        bluetooth = DefaultBluetoothClassicPeerScannerIo(context.applicationContext),
    )

    public fun scan(): Flow<Observation> =
        callbackFlow {
            if (!bluetooth.isAvailable()) {
                Log.i(TAG, "Bluetooth Classic discovery unavailable")
                close()
                return@callbackFlow
            }

            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context?,
                        intent: Intent?,
                    ) {
                        when (intent?.action) {
                            BluetoothDevice.ACTION_FOUND -> {
                                val device = intent.bluetoothDevice()
                                val advertisedName =
                                    device?.name
                                        ?: intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                                        ?: return
                                val parsed = BluetoothDeviceName.parse(advertisedName) ?: return
                                if (!parsed.serviceIdHash.contentEquals(NearbyServiceId.hashPrefix)) return
                                val macAddress = device?.address ?: return
                                trySend(
                                    Observation(
                                        endpointId = parsed.endpointId.toAsciiLabel(),
                                        endpointInfo = parsed.endpointInfo,
                                        macAddress = macAddress,
                                        advertisedName = advertisedName,
                                    ),
                                ).isSuccess
                            }
                            BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                                bluetooth.restartDiscovery()
                            }
                        }
                    }
                }

            bluetooth.registerReceiver(receiver)
            bluetooth.restartDiscovery()
            awaitClose {
                bluetooth.unregisterReceiver(receiver)
                bluetooth.stopDiscovery()
            }
        }

    public data class Observation(
        val endpointId: String,
        val endpointInfo: EndpointInfo,
        val macAddress: String,
        val advertisedName: String,
    )

    public companion object {
        internal const val TAG: String = "WvmgBtScan"
    }
}

internal interface BluetoothClassicPeerScannerIo {
    fun isAvailable(): Boolean

    fun registerReceiver(receiver: BroadcastReceiver)

    fun unregisterReceiver(receiver: BroadcastReceiver)

    fun restartDiscovery()

    fun stopDiscovery()
}

private class DefaultBluetoothClassicPeerScannerIo(
    private val context: Context,
) : BluetoothClassicPeerScannerIo {
    override fun isAvailable(): Boolean {
        val pm = context.packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) return false
        if (!hasScanPermission()) return false
        if (!hasConnectPermission()) return false
        if (!hasLegacyLocationPermission()) return false
        val adapter = adapter() ?: return false
        return adapter.isEnabled
    }

    override fun registerReceiver(receiver: BroadcastReceiver) {
        val filter =
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
        context.registerReceiver(receiver, filter)
    }

    override fun unregisterReceiver(receiver: BroadcastReceiver) {
        runCatching { context.unregisterReceiver(receiver) }
    }

    override fun restartDiscovery() {
        val adapter = adapter() ?: return
        runCatching { adapter.cancelDiscovery() }
        runCatching { adapter.startDiscovery() }
    }

    override fun stopDiscovery() {
        val adapter = adapter() ?: return
        runCatching { adapter.cancelDiscovery() }
    }

    private fun adapter(): BluetoothAdapter? {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return null
        return manager.adapter
    }

    private fun hasScanPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return checkSPermission(Manifest.permission.BLUETOOTH_SCAN)
    }

    private fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return checkSPermission(Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun hasLegacyLocationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun checkSPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            permission,
        ) == PackageManager.PERMISSION_GRANTED
}

private fun Intent.bluetoothDevice(): BluetoothDevice? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }

private fun ByteArray.toAsciiLabel(): String = String(this, Charsets.US_ASCII)
