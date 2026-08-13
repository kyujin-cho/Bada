/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.namecard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.bluehouse.bada.R
import dev.bluehouse.bada.discovery.diagnostics.DiagnosticLog
import dev.bluehouse.bada.protocol.namecard.NameCard
import dev.bluehouse.bada.service.radio.RadioHelperClient
import dev.bluehouse.bada.service.radio.ShareRadioController

/**
 * **Name Card exchange foreground service (card/server side).** Started on an NFC
 * tap (from [dev.bluehouse.bada.nfc.BadaNdefApduService] or
 * [dev.bluehouse.bada.nfc.NameCardHceService]): it brings the process up reliably
 * (a tap can wake us cold) and runs [NameCardBleExchange] as the GATT **server**
 * for the token-authenticated symmetric-consent exchange — advertising the
 * derived token id, serving the consent channel, and launching the full-screen
 * [NameCardTransferActivity] so this user can choose Share / Receive Only.
 * Nothing is served to a peer that hasn't presented the tap's token, and the
 * card itself is served only after this user taps Share.
 *
 * Reader/client side does NOT use this service — that runs from the foreground
 * [NameCardTransferActivity] directly.
 *
 * FGS type `connectedDevice` (Bluetooth); the manifest declares the type and the
 * app already holds the `connectedDevice` FGS-type gate permission.
 *
 * Status: compile-only here (no BT/NFC/2 phones); device-verified by a real tap.
 */
@Suppress("ReturnCount")
internal class NameCardExchangeService : Service() {
    private var exchange: NameCardBleExchange? = null
    private val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Forces Bluetooth on for the swap + runs the 5s helper heartbeat; restored on stop. */
    private val shareRadios by lazy { ShareRadioController(this, TAG) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startInForeground()
        val token = intent?.getByteArrayExtra(EXTRA_TOKEN)
        if (token == null) {
            DiagnosticLog.w(TAG, "no token → stop")
            stopSelf()
            return START_NOT_STICKY
        }
        val localCard =
            NameCardResolver(
                storedCard = NameCardProfileStore.from(this)::load,
                shareSelection = NameCardProfileStore.from(this)::shareSelection,
            ).resolve()
        if (localCard == null) {
            // No card configured — the user hasn't opted in to sharing anything.
            DiagnosticLog.w(TAG, "no configured card to share → stop")
            stopSelf()
            return START_NOT_STICKY
        }

        // Force Bluetooth on via the helper (+ start the 5s keep-alive heartbeat so a
        // crash mid-swap restores radios ~20s after the last beat, not minutes later).
        shareRadios.requestRadiosOn(RadioHelperClient.RADIO_BT)

        // Serve the token-gated card + CONSENT channel and launch the transfer screen immediately so
        // BOTH phones show the card at tap. The service stays foreground for the WHOLE session.
        startServerWhenBtReady(localCard, token, attempt = 0)
        timeoutHandler.postDelayed({
            DiagnosticLog.w(TAG, "server: session timeout → stop")
            stopSelf()
        }, SERVE_TIMEOUT_MS)
        return START_NOT_STICKY
    }

    private fun startServerWhenBtReady(
        localCard: NameCard,
        token: ByteArray,
        attempt: Int,
    ) {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter?.isEnabled == true || attempt >= MAX_BT_WAIT_ATTEMPTS) {
            startServerNow(localCard, token)
            return
        }
        DiagnosticLog.w(TAG, "server: BT not on yet (attempt $attempt) → waiting for helper")
        timeoutHandler.postDelayed({ startServerWhenBtReady(localCard, token, attempt + 1) }, BT_GRACE_MS)
    }

    private fun startServerNow(
        localCard: NameCard,
        token: ByteArray,
    ) {
        val ble = NameCardBleExchange(this)
        exchange = ble
        val session = NameCardLinkHolder.startSession(ble, NameCardLinkHolder.Role.SERVER, localCard)
        session.onClosed = { stopSelf() }
        val started = ble.startServerV2(localCard = localCard, token = token, listener = session)
        if (!started) {
            DiagnosticLog.w(TAG, "startServerV2 failed (BT off / no perm) → stop")
            stopSelf()
            return
        }
        // Both screens open at tap: launch the symmetric consent UI now (peer card arrives later via
        // the holder, never an Intent extra). Keep the FGS + radios alive until the session closes.
        DiagnosticLog.w(TAG, "server: serving → launch transfer screen")
        startActivity(
            NameCardTransferActivity.serverV2Intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun onDestroy() {
        timeoutHandler.removeCallbacksAndMessages(null)
        exchange?.stop()
        exchange = null
        // Stop the heartbeat + restore the radios the helper turned on.
        shareRadios.restoreRadios()
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.name_card_exchange_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val notification =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.name_card_exchange_notification))
                .setOngoing(true)
                .build()
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val TAG = "NameCardExchangeSvc"
        private const val CHANNEL_ID = "name_card_exchange"
        private const val NOTIFICATION_ID = 4310
        private const val EXTRA_TOKEN = "token"

        /** Session backstop: > the exchange's 60s backstop, so the UX timer resolves first. */
        private const val SERVE_TIMEOUT_MS = 65_000L

        /** Grace between retries while the helper turns Bluetooth on, and the cap. */
        private const val BT_GRACE_MS = 1_500L
        private const val MAX_BT_WAIT_ATTEMPTS = 2

        /** Start the server-side exchange for [token] (from the NFC tap). */
        fun start(
            context: Context,
            token: ByteArray,
        ) {
            val intent =
                Intent(context, NameCardExchangeService::class.java).putExtra(EXTRA_TOKEN, token)
            context.startForegroundService(intent)
        }
    }
}
