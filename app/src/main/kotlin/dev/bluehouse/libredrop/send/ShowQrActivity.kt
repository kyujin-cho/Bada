/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.send

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dev.bluehouse.libredrop.bugreport.BugReportFlowSupport
import dev.bluehouse.libredrop.databinding.ActivityShowQrBinding
import dev.bluehouse.libredrop.protocol.qr.QrKeyData
import dev.bluehouse.libredrop.protocol.qr.QrUrl
import kotlin.math.min

/**
 * Renders the Quick Share QR-code URL produced by [QrUrl.build] (#20)
 * as a scannable QR bitmap (#84).
 *
 * Flow on every screen entry:
 *
 *  - Generate a fresh ECDSA P-256 keypair via [QrKeyData.generate].
 *  - Build the canonical Quick Share URL with [QrUrl.build].
 *  - Encode the URL as a QR-code [android.graphics.Bitmap] via
 *    [QrBitmapRenderer.render], sized to ~75% of the shorter screen
 *    edge so it stays square on both portrait and landscape.
 *  - Surface the URL verbatim as monospace selectable text below the
 *    bitmap as a copy/paste fallback.
 *
 * Wiring still pending (tracked separately, see #28's wiring scope):
 *
 *  - Surface the keypair + advertising token to a discovery layer that
 *    the receiver can match against (the QR-handshake-data
 *    `pairing-token` flow in `:core-protocol`).
 *
 * Today's screen is not connected to the live [SendActivity] state —
 * it is launched as a separate Activity so the user can show the URL
 * to the receiver and then come back. Once the e2e wiring is in place,
 * the keypair will be plumbed back into the [SendActivity] connection
 * scope so the same key drives both the QR display and the eventual
 * `qrCodeHandshakeData` parameter on
 * [dev.bluehouse.libredrop.protocol.connection.OutboundConnection].
 */
public class ShowQrActivity : AppCompatActivity() {
    private lateinit var binding: ActivityShowQrBinding
    private lateinit var bugReportFlowSupport: BugReportFlowSupport

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShowQrBinding.inflate(layoutInflater)
        setContentView(binding.root)
        bugReportFlowSupport = BugReportFlowSupport.install(this)

        val generated = QrKeyData.generate()
        val url = QrUrl.build(generated.qrKeyData)
        binding.showQrUrl.text = url

        // Size the QR bitmap to ~75% of the shorter screen edge so it
        // stays square and proportionally large in both portrait and
        // landscape, without crowding the title/URL/button below it.
        val displayMetrics = resources.displayMetrics
        val qrSize = (min(displayMetrics.widthPixels, displayMetrics.heightPixels) * QR_SCREEN_FRACTION).toInt()

        val bitmap = QrBitmapRenderer.render(url, qrSize)
        binding.showQrBitmap.setImageBitmap(bitmap)
        binding.showQrBitmap.layoutParams =
            binding.showQrBitmap.layoutParams.apply {
                width = qrSize
                height = qrSize
            }

        binding.showQrDone.setOnClickListener { finish() }
    }

    private companion object {
        /**
         * Fraction of the shorter screen edge to use for the QR bitmap.
         * Per the issue acceptance criteria — "square, ~75% of the
         * shorter screen edge".
         */
        private const val QR_SCREEN_FRACTION: Double = 0.75
    }
}
