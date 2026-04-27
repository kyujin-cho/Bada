/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.send

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.github.kyujincho.wvmg.databinding.ActivityShowQrBinding
import io.github.kyujincho.wvmg.protocol.qr.QrKeyData
import io.github.kyujincho.wvmg.protocol.qr.QrUrl

/**
 * Renders the Quick Share QR-code URL produced by [QrUrl.build] (#20).
 *
 * **Phase 1 stub.** The protocol-side QR-code path (key generation, URL
 * encoding, advertising-token derivation) is fully implemented in
 * `:core-protocol`, but zxing — the standard Android QR-bitmap library
 * — is not yet on the dependency graph. Adding it pulls in ~200 KB of
 * additional code and is out of scope for #24, whose acceptance
 * criteria explicitly say the QR-bitmap renderer can be stubbed as
 * long as the URL is surfaced.
 *
 * What we do today:
 *
 *  - Generate a fresh ECDSA P-256 keypair on every screen entry via
 *    [QrKeyData.generate].
 *  - Build the canonical Quick Share URL with [QrUrl.build].
 *  - Display the URL as monospace selectable text.
 *
 * What still needs to land before this is production-ready (tracked in
 * a follow-up — see issue #28's wiring scope):
 *
 *  - Render the URL as an actual QR bitmap (zxing or similar).
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
 * [io.github.kyujincho.wvmg.protocol.connection.OutboundConnection].
 */
public class ShowQrActivity : AppCompatActivity() {
    private lateinit var binding: ActivityShowQrBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShowQrBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val generated = QrKeyData.generate()
        val url = QrUrl.build(generated.qrKeyData)
        binding.showQrUrl.text = url

        // Followup #28 will render `url` via zxing (or comparable) once
        // the dependency graph is updated. See the class-level docs for
        // the rationale and the wiring scope tracked in #28.

        binding.showQrDone.setOnClickListener { finish() }
    }
}
