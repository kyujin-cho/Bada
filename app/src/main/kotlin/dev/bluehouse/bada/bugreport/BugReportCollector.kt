/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.bugreport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.PixelCopy
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import dev.bluehouse.bada.discovery.DiscoveryDiagnostics
import dev.bluehouse.bada.discovery.diagnostics.DiagnosticLog
import dev.bluehouse.bada.service.receiver.ActiveBleScannerHolder
import dev.bluehouse.bada.service.receiver.MdnsVisibilityOverrideHolder
import dev.bluehouse.bada.service.receiver.OutboundSessionActiveHolder
import dev.bluehouse.bada.service.receiver.QrSessionActiveHolder
import dev.bluehouse.bada.service.receiver.ReceiverAdvertisementStateHolder
import dev.bluehouse.bada.service.receiver.ReceiverBugReportDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlin.math.max

internal data class PreparedBugReport(
    val suggestedName: String,
    val tempZip: File,
)

internal class BugReportCollector(
    private val context: Context,
) {
    suspend fun collect(
        activity: AppCompatActivity,
        includeWifiBssid: Boolean,
    ): PreparedBugReport =
        withContext(Dispatchers.IO) {
            val now = Instant.now()
            val failures = linkedMapOf<String, String>()
            val packageInfo = readPackageInfo()

            val screenshotResult = captureScreenshot(activity)
            if (screenshotResult.failureReason != null) {
                failures["screenshot"] = screenshotResult.failureReason
            }

            val outboundLogBytes = readOptionalExternalFile("bada-outbound.log", failures, "outbound_log")
            val diagnosticsLogBytes = collectDiagnosticsLog(includeWifiBssid, failures)
            val ringbufferText =
                DiagnosticLog.dumpRecent(
                    maxAgeMillis = DiagnosticLog.DEFAULT_MAX_AGE_MILLIS,
                    nowMillis = System.currentTimeMillis(),
                )
            if (ringbufferText.isBlank()) {
                failures["ringbuffer"] = "not_available: no buffered lines in the last 15 minutes"
            }

            val discoverySnapshot = ReceiverBugReportDiagnostics.discoverySnapshot()
            if (discoverySnapshot == null) {
                failures["discovery_snapshot"] = "not_available: receiver discovery service is not running"
            }
            failures["recent_crash_markers"] = "not_available: crash marker persistence is not implemented"

            val entries =
                listOf(
                    BugReportArchiveEntry("README.txt", buildReadme().encodeToByteArray()),
                    BugReportArchiveEntry(
                        "metadata.json",
                        buildMetadataJson(
                            generatedAt = now,
                            includeWifiBssid = includeWifiBssid,
                            screenshotRedacted = screenshotResult.redacted,
                            failures = failures,
                        ).encodeToByteArray(),
                    ),
                    BugReportArchiveEntry(
                        "device.txt",
                        buildDeviceDiagnostics(
                            packageInfo = packageInfo,
                            includeWifiBssid = includeWifiBssid,
                        ).encodeToByteArray(),
                    ),
                    BugReportArchiveEntry(
                        "permissions.txt",
                        buildPermissionsSnapshot().encodeToByteArray(),
                    ),
                    BugReportArchiveEntry(
                        "discovery.txt",
                        buildDiscoverySnapshot(discoverySnapshot).encodeToByteArray(),
                    ),
                    BugReportArchiveEntry(
                        "logs/outbound.log",
                        outboundLogBytes ?: "not_available\n".encodeToByteArray(),
                    ),
                    BugReportArchiveEntry(
                        "logs/diagnostics.log",
                        diagnosticsLogBytes
                            ?: "${failures["diagnostics_log"] ?: "not_available"}\n".encodeToByteArray(),
                    ),
                    BugReportArchiveEntry(
                        "logs/ringbuffer.txt",
                        ringbufferText.ifBlank { "not_available\n" }.encodeToByteArray(),
                    ),
                    BugReportArchiveEntry(
                        "screenshot.png",
                        screenshotResult.pngBytes,
                    ),
                )

            val tempZip = File.createTempFile("bada-bugreport-", ".zip", context.cacheDir)
            BugReportArchiveWriter.write(tempZip, entries)
            PreparedBugReport(
                suggestedName = BugReportFileNaming.archiveName(now),
                tempZip = tempZip,
            )
        }

    fun writeToUri(
        report: PreparedBugReport,
        destination: Uri,
    ) {
        context.contentResolver.openOutputStream(destination)?.use { out ->
            FileInputStream(report.tempZip).use { input ->
                input.copyTo(out)
            }
        } ?: error("Could not open output stream for $destination")
    }

    private fun buildReadme(): String =
        """
        Bada bug report archive
        
        Contents:
        - metadata.json: machine-readable collection summary and per-source failures
        - device.txt: device, app, locale, battery, Bluetooth, and network snapshot
        - permissions.txt: granted/denied runtime permissions
        - discovery.txt: receiver/discovery runtime state
        - logs/outbound.log: on-disk outbound diagnostic log when available
        - logs/diagnostics.log: persisted BLE/discovery diagnostics (rotated), incl. L2CAP/GATT bootstrap detail; only when more-identifying details consent is given
        - logs/ringbuffer.txt: recent in-memory diagnostics from the last 15 minutes
        - screenshot.png: screenshot of the current Bada activity, or a placeholder when redacted
        
        This archive stays on-device until you choose where to save it and manually attach it to a bug report.
        """.trimIndent()

    private fun buildMetadataJson(
        generatedAt: Instant,
        includeWifiBssid: Boolean,
        screenshotRedacted: Boolean,
        failures: Map<String, String>,
    ): String {
        val root = JSONObject()
        root.put("generated_at_utc", generatedAt.toString())
        root.put("application_id", context.packageName)
        root.put("include_wifi_bssid", includeWifiBssid)
        root.put("screenshot_redacted", screenshotRedacted)
        val failureObject = JSONObject()
        failures.forEach { (key, value) -> failureObject.put(key, value) }
        root.put("collection_failures", failureObject)
        return root.toString(2)
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun buildDeviceDiagnostics(
        packageInfo: PackageInfo?,
        includeWifiBssid: Boolean,
    ): String {
        val batteryIntent =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryLevel = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val batteryScale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPercent =
            if (batteryLevel >= 0 && batteryScale > 0) {
                batteryLevel * BATTERY_PERCENT_SCALE / batteryScale
            } else {
                null
            }
        val batteryStatus =
            batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val locale = Locale.getDefault()
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val linkProperties = connectivity?.getLinkProperties(connectivity.activeNetwork)
        val wifiSnapshot = readWifiDiagnostics(includeWifiBssid)
        val bluetoothSnapshot = readBluetoothDiagnostics()
        val externalDir = context.getExternalFilesDir(null)
        val isDebuggable =
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val buildType =
            if (context.packageName.endsWith(".debug")) {
                "debug"
            } else {
                "release"
            }

        val socDiagnostics = BugReportSocDiagnostics.read()

        return buildString {
            appendLine("manufacturer=${Build.MANUFACTURER}")
            appendLine("brand=${Build.BRAND}")
            appendLine("model=${Build.MODEL}")
            appendLine("device=${Build.DEVICE}")
            appendLine("product=${Build.PRODUCT}")
            appendLine("fingerprint=${Build.FINGERPRINT}")
            appendLine("hardware=${Build.HARDWARE}")
            appendLine("board=${Build.BOARD}")
            appendLine("socManufacturer=${socDiagnostics.manufacturer}")
            appendLine("socModel=${socDiagnostics.model}")
            appendLine("release=${Build.VERSION.RELEASE}")
            appendLine("sdkInt=${Build.VERSION.SDK_INT}")
            appendLine("incremental=${Build.VERSION.INCREMENTAL}")
            appendLine("codename=${Build.VERSION.CODENAME}")
            appendLine("applicationId=${context.packageName}")
            appendLine("versionName=${packageInfo?.versionName ?: "unknown"}")
            appendLine("versionCode=${packageInfo?.let(PackageInfoCompat::getLongVersionCode) ?: 0L}")
            appendLine("debug=$isDebuggable")
            appendLine("buildType=$buildType")
            appendLine("installer=${readInstallSource()}")
            appendLine("packageLastUpdateMillis=${packageInfo?.lastUpdateTime ?: -1L}")
            appendLine("locale=${locale.toLanguageTag()}")
            appendLine("timezone=${TimeZone.getDefault().id}")
            appendLine("uptimeMillis=${SystemClock.elapsedRealtime()}")
            appendLine("batteryPercent=${batteryPercent ?: "not_available"}")
            appendLine(
                "batteryCharging=" +
                    (
                        batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                            batteryStatus == BatteryManager.BATTERY_STATUS_FULL
                    ),
            )
            appendLine("powerSaveMode=${powerManager?.isPowerSaveMode ?: false}")
            appendLine("wifiSsid=${wifiSnapshot.ssid}")
            appendLine("wifiBssid=${wifiSnapshot.bssid}")
            appendLine("wifiLinkSpeedMbps=${wifiSnapshot.linkSpeedMbps ?: "not_available"}")
            appendLine("wifiFrequencyMhz=${wifiSnapshot.frequencyMhz ?: "not_available"}")
            appendLine("ipFamilies=${describeIpFamilies(linkProperties?.linkAddresses.orEmpty())}")
            appendLine("bluetoothState=${bluetoothSnapshot.state}")
            appendLine("bluetoothName=${bluetoothSnapshot.name}")
            appendLine("bondedDeviceCount=${bluetoothSnapshot.bondedDeviceCount ?: "not_available"}")
            appendLine("bleFeatures=${bluetoothSnapshot.bleFeatures}")
            appendLine(
                "receiverServiceLikelyRunning=" +
                    ReceiverBugReportDiagnostics.isReceiverServiceLikelyRunning(),
            )
            appendLine("receiverAdvertising=${ReceiverAdvertisementStateHolder.isAdvertising}")
            appendLine("outboundSessionActive=${OutboundSessionActiveHolder.isActive}")
            appendLine("mdnsOverrideActive=${MdnsVisibilityOverrideHolder.isActive}")
            appendLine("qrSessionActive=${QrSessionActiveHolder.isActive}")
            appendLine("externalFilesDirFreeBytes=${externalDir?.usableSpace ?: -1L}")
            appendLine("downloadsState=${Environment.getExternalStorageState()}")
        }
    }

    private fun buildPermissionsSnapshot(): String {
        val packageInfo = readPackageInfo(flags = PackageManager.GET_PERMISSIONS.toLong())
        val permissions =
            packageInfo?.requestedPermissions?.sorted().orEmpty()
        if (permissions.isEmpty()) {
            return "not_available: no requested permissions reported by PackageManager"
        }
        return buildString {
            permissions.forEach { permission ->
                val granted =
                    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
                append(permission)
                append('=')
                append(if (granted) "granted" else "denied")
                append('\n')
            }
        }.trimEnd()
    }

    private fun buildDiscoverySnapshot(discoveryDiagnostics: DiscoveryDiagnostics?): String =
        buildString {
            appendLine("receiverAdvertising=${ReceiverAdvertisementStateHolder.isAdvertising}")
            appendLine("outboundSessionActive=${OutboundSessionActiveHolder.isActive}")
            appendLine("mdnsOverrideActive=${MdnsVisibilityOverrideHolder.isActive}")
            appendLine("qrSessionActive=${QrSessionActiveHolder.isActive}")
            appendLine("bleScannerPresent=${ActiveBleScannerHolder.current() != null}")
            if (discoveryDiagnostics == null) {
                appendLine("discoverySnapshot=not_available")
            } else {
                appendLine("advertiseBoundAddress=${discoveryDiagnostics.advertiseBoundAddress?.hostAddress ?: "none"}")
                appendLine("browseBoundAddress=${discoveryDiagnostics.browseBoundAddress?.hostAddress ?: "none"}")
                appendLine("multicastLockHeld=${discoveryDiagnostics.multicastLockHeld}")
                appendLine("advertising=${discoveryDiagnostics.advertising}")
                appendLine("browsing=${discoveryDiagnostics.browsing}")
                appendLine("recentEvents=")
                discoveryDiagnostics.recentEvents.forEach { event ->
                    appendLine("  - ${event.timestampMillis} ${event.kind.name}:${event.instanceName}")
                }
            }
        }.trimEnd()

    private fun readPackageInfo(flags: Long = 0L): PackageInfo? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(flags),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, flags.toInt())
            }
        }.getOrNull()

    @Suppress("DEPRECATION")
    private fun readInstallSource(): String =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                context.packageManager.getInstallerPackageName(context.packageName)
            } ?: "unknown"
        }.getOrElse { "unknown" }

    private suspend fun captureScreenshot(activity: AppCompatActivity): ScreenshotResult {
        if (BugReportSensitiveScreens.shouldRedact(activity)) {
            return ScreenshotResult(
                pngBytes = renderPlaceholderPng("Screenshot redacted on a sensitive screen."),
                redacted = true,
                failureReason = null,
            )
        }

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val decorView = activity.window.decorView
                val width = max(decorView.width, 1)
                val height = max(decorView.height, 1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val handler = Handler(Looper.getMainLooper())
                PixelCopy.request(
                    activity.window,
                    bitmap,
                    { result ->
                        if (result == PixelCopy.SUCCESS) {
                            continuation.resume(
                                ScreenshotResult(
                                    pngBytes = bitmap.toPngBytes(),
                                    redacted = false,
                                    failureReason = null,
                                ),
                            )
                        } else {
                            continuation.resume(
                                ScreenshotResult(
                                    pngBytes = renderPlaceholderPng("Screenshot unavailable."),
                                    redacted = false,
                                    failureReason = "not_available: PixelCopy failed with code=$result",
                                ),
                            )
                        }
                    },
                    handler,
                )
            }
        }
    }

    private fun readOptionalExternalFile(
        fileName: String,
        failures: MutableMap<String, String>,
        failureKey: String,
    ): ByteArray? {
        val file = context.getExternalFilesDir(null)?.resolve(fileName)
        if (file == null || !file.exists()) {
            failures[failureKey] = "not_available: $fileName does not exist"
            return null
        }
        return runCatching { file.readBytes() }.getOrElse { t ->
            failures[failureKey] = "not_available: could not read $fileName (${t.message})"
            null
        }
    }

    /**
     * `bada-diagnostics.log` carries BLE/discovery detail that includes
     * nearby-device identifiers (peer BLE MACs, advertisement payloads), so it
     * ships only when the user opts in via the same more-identifying-details
     * consent that gates the Wi-Fi BSSID (#201).
     */
    private fun collectDiagnosticsLog(
        includeWifiBssid: Boolean,
        failures: MutableMap<String, String>,
    ): ByteArray? {
        if (!includeWifiBssid) {
            failures["diagnostics_log"] =
                "redacted: contains nearby-device identifiers; not collected without consent"
            return null
        }
        // The sink writes asynchronously off the BLE/GATT threads, so drain any
        // queued lines to disk before reading the file back.
        DiagnosticLog.flushFileSink()
        return readRotatedExternalFile("bada-diagnostics.log", failures, "diagnostics_log")
    }

    /**
     * Reads a size-rotated log written by [DiagnosticLog]'s file sink:
     * the single `<name>.1` backup (older lines) followed by `<name>`
     * (newer lines), concatenated in chronological order. Returns `null`
     * and records a failure when neither file exists.
     */
    private fun readRotatedExternalFile(
        fileName: String,
        failures: MutableMap<String, String>,
        failureKey: String,
    ): ByteArray? {
        val dir = context.getExternalFilesDir(null)
        val ordered =
            if (dir == null) {
                emptyList()
            } else {
                listOf(File(dir, "$fileName.1"), File(dir, fileName)).filter { it.exists() }
            }
        if (ordered.isEmpty()) {
            failures[failureKey] = "not_available: $fileName does not exist"
            return null
        }
        return runCatching {
            ByteArrayOutputStream().use { out ->
                ordered.forEach { out.write(it.readBytes()) }
                out.toByteArray()
            }
        }.getOrElse { t ->
            failures[failureKey] = "not_available: could not read $fileName (${t.message})"
            null
        }
    }

    @SuppressLint("MissingPermission", "HardwareIds", "MissingPermission")
    private fun readWifiDiagnostics(includeWifiBssid: Boolean): WifiDiagnostics {
        val wifi =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return unavailableWifiDiagnostics(includeWifiBssid = true)
        return runCatching {
            val info = wifi.connectionInfo
            val ssid = sanitizeWifiSsid(info?.ssid) ?: "not_available"
            val bssid =
                when {
                    !includeWifiBssid -> "<redacted>"
                    info?.bssid.isNullOrBlank() -> "not_available"
                    else -> info?.bssid ?: "not_available"
                }
            WifiDiagnostics(
                ssid = ssid,
                bssid = bssid,
                linkSpeedMbps = info?.linkSpeed?.takeIf { it > 0 },
                frequencyMhz = info?.frequency?.takeIf { it > 0 },
            )
        }.getOrElse {
            unavailableWifiDiagnostics(includeWifiBssid)
        }
    }

    @SuppressLint("MissingPermission")
    private fun readBluetoothDiagnostics(): BluetoothDiagnostics {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null) {
            return if (manager == null) {
                BluetoothDiagnostics("not_available", "not_available", null, "not_available")
            } else {
                BluetoothDiagnostics("absent", "absent", null, "not_available")
            }
        }
        val packageManager = context.packageManager
        val bleFeatures =
            listOf(
                "le2m=${packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)}",
                "leExtended=${packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)}",
                "l2capCoc=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q}",
            ).joinToString(separator = ",")
        val connectGranted =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        val redactedName =
            if (connectGranted) {
                adapter.name?.let { clampBluetoothName(it) } ?: "not_available"
            } else {
                "<redacted: missing BLUETOOTH_CONNECT>"
            }
        val bondedCount =
            if (connectGranted) {
                runCatching { adapter.bondedDevices?.size }.getOrNull()
            } else {
                null
            }
        return BluetoothDiagnostics(
            state = bluetoothStateLabel(adapter.state),
            name = redactedName,
            bondedDeviceCount = bondedCount,
            bleFeatures = bleFeatures,
        )
    }

    private fun clampBluetoothName(name: String): String =
        when {
            name.isBlank() -> "not_available"
            name.length <= BLUETOOTH_NAME_VISIBLE_PREFIX_LENGTH -> "*".repeat(name.length)
            else ->
                "${name.take(BLUETOOTH_NAME_VISIBLE_PREFIX_LENGTH)}" +
                    "…(${name.length} chars)"
        }

    private fun bluetoothStateLabel(state: Int): String =
        when (state) {
            BluetoothAdapter.STATE_ON -> "on"
            BluetoothAdapter.STATE_OFF -> "off"
            BluetoothAdapter.STATE_TURNING_ON -> "turning_on"
            BluetoothAdapter.STATE_TURNING_OFF -> "turning_off"
            else -> "unknown($state)"
        }

    private fun describeIpFamilies(addresses: List<LinkAddress>): String {
        val hasIpv4 = addresses.any { it.address.hostAddress?.contains('.') == true }
        val hasIpv6 = addresses.any { it.address.hostAddress?.contains(':') == true }
        return "ipv4=$hasIpv4,ipv6=$hasIpv6"
    }

    private fun renderPlaceholderPng(label: String): ByteArray {
        val bitmap =
            Bitmap.createBitmap(
                PLACEHOLDER_BITMAP_WIDTH,
                PLACEHOLDER_BITMAP_HEIGHT,
                Bitmap.Config.ARGB_8888,
            )
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val titlePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = PLACEHOLDER_TITLE_TEXT_SIZE
                textAlign = Paint.Align.LEFT
            }
        val bodyPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.DKGRAY
                textSize = PLACEHOLDER_BODY_TEXT_SIZE
                textAlign = Paint.Align.LEFT
            }
        canvas.drawText(
            "Bada bug report",
            PLACEHOLDER_TEXT_X,
            PLACEHOLDER_TITLE_Y,
            titlePaint,
        )
        canvas.drawText(label, PLACEHOLDER_TEXT_X, PLACEHOLDER_BODY_LINE_1_Y, bodyPaint)
        canvas.drawText(
            "No sensitive in-app content was captured.",
            PLACEHOLDER_TEXT_X,
            PLACEHOLDER_BODY_LINE_2_Y,
            bodyPaint,
        )
        return bitmap.toPngBytes()
    }

    private fun Bitmap.toPngBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, PNG_COMPRESSION_QUALITY, out)
        return out.toByteArray()
    }

    private fun sanitizeWifiSsid(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "<unknown ssid>") return null
        val stripped =
            if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) {
                raw.substring(1, raw.length - 1)
            } else {
                raw
            }
        return stripped.takeUnless { it.isBlank() || it == "<unknown ssid>" }
    }

    private fun unavailableWifiDiagnostics(includeWifiBssid: Boolean): WifiDiagnostics =
        WifiDiagnostics(
            ssid = "not_available",
            bssid = if (includeWifiBssid) "not_available" else "<redacted>",
            linkSpeedMbps = null,
            frequencyMhz = null,
        )

    private companion object {
        private const val BATTERY_PERCENT_SCALE: Int = 100
        private const val BLUETOOTH_NAME_VISIBLE_PREFIX_LENGTH: Int = 3
        private const val PLACEHOLDER_BITMAP_WIDTH: Int = 1080
        private const val PLACEHOLDER_BITMAP_HEIGHT: Int = 1920
        private const val PLACEHOLDER_TITLE_TEXT_SIZE: Float = 48f
        private const val PLACEHOLDER_BODY_TEXT_SIZE: Float = 36f
        private const val PLACEHOLDER_TEXT_X: Float = 80f
        private const val PLACEHOLDER_TITLE_Y: Float = 160f
        private const val PLACEHOLDER_BODY_LINE_1_Y: Float = 260f
        private const val PLACEHOLDER_BODY_LINE_2_Y: Float = 320f
        private const val PNG_COMPRESSION_QUALITY: Int = 100
    }
}

private data class ScreenshotResult(
    val pngBytes: ByteArray,
    val redacted: Boolean,
    val failureReason: String?,
)

private data class WifiDiagnostics(
    val ssid: String,
    val bssid: String,
    val linkSpeedMbps: Int?,
    val frequencyMhz: Int?,
)

private data class BluetoothDiagnostics(
    val state: String,
    val name: String,
    val bondedDeviceCount: Int?,
    val bleFeatures: String,
)
