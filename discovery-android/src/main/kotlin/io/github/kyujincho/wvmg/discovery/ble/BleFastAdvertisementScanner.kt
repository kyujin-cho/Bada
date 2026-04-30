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
import io.github.kyujincho.wvmg.protocol.endpoint.DctAdvertisement
import io.github.kyujincho.wvmg.protocol.endpoint.DeviceType
import io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo
import io.github.kyujincho.wvmg.protocol.endpoint.NearbyServiceId
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Sender-side scanner for the stock Quick Share receiver fast-advertisement
 * BLE payloads on service UUIDs `0xFEF3` and `0xFC73`.
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

                    override fun onScanFailed(errorCode: Int) {
                        Log.w(TAG, "BLE fast-advertisement scan failed code=$errorCode")
                        close()
                    }
                }

            try {
                Log.w(
                    TAG,
                    "BLE fast-advertisement scan start uuid=${BleServiceData.SERVICE_UUID_128_STRING} " +
                        "dctUuid=${DctAdvertisement.SERVICE_UUID_128_STRING}",
                )
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
        val l2capPsm: Int?,
    )

    private fun ScanResult.toObservation(): Observation? {
        val scanRecord: ScanRecord =
            scanRecord ?: run {
                Log.w(TAG, "BLE fast-advertisement result without ScanRecord address=${device?.address}")
                return null
            }
        val fastData = scanRecord.getServiceData(SERVICE_UUID)
        val dctData = scanRecord.getServiceData(DCT_SERVICE_UUID)
        if (fastData == null && dctData == null) {
            Log.w(
                TAG,
                "BLE receiver-advertisement result without supported service data " +
                    "address=${device?.address} rssi=$rssi uuids=${scanRecord.serviceUuids}",
            )
            return null
        }
        val fastObservation =
            fastData?.let { serviceData ->
                parseFastServiceData(serviceData, device?.address, rssi).also { observation ->
                    if (observation == null) {
                        Log.w(
                            TAG,
                            "BLE fast-advertisement parse failed address=${device?.address} " +
                                "rssi=$rssi bytes=${serviceData.toHex()}",
                        )
                    } else {
                        Log.w(
                            TAG,
                            "BLE fast-advertisement observed endpointId=${observation.endpointId} " +
                                "address=${observation.advertiserAddress} rssi=${observation.rssi} " +
                                "psm=${observation.l2capPsm} bytes=${serviceData.toHex()}",
                        )
                    }
                }
            }
        val dctObservation =
            dctData?.let { serviceData ->
                parseDctServiceData(serviceData, device?.address, rssi).also { observation ->
                    if (observation == null) {
                        Log.w(
                            TAG,
                            "BLE DCT advertisement parse failed or ignored address=${device?.address} " +
                                "rssi=$rssi bytes=${serviceData.toHex()}",
                        )
                    } else {
                        Log.w(
                            TAG,
                            "BLE DCT advertisement observed endpointId=${observation.endpointId} " +
                                "address=${observation.advertiserAddress} rssi=${observation.rssi} " +
                                "psm=${observation.l2capPsm} name=${observation.endpointInfo.deviceName} " +
                                "bytes=${serviceData.toHex()}",
                        )
                    }
                }
            }
        return chooseObservation(
            fastObservation = fastObservation,
            dctObservation = dctObservation,
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
                .setServiceData(SERVICE_UUID, ByteArray(0), ByteArray(0))
                .build(),
            ScanFilter
                .Builder()
                .setServiceData(DCT_SERVICE_UUID, ByteArray(0), ByteArray(0))
                .build(),
        )

    private fun buildSettings(): ScanSettings =
        ScanSettings
            .Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setReportDelay(0)
            .build()

    public companion object {
        private const val TAG: String = "WvmgBleFastScan"
        private val SERVICE_UUID: ParcelUuid
            get() = ParcelUuid.fromString(BleServiceData.SERVICE_UUID_128_STRING)
        private val DCT_SERVICE_UUID: ParcelUuid
            get() = ParcelUuid.fromString(DctAdvertisement.SERVICE_UUID_128_STRING)
        private val EXPECTED_DCT_SERVICE_ID_HASH: ByteArray =
            DctAdvertisement.computeServiceIdHash(NearbyServiceId.VALUE)

        internal fun parseFastServiceData(
            serviceData: ByteArray,
            advertiserAddress: String?,
            rssi: Int?,
        ): Observation? {
            val parsed = BleServiceData.parse(serviceData) ?: return null
            val endpointId = String(parsed.endpointId, Charsets.US_ASCII)
            val l2capPsm = BleServiceData.parsePsmExtraField(serviceData)?.takeIf { it > 0 }
            return Observation(
                endpointId = endpointId,
                endpointInfo = parsed.endpointInfo,
                advertiserAddress = advertiserAddress,
                rssi = rssi,
                l2capPsm = l2capPsm,
            )
        }

        internal fun parseDctServiceData(
            serviceData: ByteArray,
            advertiserAddress: String?,
            rssi: Int?,
        ): Observation? {
            val parsed = DctAdvertisement.parse(serviceData) ?: return null
            if (!parsed.serviceIdHash.contentEquals(EXPECTED_DCT_SERVICE_ID_HASH)) return null
            val endpointId =
                DctAdvertisement.generateEndpointId(parsed.dedup, parsed.deviceName) ?: return null
            val l2capPsm = parsed.psm.takeIf { it > 0 }
            return Observation(
                endpointId = endpointId,
                endpointInfo = parsed.toEndpointInfo(),
                advertiserAddress = advertiserAddress,
                rssi = rssi,
                l2capPsm = l2capPsm,
            )
        }

        private fun chooseObservation(
            fastObservation: Observation?,
            dctObservation: Observation?,
        ): Observation? =
            when {
                dctObservation == null -> fastObservation
                fastObservation == null -> dctObservation
                dctObservation.l2capPsm != null -> dctObservation
                !dctObservation.endpointInfo.deviceName.isNullOrBlank() -> dctObservation
                else -> fastObservation
            }
    }
}

private fun DctAdvertisement.Parsed.toEndpointInfo(): EndpointInfo =
    EndpointInfo(
        version = 1,
        hidden = false,
        deviceType = DeviceType.PHONE,
        reserved = false,
        metadata = ByteArray(EndpointInfo.METADATA_LEN),
        deviceName = deviceName,
    )

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
