/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.debug

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.bluehouse.libredrop.discovery.ble.BleFastAdvertisementScanner
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class BleFastScanProbeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            Log.w(TAG, "probe start durationMs=$PROBE_DURATION_MILLIS")
            try {
                withTimeout(PROBE_DURATION_MILLIS) {
                    BleFastAdvertisementScanner(applicationContext)
                        .scan()
                        .collect { observation ->
                            Log.w(
                                TAG,
                                "probe observed endpointId=${observation.endpointId} " +
                                    "address=${observation.advertiserAddress} rssi=${observation.rssi}",
                            )
                        }
                }
            } catch (_: TimeoutCancellationException) {
                Log.w(TAG, "probe timeout")
            } finally {
                finish()
            }
        }
    }

    private companion object {
        private const val TAG: String = "LibreDropBleFastProbe"
        private const val PROBE_DURATION_MILLIS: Long = 30_000L
    }
}
