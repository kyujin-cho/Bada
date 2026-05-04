/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.service.receiver

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.github.kyujincho.wvmg.protocol.endpoint.DeviceType
import io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo
import io.github.kyujincho.wvmg.protocol.endpoint.TlvRecord
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * Central advertised-name policy for receiver identity surfaces.
 *
 * The same resolved name is fed into the receiver's canonical [EndpointInfo],
 * which in turn drives mDNS, BLE advertisement generation, and any future QR
 * path that reuses the receiver identity blob.
 */
public object AdvertisedDeviceNames {
    /**
     * Interop guardrail for the receiver's canonical Quick Share name.
     *
     * Keeping the visible [EndpointInfo.deviceName] under 19 UTF-8 bytes avoids
     * the advertisement-length regressions observed with longer receiver names
     * on stock Quick Share surfaces. Secondary BLE hints such as DCT may
     * truncate even further, but every receiver identity path starts from this
     * shared canonical budget.
     */
    public const val MAX_DEVICE_NAME_BYTES: Int = 19

    /** Last-resort label when every platform-derived candidate is blank or unavailable. */
    public const val DEFAULT_DEVICE_NAME: String = "Quick Share"

    @JvmStatic
    public fun getCustomName(context: Context): String? = AdvertisedDeviceNamePreferences.from(context).getCustomName()

    @JvmStatic
    public fun setCustomName(
        context: Context,
        rawName: String?,
    ): String? = AdvertisedDeviceNamePreferences.from(context).setCustomName(rawName)

    @JvmStatic
    public fun clearCustomName(context: Context) {
        AdvertisedDeviceNamePreferences.from(context).clearCustomName()
    }

    /** Resolve the effective advertised name using the documented fallback order. */
    @JvmStatic
    public fun resolve(context: Context): String = policy(context).resolve()

    internal fun createEndpointInfo(
        context: Context,
        previous: EndpointInfo? = null,
    ): EndpointInfo = policy(context).createEndpointInfo(previous)

    private fun policy(context: Context): AndroidAdvertisedDeviceNamePolicy {
        val app = context.applicationContext
        return AndroidAdvertisedDeviceNamePolicy(
            preferences = AdvertisedDeviceNamePreferences.from(app),
            sdkInt = Build.VERSION.SDK_INT,
            globalDeviceNameReader =
                GlobalDeviceNameReader {
                    Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)
                },
            bluetoothNameGateway = AndroidBluetoothNameGateway(app),
            modelName = { Build.MODEL },
            appLabel = {
                app.applicationInfo.loadLabel(app.packageManager)?.toString()
            },
        )
    }
}

internal class AndroidAdvertisedDeviceNamePolicy(
    private val preferences: AdvertisedDeviceNamePreferences,
    private val sdkInt: Int,
    private val globalDeviceNameReader: GlobalDeviceNameReader,
    private val bluetoothNameGateway: BluetoothNameGateway,
    private val modelName: () -> String?,
    private val appLabel: () -> String?,
) {
    fun resolve(): String =
        sequenceOf<() -> String?>(
            { preferences.getCustomName() },
            { AdvertisedDeviceNameSanitizer.sanitize(readSystemDeviceName()) },
            { AdvertisedDeviceNameSanitizer.sanitize(readBluetoothName()) },
            { AdvertisedDeviceNameSanitizer.sanitize(modelName()) },
            { AdvertisedDeviceNameSanitizer.sanitize(appLabel()) },
        ).mapNotNull { reader -> reader() }
            .firstOrNull()
            ?: AdvertisedDeviceNames.DEFAULT_DEVICE_NAME

    fun createEndpointInfo(previous: EndpointInfo?): EndpointInfo =
        EndpointInfo(
            version = previous?.version ?: DEFAULT_ENDPOINT_VERSION,
            hidden = false,
            deviceType = previous?.deviceType ?: DeviceType.PHONE,
            reserved = previous?.reserved ?: false,
            metadata = previous?.metadata?.copyOf() ?: randomMetadata(),
            deviceName = resolve(),
            tlvRecords = previous?.tlvRecords?.deepCopy() ?: emptyList(),
        )

    private fun readSystemDeviceName(): String? {
        if (sdkInt < Build.VERSION_CODES.N_MR1) return null
        return runCatching { globalDeviceNameReader.read() }.getOrNull()
    }

    private fun readBluetoothName(): String? {
        if (sdkInt >= Build.VERSION_CODES.S && !bluetoothNameGateway.hasConnectPermission()) {
            return null
        }
        return runCatching { bluetoothNameGateway.read() }.getOrNull()
    }

    private fun randomMetadata(): ByteArray = ByteArray(EndpointInfo.METADATA_LEN).also { SecureRandom().nextBytes(it) }

    private companion object {
        const val DEFAULT_ENDPOINT_VERSION: Int = 1
    }
}

internal class AdvertisedDeviceNameResolver(
    private val customName: String?,
    private val systemDeviceName: String?,
    private val bluetoothName: String?,
    private val modelName: String?,
    private val appLabel: String?,
) {
    fun resolve(): String {
        val candidates =
            listOf(
                customName,
                systemDeviceName,
                bluetoothName,
                modelName,
                appLabel,
                AdvertisedDeviceNames.DEFAULT_DEVICE_NAME,
            )
        for (candidate in candidates) {
            val sanitized = AdvertisedDeviceNameSanitizer.sanitize(candidate)
            if (sanitized != null) return sanitized
        }
        return AdvertisedDeviceNames.DEFAULT_DEVICE_NAME
    }
}

internal object AdvertisedDeviceNameSanitizer {
    fun sanitize(rawName: String?): String? {
        val trimmed = rawName?.trim()
        return if (trimmed.isNullOrEmpty() || !hasValidSurrogates(trimmed)) {
            null
        } else {
            truncateUtf8(trimmed, AdvertisedDeviceNames.MAX_DEVICE_NAME_BYTES)
                .takeIf { it.isNotEmpty() }
        }
    }

    private fun truncateUtf8(
        value: String,
        maxBytes: Int,
    ): String {
        val out = StringBuilder()
        var offset = 0
        var used = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            val next = String(Character.toChars(codePoint))
            val nextSize = next.toByteArray(StandardCharsets.UTF_8).size
            if (used + nextSize > maxBytes) break
            out.append(next)
            used += nextSize
            offset += Character.charCount(codePoint)
        }
        return out.toString()
    }

    private fun hasValidSurrogates(value: String): Boolean {
        var index = 0
        var valid = true
        while (index < value.length) {
            val ch = value[index]
            when {
                ch.isHighSurrogate() -> {
                    valid = index + 1 < value.length && value[index + 1].isLowSurrogate()
                    if (valid) {
                        index += 2
                    } else {
                        index = value.length
                    }
                }
                ch.isLowSurrogate() -> {
                    valid = false
                    index = value.length
                }
                else -> index += 1
            }
        }
        return valid
    }
}

internal fun interface GlobalDeviceNameReader {
    fun read(): String?
}

internal interface BluetoothNameGateway {
    fun hasConnectPermission(): Boolean

    fun read(): String?
}

internal class AndroidBluetoothNameGateway(
    private val context: Context,
) : BluetoothNameGateway {
    override fun hasConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override fun read(): String? =
        // The policy calls this only after checking BLUETOOTH_CONNECT on
        // Android 12+, and wraps the read so a late permission revocation
        // falls through to the next name candidate instead of crashing.
        context
            .getSystemService(BluetoothManager::class.java)
            ?.adapter
            ?.name
}

private fun List<TlvRecord>.deepCopy(): List<TlvRecord> =
    map { record -> TlvRecord(record.type, record.value.copyOf()) }
