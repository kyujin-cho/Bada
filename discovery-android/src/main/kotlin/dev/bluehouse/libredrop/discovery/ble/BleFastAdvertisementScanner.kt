/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:Suppress("ReturnCount")

@file:android.annotation.SuppressLint("MissingPermission")

package dev.bluehouse.libredrop.discovery.ble

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
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dev.bluehouse.libredrop.protocol.endpoint.BleAdvertisement
import dev.bluehouse.libredrop.protocol.endpoint.BleAdvertisementHeader
import dev.bluehouse.libredrop.protocol.endpoint.BleServiceData
import dev.bluehouse.libredrop.protocol.endpoint.DctAdvertisement
import dev.bluehouse.libredrop.protocol.endpoint.DeviceType
import dev.bluehouse.libredrop.protocol.endpoint.EndpointInfo
import dev.bluehouse.libredrop.protocol.endpoint.NearbyServiceId
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Sender-side scanner for the stock Quick Share receiver fast-advertisement
 * BLE payloads on service UUIDs `0xFEF3` and `0xFC73`.
 */
public class BleFastAdvertisementScanner(
    private val context: Context,
) {
    @Suppress("CyclomaticComplexMethod")
    public fun scan(): Flow<Observation> =
        callbackFlow {
            val gattAdvertisementReader = BleGattAdvertisementReader(context.applicationContext)
            val gattReadAttempts = ConcurrentHashMap<String, Long>()
            val gattReadInFlight = ConcurrentHashMap.newKeySet<String>()
            val gattReadSucceeded = ConcurrentHashMap.newKeySet<String>()

            fun scheduleGattAdvertisementRead(
                advertiserAddress: String,
                rssi: Int?,
                slotCount: Int,
                advertisementKey: String,
                fallbackPsm: Int? = null,
                fallbackObservation: Observation? = null,
            ) {
                val key = "$advertiserAddress:$advertisementKey:$slotCount"
                if (key in gattReadSucceeded) return
                if (!gattReadInFlight.add(key)) return
                val now = SystemClock.elapsedRealtime()
                val lastAttempt = gattReadAttempts[key]
                if (lastAttempt != null && now - lastAttempt < GATT_READ_RETRY_MILLIS) {
                    gattReadInFlight.remove(key)
                    return
                }
                gattReadAttempts[key] = now
                launch {
                    try {
                        val readResult =
                            gattAdvertisementReader.read(
                                macAddress = advertiserAddress,
                                slotCount = slotCount,
                            )
                        val slotAdvertisements = readResult.slotAdvertisements
                        if (readResult.socketServiceAvailable && slotAdvertisements.isEmpty()) {
                            fallbackObservation?.let { observation ->
                                val verifiedObservation =
                                    observation.copy(
                                        l2capPsm = observation.l2capPsm ?: fallbackPsm,
                                        gattConnectable = true,
                                    )
                                Log.w(
                                    TAG,
                                    "BLE GATT socket service verified endpointId=${verifiedObservation.endpointId} " +
                                        "address=$advertiserAddress rssi=$rssi " +
                                        "psm=${verifiedObservation.l2capPsm} " +
                                        "name=${verifiedObservation.displayName ?: "<none>"} " +
                                        "nameSource=${verifiedObservation.displayNameSource?.logLabel ?: "<none>"}",
                                )
                                trySend(verifiedObservation).isSuccess
                            }
                        }
                        if (slotAdvertisements.isNotEmpty()) {
                            gattReadSucceeded.add(key)
                        } else if (readResult.socketServiceAvailable) {
                            gattReadSucceeded.add(key)
                        }
                        slotAdvertisements.forEach { slot ->
                            val slotDisplayName = slot.endpointInfo.deviceName.toDisplayNameOrNull()
                            val slotDisplayNameSource =
                                slotDisplayName?.let { DisplayNameSource.GATT_SLOT_ENDPOINT_INFO }
                            val slotL2capPsm = slot.l2capPsm ?: fallbackPsm
                            Log.w(
                                TAG,
                                "BLE GATT slot advertisement observed endpointId=${slot.endpointId} " +
                                    "address=$advertiserAddress rssi=$rssi " +
                                    "psm=$slotL2capPsm " +
                                    "name=${slotDisplayName ?: "<none>"} " +
                                    "nameSource=${slotDisplayNameSource?.logLabel ?: "<none>"}",
                            )
                            trySend(
                                Observation(
                                    endpointId = slot.endpointId,
                                    endpointInfo = slot.endpointInfo,
                                    advertiserAddress = advertiserAddress,
                                    rssi = rssi,
                                    l2capPsm = slotL2capPsm,
                                    gattConnectable = true,
                                    displayName = slotDisplayName,
                                    displayNameSource = slotDisplayNameSource,
                                ),
                            ).isSuccess
                        }
                    } finally {
                        gattReadInFlight.remove(key)
                    }
                }
            }

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
                        result?.toObservation(::scheduleGattAdvertisementRead)?.let { observation ->
                            trySend(observation).isSuccess
                        }
                    }

                    override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                        results.orEmpty().forEach { result ->
                            result.toObservation(::scheduleGattAdvertisementRead)?.let { observation ->
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
        val endpointId: String?,
        val endpointInfo: EndpointInfo?,
        val advertiserAddress: String?,
        val rssi: Int?,
        val l2capPsm: Int?,
        val gattConnectable: Boolean,
        val displayName: String? = null,
        val displayNameSource: DisplayNameSource? = null,
    )

    public enum class DisplayNameSource(
        public val logLabel: String,
    ) {
        FAST_ADVERTISEMENT_ENDPOINT_INFO("fast-advertisement-endpoint-info"),
        DCT_ADVERTISEMENT("dct-advertisement"),
        GATT_SLOT_ENDPOINT_INFO("gatt-slot-endpoint-info"),
        BLE_LOCAL_NAME("ble-local-name"),
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LongMethod")
    private fun ScanResult.toObservation(
        scheduleGattAdvertisementRead: (String, Int?, Int, String, Int?, Observation?) -> Unit,
    ): Observation? {
        val scanRecord: ScanRecord =
            scanRecord ?: run {
                Log.w(TAG, "BLE fast-advertisement result without ScanRecord address=${device?.address}")
                return null
            }
        val fastData = scanRecord.getServiceData(SERVICE_UUID)
        val dctData = scanRecord.getServiceData(DCT_SERVICE_UUID)
        val scanResultConnectable = isGattConnectable()
        if (fastData == null && dctData == null) {
            Log.w(
                TAG,
                "BLE receiver-advertisement result without supported service data " +
                    "address=${device?.address} rssi=$rssi uuids=${scanRecord.serviceUuids}",
            )
            return null
        }
        val localDisplayName = scanRecord.displayNameCandidate()
        val fastObservation =
            fastData?.let { serviceData ->
                val advertisementHeader = BleAdvertisementHeader.parse(serviceData)
                advertisementHeader?.let { header ->
                    device?.address?.let { address ->
                        if (scanResultConnectable && !header.supportsExtendedAdvertisement) {
                            scheduleGattAdvertisementRead(
                                address,
                                rssi,
                                header.numSlots,
                                header.advertisementHash.toHex(),
                                header.psm.takeIf { it > 0 },
                                null,
                            )
                        }
                    }
                }
                parseFastServiceData(
                    serviceData = serviceData,
                    advertiserAddress = device?.address,
                    rssi = rssi,
                    gattConnectable = scanResultConnectable,
                    fallbackDisplayName = localDisplayName?.value,
                    fallbackDisplayNameSource = localDisplayName?.source,
                ).also { observation ->
                    if (observation == null) {
                        if (advertisementHeader?.supportsExtendedAdvertisement != true) {
                            Log.w(
                                TAG,
                                "BLE fast-advertisement parse failed address=${device?.address} " +
                                    "rssi=$rssi bytes=${serviceData.toHex()}",
                            )
                        }
                    } else {
                        Log.w(
                            TAG,
                            "BLE fast-advertisement observed endpointId=${observation.endpointId ?: "<none>"} " +
                                "address=${observation.advertiserAddress} rssi=${observation.rssi} " +
                                "psm=${observation.l2capPsm} gatt=${observation.gattConnectable} " +
                                "name=${observation.displayName ?: "<none>"} " +
                                "nameSource=${observation.displayNameSource?.logLabel ?: "<none>"} " +
                                "bytes=${serviceData.toHex()}",
                        )
                        if (scanResultConnectable && observation.requiresGattVerification()) {
                            scheduleGattAdvertisementRead(
                                requireNotNull(observation.advertiserAddress),
                                observation.rssi,
                                DEFAULT_GATT_SLOT_READ_COUNT,
                                serviceData.contentKey(),
                                null,
                                observation,
                            )
                        }
                    }
                }
            }
        val dctObservation =
            dctData?.let { serviceData ->
                parseDctServiceData(
                    serviceData = serviceData,
                    advertiserAddress = device?.address,
                    rssi = rssi,
                    gattConnectable = scanResultConnectable,
                    fallbackDisplayName = localDisplayName?.value,
                    fallbackDisplayNameSource = localDisplayName?.source,
                ).also { observation ->
                    if (observation == null) {
                        Log.w(
                            TAG,
                            "BLE DCT advertisement parse failed or ignored address=${device?.address} " +
                                "rssi=$rssi bytes=${serviceData.toHex()}",
                        )
                    } else {
                        Log.w(
                            TAG,
                            "BLE DCT advertisement observed endpointId=${observation.endpointId ?: "<none>"} " +
                                "address=${observation.advertiserAddress} rssi=${observation.rssi} " +
                                "psm=${observation.l2capPsm} gatt=${observation.gattConnectable} " +
                                "name=${observation.displayName ?: "<none>"} " +
                                "nameSource=${observation.displayNameSource?.logLabel ?: "<none>"} " +
                                "bytes=${serviceData.toHex()}",
                        )
                        if (scanResultConnectable && observation.requiresGattVerification()) {
                            scheduleGattAdvertisementRead(
                                requireNotNull(observation.advertiserAddress),
                                observation.rssi,
                                DEFAULT_GATT_SLOT_READ_COUNT,
                                serviceData.contentKey(),
                                null,
                                observation,
                            )
                        }
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

    private fun buildSettings(): ScanSettings {
        val builder =
            ScanSettings
                .Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .setReportDelay(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Stock Nearby publishes the full identity as extended advertising data
            // when possible, keeping the legacy GATT header only as a fallback.
            builder.setLegacy(false)
            builder.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
        }
        return builder.build()
    }

    private fun ScanResult.isGattConnectable(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            isConnectable
        } else {
            device?.address != null
        }

    public companion object {
        private const val TAG: String = "LibreDropBleFastScan"
        private const val DEFAULT_GATT_SLOT_READ_COUNT: Int = 1
        private const val GATT_READ_RETRY_MILLIS: Long = 5_000L
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
            gattConnectable: Boolean = advertiserAddress != null,
            fallbackDisplayName: String? = null,
            fallbackDisplayNameSource: DisplayNameSource? = null,
        ): Observation? {
            val fallbackName = fallbackDisplayName.toDisplayNameOrNull()
            return parseLegacyGattHeader(
                serviceData = serviceData,
                advertiserAddress = advertiserAddress,
                rssi = rssi,
                gattConnectable = gattConnectable,
                fallbackDisplayName = fallbackName,
                fallbackDisplayNameSource = fallbackDisplayNameSource,
            ) ?: BleAdvertisement
                .parse(serviceData)
                ?.let { advertisement ->
                    parseNearbyBleAdvertisement(
                        advertisement = advertisement,
                        advertiserAddress = advertiserAddress,
                        rssi = rssi,
                        gattConnectable = gattConnectable,
                        fallbackDisplayName = fallbackName,
                        fallbackDisplayNameSource = fallbackDisplayNameSource,
                    )
                } ?: parseFramedFastServiceData(
                serviceData = serviceData,
                advertiserAddress = advertiserAddress,
                rssi = rssi,
                gattConnectable = gattConnectable,
                fallbackDisplayName = fallbackName,
                fallbackDisplayNameSource = fallbackDisplayNameSource,
            )
        }

        private fun parseLegacyGattHeader(
            serviceData: ByteArray,
            advertiserAddress: String?,
            rssi: Int?,
            gattConnectable: Boolean,
            fallbackDisplayName: String?,
            fallbackDisplayNameSource: DisplayNameSource?,
        ): Observation? {
            val header = BleAdvertisementHeader.parse(serviceData) ?: return null
            if (header.supportsExtendedAdvertisement) return null
            val l2capPsm = header.psm.takeIf { it > 0 }
            return Observation(
                endpointId = null,
                endpointInfo = null,
                advertiserAddress = advertiserAddress,
                rssi = rssi,
                l2capPsm = l2capPsm,
                gattConnectable = gattConnectable && advertiserAddress != null && l2capPsm != null,
                displayName = fallbackDisplayName,
                displayNameSource = fallbackDisplayName?.let { fallbackDisplayNameSource },
            )
        }

        private fun parseFramedFastServiceData(
            serviceData: ByteArray,
            advertiserAddress: String?,
            rssi: Int?,
            gattConnectable: Boolean,
            fallbackDisplayName: String?,
            fallbackDisplayNameSource: DisplayNameSource?,
        ): Observation? {
            val parsed = BleServiceData.parse(serviceData) ?: return null
            val endpointId = String(parsed.endpointId, Charsets.US_ASCII)
            val l2capPsm = BleServiceData.parsePsmExtraField(serviceData)?.takeIf { it > 0 }
            val endpointName = parsed.endpointInfo.deviceName.toDisplayNameOrNull()
            val displayName = endpointName ?: fallbackDisplayName
            return Observation(
                endpointId = endpointId,
                endpointInfo = parsed.endpointInfo,
                advertiserAddress = advertiserAddress,
                rssi = rssi,
                l2capPsm = l2capPsm,
                gattConnectable = gattConnectable && advertiserAddress != null && l2capPsm != null,
                displayName = displayName,
                displayNameSource =
                    when {
                        endpointName != null -> DisplayNameSource.FAST_ADVERTISEMENT_ENDPOINT_INFO
                        displayName != null -> fallbackDisplayNameSource
                        else -> null
                    },
            )
        }

        private fun parseNearbyBleAdvertisement(
            advertisement: BleAdvertisement.Parsed,
            advertiserAddress: String?,
            rssi: Int?,
            gattConnectable: Boolean,
            fallbackDisplayName: String?,
            fallbackDisplayNameSource: DisplayNameSource?,
        ): Observation? {
            val serviceData =
                when {
                    advertisement.fastAdvertisement -> advertisement.data
                    advertisement.serviceIdHash?.contentEquals(NearbyServiceId.hashPrefix) == true ->
                        advertisement.data

                    else -> return null
                }
            return parseNearbyBleAdvertisementBody(
                serviceData = serviceData,
                psm = advertisement.psm,
                advertiserAddress = advertiserAddress,
                rssi = rssi,
                gattConnectable = gattConnectable,
                fallbackDisplayName = fallbackDisplayName,
                fallbackDisplayNameSource = fallbackDisplayNameSource,
            )
        }

        @Suppress("LongParameterList")
        private fun parseNearbyBleAdvertisementBody(
            serviceData: ByteArray,
            psm: Int,
            advertiserAddress: String?,
            rssi: Int?,
            gattConnectable: Boolean,
            fallbackDisplayName: String?,
            fallbackDisplayNameSource: DisplayNameSource?,
        ): Observation? {
            val parsed = BleServiceData.parse(serviceData) ?: return null
            val endpointId = String(parsed.endpointId, Charsets.US_ASCII)
            val endpointName = parsed.endpointInfo.deviceName.toDisplayNameOrNull()
            val displayName = endpointName ?: fallbackDisplayName
            val l2capPsm = psm.takeIf { it > 0 }
            return Observation(
                endpointId = endpointId,
                endpointInfo = parsed.endpointInfo,
                advertiserAddress = advertiserAddress,
                rssi = rssi,
                l2capPsm = l2capPsm,
                gattConnectable = gattConnectable && advertiserAddress != null && l2capPsm != null,
                displayName = displayName,
                displayNameSource =
                    when {
                        endpointName != null -> DisplayNameSource.FAST_ADVERTISEMENT_ENDPOINT_INFO
                        displayName != null -> fallbackDisplayNameSource
                        else -> null
                    },
            )
        }

        internal fun parseDctServiceData(
            serviceData: ByteArray,
            advertiserAddress: String?,
            rssi: Int?,
            gattConnectable: Boolean = advertiserAddress != null,
            fallbackDisplayName: String? = null,
            fallbackDisplayNameSource: DisplayNameSource? = null,
        ): Observation? {
            val parsed = DctAdvertisement.parse(serviceData) ?: return null
            if (!parsed.serviceIdHash.contentEquals(EXPECTED_DCT_SERVICE_ID_HASH)) return null
            val endpointId =
                DctAdvertisement.generateEndpointId(parsed.dedup, parsed.deviceName) ?: return null
            val l2capPsm = parsed.psm.takeIf { it > 0 }
            val dctName = parsed.deviceName.toDisplayNameOrNull()
            val fallbackName = fallbackDisplayName.toDisplayNameOrNull()
            val displayName = dctName ?: fallbackName
            return Observation(
                endpointId = endpointId,
                endpointInfo = parsed.toEndpointInfo(),
                advertiserAddress = advertiserAddress,
                rssi = rssi,
                l2capPsm = l2capPsm,
                gattConnectable = gattConnectable && advertiserAddress != null && l2capPsm != null,
                displayName = displayName,
                displayNameSource =
                    when {
                        dctName != null -> DisplayNameSource.DCT_ADVERTISEMENT
                        displayName != null -> fallbackDisplayNameSource
                        else -> null
                    },
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
                !dctObservation.endpointInfo?.deviceName.isNullOrBlank() -> dctObservation
                else -> fastObservation
            }
    }
}

private fun BleFastAdvertisementScanner.Observation.requiresGattVerification(): Boolean =
    advertiserAddress != null &&
        l2capPsm == null &&
        !gattConnectable

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

private fun ByteArray.contentKey(): String = "$size:${contentHashCode().toString(radix = 16)}"

private data class DisplayNameCandidate(
    val value: String,
    val source: BleFastAdvertisementScanner.DisplayNameSource,
)

private fun ScanRecord.displayNameCandidate(): DisplayNameCandidate? =
    // Only trust the current advertisement's local-name field here. BluetoothDevice.name
    // is a cached alias gated by BLUETOOTH_CONNECT and can be stale in degraded scan-only mode.
    deviceName
        .toDisplayNameOrNull()
        ?.let { DisplayNameCandidate(it, BleFastAdvertisementScanner.DisplayNameSource.BLE_LOCAL_NAME) }

private fun String?.toDisplayNameOrNull(): String? =
    this
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
