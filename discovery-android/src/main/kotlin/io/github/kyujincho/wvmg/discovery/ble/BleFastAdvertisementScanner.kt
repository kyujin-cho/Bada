/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:Suppress("ReturnCount")

@file:android.annotation.SuppressLint("MissingPermission")

package io.github.kyujincho.wvmg.discovery.ble

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import io.github.kyujincho.wvmg.protocol.endpoint.BleServiceData
import io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Sender-side scanner for the stock Quick Share receiver fast-advertisement
 * BLE payload on service UUID `0xFEF3`.
 */
public class BleFastAdvertisementScanner(
    private val context: Context,
) {
    public fun scan(): Flow<Observation> =
        callbackFlow {
            if (!hasScanPermission()) {
                Log.i(TAG, "BLE fast-advertisement scan unavailable: missing runtime permission")
                close()
                return@callbackFlow
            }
            val scanner = resolveScanner()
            if (scanner == null) {
                Log.i(TAG, "BLE fast-advertisement scan unavailable")
                close()
                return@callbackFlow
            }

            val callback =
                object : ScanCallback() {
                    override fun onScanResult(
                        callbackType: Int,
                        result: ScanResult?,
                    ) {
                        result?.toObservation()?.let { observation ->
                            trySend(observation).isSuccess
                        }
                    }

                    override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                        results.orEmpty().forEach { result ->
                            result.toObservation()?.let { observation ->
                                trySend(observation).isSuccess
                            }
                        }
                    }
                }

            try {
                scanner.startScan(buildFilters(), buildSettings(), callback)
            } catch (e: SecurityException) {
                Log.w(TAG, "BLE fast-advertisement scan start rejected by platform", e)
                close()
                return@callbackFlow
            }
            awaitClose {
                runCatching { scanner.stopScan(callback) }
            }
        }

    public data class Observation(
        val endpointId: String,
        val endpointInfo: EndpointInfo,
        val advertiserAddress: String?,
        val rssi: Int?,
    )

    private fun ScanResult.toObservation(): Observation? {
        val scanRecord: ScanRecord = scanRecord ?: return null
        val serviceData = scanRecord.getServiceData(SERVICE_UUID) ?: return null
        val parsed = BleServiceData.parse(serviceData) ?: return null
        return Observation(
            endpointId = String(parsed.endpointId, Charsets.US_ASCII),
            endpointInfo = parsed.endpointInfo,
            advertiserAddress = device?.address,
            rssi = rssi,
        )
    }

    private fun resolveScanner(): BluetoothLeScanner? {
        val manager = context.applicationContext.getSystemService(BluetoothManager::class.java) ?: return null
        val adapter = manager.adapter ?: return null
        if (!adapter.isEnabled) return null
        return adapter.bluetoothLeScanner
    }

    private fun hasScanPermission(): Boolean =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> checkSPermission()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> hasLegacyLocationPermission()
            else -> true
        }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun checkSPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_SCAN,
        ) == PackageManager.PERMISSION_GRANTED

    private fun hasLegacyLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    private fun buildFilters(): List<ScanFilter> =
        listOf(
            ScanFilter
                .Builder()
                .setServiceUuid(SERVICE_UUID)
                .build(),
        )

    private fun buildSettings(): ScanSettings =
        ScanSettings
            .Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setReportDelay(0)
            .build()

    private companion object {
        private const val TAG: String = "WvmgBleFastScan"
        private val SERVICE_UUID: ParcelUuid =
            ParcelUuid.fromString(BleServiceData.SERVICE_UUID_128_STRING)
    }
}
