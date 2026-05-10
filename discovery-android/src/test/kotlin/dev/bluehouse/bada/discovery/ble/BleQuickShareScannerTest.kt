/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery.ble

import android.bluetooth.le.ScanSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * JVM unit tests for [BleQuickShareScanner].
 *
 * The Android `ScanResult` / `BluetoothLeScanner` types are final and not
 * usable on a plain JVM, so the scanner itself is tested through:
 *   * `forTestEmitPulse` — drives the activity state machine without
 *     having to construct a real `ScanResult`.
 *   * a [FakeBleScannerGate] — counts platform start/stop and tracks
 *     subscriber callbacks.
 *
 * The test runs under [runTest], whose virtual-time scheduler makes the
 * inactivity timeout (a `delay()` call inside a launched job) deterministic
 * via `advanceTimeBy` / `advanceUntilIdle`. The scanner attaches its
 * inactivity-timer job to `backgroundScope`, which `runTest` auto-cancels
 * when the test body returns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleQuickShareScannerTest {
    @Test
    fun `prefix and mask match PROTOCOL spec`() {
        // Pin the wire bytes — issue #33 cites the spec verbatim and a
        // regression here would silently break interop with stock
        // Quick Share senders. Hex-string comparison is more readable
        // than byte-by-byte assertions and keeps the test failure
        // message obvious.
        val hexPrefix = BleQuickShareScanner.SERVICE_DATA_PREFIX.joinToString(separator = "") { "%02x".format(it) }
        assertThat(hexPrefix).isEqualTo("fc128e014200000000000000" + "0000")

        // Mask is 0xff for every prefix byte — i.e. the platform
        // compares the entire 14-byte prefix exactly.
        val hexMask = BleQuickShareScanner.SERVICE_DATA_MASK.joinToString(separator = "") { "%02x".format(it) }
        assertThat(hexMask).isEqualTo("ffffffffffffffffffffffffffff")
        assertThat(BleQuickShareScanner.SERVICE_DATA_PREFIX.size).isEqualTo(14)
        assertThat(BleQuickShareScanner.SERVICE_DATA_MASK.size).isEqualTo(14)
    }

    @Test
    fun `service UUID expands to SIG-base form`() {
        // Quick Share's 16-bit service UUID `0xFE2C` must expand into the
        // standard SIG base UUID. Pin the string form here (as opposed to
        // the ParcelUuid object, which is mocked away in AGP unit tests)
        // so a future refactor cannot silently swap it for a different
        // namespace.
        assertThat(BleQuickShareScanner.QUICK_SHARE_SERVICE_UUID_STRING)
            .isEqualTo("0000fe2c-0000-1000-8000-00805f9b34fb")
    }

    @Test
    fun `start without scan permission is a no-op`() =
        runTest {
            val gate = FakeBleScannerGate()
            val scanner =
                BleQuickShareScanner.forTesting(
                    coroutineScope = backgroundScope,
                    gate = gate,
                    permissionChecker = { false },
                )

            val started = scanner.start()
            assertThat(started).isFalse()
            assertThat(gate.startCount).isEqualTo(0)
            assertThat(scanner.isScanning).isFalse()
        }

    @Test
    fun `start engages the platform scan exactly once`() =
        runTest {
            val gate = FakeBleScannerGate()
            val scanner =
                BleQuickShareScanner.forTesting(
                    coroutineScope = backgroundScope,
                    gate = gate,
                    permissionChecker = { true },
                )

            assertThat(scanner.start()).isTrue()
            assertThat(scanner.start()).isTrue()
            assertThat(gate.startCount).isEqualTo(1)
            assertThat(scanner.isScanning).isTrue()
        }

    @Test
    fun `start delegates to the gate exactly once when permission is granted`() =
        runTest {
            val gate = FakeBleScannerGate()
            val scanner =
                BleQuickShareScanner.forTesting(
                    coroutineScope = backgroundScope,
                    gate = gate,
                    permissionChecker = { true },
                )

            scanner.start()

            // The wire-level filter contents (UUID, service-data prefix,
            // mask, scan mode = BALANCED, reportDelay = 0) are pinned by
            // the spec-aligned tests above. Here we only assert that
            // start did, in fact, hit the gate.
            assertThat(gate.startCount).isEqualTo(1)
        }

    @Test
    fun `start uses SCAN_MODE_BALANCED by default`() =
        runTest {
            // Issue #35 acceptance criterion: the scan registers in
            // BALANCED mode by default. LOW_LATENCY is opt-in via
            // setScanMode while the user has the app foregrounded.
            val gate = FakeBleScannerGate()
            val scanner =
                BleQuickShareScanner.forTesting(
                    coroutineScope = backgroundScope,
                    gate = gate,
                    permissionChecker = { true },
                )

            scanner.start()
            assertThat(gate.lastScanMode).isEqualTo(ScanSettings.SCAN_MODE_BALANCED)
            assertThat(scanner.activeScanMode).isEqualTo(ScanSettings.SCAN_MODE_BALANCED)
        }

    @Test
    fun `setScanMode while idle pins the mode for the next start`() =
        runTest {
            // No platform call should happen until start() runs — we
            // don't want to thrash the kernel scan registration before
            // the receiver service has even decided to bring it up.
            val gate = FakeBleScannerGate()
            val scanner =
                BleQuickShareScanner.forTesting(
                    coroutineScope = backgroundScope,
                    gate = gate,
                    permissionChecker = { true },
                )

            assertThat(scanner.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)).isTrue()
            assertThat(gate.startCount).isEqualTo(0)
            assertThat(scanner.activeScanMode).isEqualTo(ScanSettings.SCAN_MODE_LOW_LATENCY)

            scanner.start()
            assertThat(gate.lastScanMode).isEqualTo(ScanSettings.SCAN_MODE_LOW_LATENCY)
        }

    @Test
    fun `setScanMode while active re-registers with the new mode`() =
        runTest {
            // Simulates the foreground -> background transition: the
            // app comes to the foreground (LOW_LATENCY) and then leaves
            // it (BALANCED). The scanner should tear down the existing
            // registration and bring up a new one each time.
            val gate = FakeBleScannerGate()
            val scanner =
                BleQuickShareScanner.forTesting(
                    coroutineScope = backgroundScope,
                    gate = gate,
                    permissionChecker = { true },
                )

            scanner.start()
            assertThat(gate.startCount).isEqualTo(1)
            assertThat(gate.lastScanMode).isEqualTo(ScanSettings.SCAN_MODE_BALANCED)

            assertThat(scanner.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)).isTrue()
            assertThat(gate.startCount).isEqualTo(2)
            assertThat(gate.stopCount).isEqualTo(1)
            assertThat(gate.lastScanMode).isEqualTo(ScanSettings.SCAN_MODE_LOW_LATENCY)
            assertThat(scanner.isScanning).isTrue()

            assertThat(scanner.setScanMode(ScanSettings.SCAN_MODE_BALANCED)).isTrue()
            assertThat(gate.startCount).isEqualTo(3)
            assertThat(gate.stopCount).isEqualTo(2)
            assertThat(gate.lastScanMode).isEqualTo(ScanSettings.SCAN_MODE_BALANCED)
        }

    @Test
    fun `setScanMode is a no-op when already on the requested mode`() =
        runTest {
            val gate = FakeBleScannerGate()
            val scanner =
                BleQuickShareScanner.forTesting(
                    coroutineScope = backgroundScope,
                    gate = gate,
                    permissionChecker = { true },
                )

            scanner.start()
            assertThat(gate.startCount).isEqualTo(1)

            // Same mode, same registration — no restart should happen.
            assertThat(scanner.setScanMode(ScanSettings.SCAN_MODE_BALANCED)).isTrue()
            assertThat(gate.startCount).isEqualTo(1)
            assertThat(gate.stopCount).isEqualTo(0)
        }

    @Test
    fun `setScanMode leaves the scanner stopped if re-register fails`() =
        runTest {
            // Once the user has switched on Bluetooth airplane-mode etc,
            // the platform may refuse a re-register. The scanner must
            // not pretend to still be scanning in that case.
            val gate = FlakyBleScannerGate()
            val scanner =
                BleQuickShareScanner.forTesting(
                    coroutineScope = backgroundScope,
                    gate = gate,
                    permissionChecker = { true },
                )

            scanner.start()
            assertThat(scanner.isScanning).isTrue()

            gate.refuseStart = true
            assertThat(scanner.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)).isFalse()
            assertThat(scanner.isScanning).isFalse()
        }

    @Test
    fun `stop closes the registration and resets the activity state`() =
        runTest {
            val gate = FakeBleScannerGate()
            // backgroundScope owns the inactivity-timeout job; auto-cancelled by runTest.
            val scanner =
                BleQuickShareScanner.forTesting(
                    coroutineScope = backgroundScope,
                    gate = gate,
                    permissionChecker = { true },
                    nowMillis = { 1_000L },
                )

            scanner.start()
            scanner.forTestEmitPulse()
            advanceUntilIdle()
            assertThat(scanner.activity.value).isInstanceOf(ScanActivity.Active::class.java)

            scanner.stop()
            assertThat(gate.stopCount).isEqualTo(1)
            assertThat(scanner.isScanning).isFalse()
            assertThat(scanner.activity.value).isEqualTo(ScanActivity.Idle)
        }

    @Test
    fun `pulse flips activity to Active and the timestamp matches the clock`() =
        runTest {
            val gate = FakeBleScannerGate()
            var clock = 5_000L
            val scanner =
                BleQuickShareScanner.forTesting(
                    coroutineScope = backgroundScope,
                    gate = gate,
                    permissionChecker = { true },
                    nowMillis = { clock },
                )

            scanner.start()
            scanner.forTestEmitPulse()
            advanceUntilIdle()

            val current = scanner.activity.value
            assertThat(current).isEqualTo(ScanActivity.Active(lastSeenAtMillis = 5_000L))
        }

    @Test
    fun `activity flips back to Idle after the inactivity timeout`() =
        runTest {
            val gate = FakeBleScannerGate()
            val scanner =
                BleQuickShareScanner.forTesting(
                    coroutineScope = backgroundScope,
                    gate = gate,
                    permissionChecker = { true },
                    inactivityTimeoutMillis = 30_000L,
                    nowMillis = { 0L },
                )

            scanner.start()
            scanner.forTestEmitPulse()
            advanceUntilIdle()
            assertThat(scanner.activity.value).isInstanceOf(ScanActivity.Active::class.java)

            // Just before the deadline — still active.
            advanceTimeBy(29_999L)
            assertThat(scanner.activity.value).isInstanceOf(ScanActivity.Active::class.java)

            // At and past the deadline — flips to Idle.
            advanceTimeBy(2L)
            advanceUntilIdle()
            assertThat(scanner.activity.value).isEqualTo(ScanActivity.Idle)
        }

    @Test
    fun `back-to-back pulses extend the inactivity window`() =
        runTest {
            val gate = FakeBleScannerGate()
            var clock = 0L
            val scanner =
                BleQuickShareScanner.forTesting(
                    coroutineScope = backgroundScope,
                    gate = gate,
                    permissionChecker = { true },
                    inactivityTimeoutMillis = 30_000L,
                    nowMillis = { clock },
                )

            scanner.start()
            scanner.forTestEmitPulse()
            advanceUntilIdle()

            // Halfway through the window, another pulse arrives.
            advanceTimeBy(15_000L)
            clock = 15_000L
            scanner.forTestEmitPulse()
            advanceUntilIdle()

            // Original deadline has now passed but the second pulse
            // refreshed it — still active.
            advanceTimeBy(20_000L)
            advanceUntilIdle()
            assertThat(scanner.activity.value).isInstanceOf(ScanActivity.Active::class.java)

            // Eventually the second pulse's window expires.
            advanceTimeBy(11_000L)
            advanceUntilIdle()
            assertThat(scanner.activity.value).isEqualTo(ScanActivity.Idle)
        }

    @Test
    fun `start when gate refuses to start returns false`() =
        runTest {
            val gate = FakeBleScannerGate(refuseStart = true)
            val scanner =
                BleQuickShareScanner.forTesting(
                    coroutineScope = backgroundScope,
                    gate = gate,
                    permissionChecker = { true },
                )

            val started = scanner.start()
            assertThat(started).isFalse()
            assertThat(scanner.isScanning).isFalse()
        }

    @Test
    fun `stop without an active scan is a no-op`() =
        runTest {
            val gate = FakeBleScannerGate()
            val scanner =
                BleQuickShareScanner.forTesting(
                    coroutineScope = backgroundScope,
                    gate = gate,
                    permissionChecker = { true },
                )

            scanner.stop()
            assertThat(gate.stopCount).isEqualTo(0)
        }

    @Test
    fun `activity state flow exposes initial Idle value`() =
        runTest {
            val gate = FakeBleScannerGate()
            val scanner =
                BleQuickShareScanner.forTesting(
                    coroutineScope = backgroundScope,
                    gate = gate,
                    permissionChecker = { true },
                )

            // StateFlow contract: collectors immediately see the current
            // value. Issue #34 relies on this — the mDNS gate must not
            // miss the initial Idle that says "no pulse yet, do nothing".
            assertThat(scanner.activity.first()).isEqualTo(ScanActivity.Idle)
        }

    /**
     * Pure-JVM stand-in for the platform scanner.
     *
     * Records every `startScan` invocation and matching close. The flag
     * [refuseStart] simulates the production path where the platform
     * [BluetoothManager] returns `null` (e.g. adapter off) — the gate
     * returns `null` and the scanner reports the start as failed.
     *
     * The fake intentionally does NOT touch the AGP-stubbed `ScanFilter`
     * / `ScanSettings` types: the production gate
     * ([AndroidBleScannerGate]) builds those internally so JVM tests
     * never have to.
     */
    private class FakeBleScannerGate(
        private val refuseStart: Boolean = false,
    ) : BleScannerGate {
        var startCount: Int = 0
            private set
        var stopCount: Int = 0
            private set
        var sinkCount: Int = 0
            private set

        /**
         * Most-recently-requested scan mode. `null` until [startScan]
         * has been called once. Tests assert this to verify that #35's
         * BALANCED-by-default / LOW_LATENCY-on-foreground policy
         * actually reaches the platform.
         */
        var lastScanMode: Int? = null
            private set

        override fun startScan(scanMode: Int): BleScannerGate.Registration? {
            if (refuseStart) return null
            startCount++
            lastScanMode = scanMode
            return Registration()
        }

        override fun addSink(sink: BleScannerGate.PulseSink) {
            sinkCount++
        }

        override fun removeSink(sink: BleScannerGate.PulseSink) {
            sinkCount--
        }

        private inner class Registration : BleScannerGate.Registration {
            override fun close() {
                stopCount++
            }
        }
    }

    /**
     * [FakeBleScannerGate] variant whose `refuseStart` flag is mutable
     * so a single test can flip the platform from "scan accepted" to
     * "scan rejected" mid-flight. Used to exercise the
     * setScanMode-fails-mid-flight branch of the scanner.
     */
    private class FlakyBleScannerGate : BleScannerGate {
        var refuseStart: Boolean = false
        var lastScanMode: Int? = null
            private set

        override fun startScan(scanMode: Int): BleScannerGate.Registration? {
            if (refuseStart) return null
            lastScanMode = scanMode
            return Registration()
        }

        override fun addSink(sink: BleScannerGate.PulseSink) = Unit

        override fun removeSink(sink: BleScannerGate.PulseSink) = Unit

        private class Registration : BleScannerGate.Registration {
            override fun close() = Unit
        }
    }
}
