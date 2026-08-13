/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.nfc

import android.app.KeyguardManager
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import dev.bluehouse.bada.namecard.NameCardExchangeService
import dev.bluehouse.bada.namecard.NameCardPreferences

/**
 * Host Card Emulation service that exposes the current Bada pairing link
 * (the QR URL) as an NFC Forum **Type-4 Tag** carrying a single NDEF **URI**
 * record. When a phone is tapped to the back of this device, it
 * background-reads the NDEF URI and offers to open it — so a phone that does
 * not run the app can open the pairing link by tapping, without scanning the
 * QR code.
 *
 * The served URI is read from [NfcLinkHolder.currentUrl] at the moment the
 * reader SELECTs the NDEF application, so each tap serves whatever pairing
 * link the Send/QR screen is currently showing. When the holder is `null`
 * (no QR on screen) an empty NDEF message (NLEN = 0) is served, so a stray
 * tap does nothing rather than opening a stale link.
 *
 * Implemented with only public `android.nfc.cardemulation` APIs + the
 * auto-granted `BIND_NFC_SERVICE` permission — no OEM privilege.
 *
 * ## Type-4 Tag protocol (NFC Forum T4T Operation + ISO 7816-4)
 * The reader runs, and this service answers, the standard T4T sequence:
 *
 *  1. SELECT by name, NDEF Tag App AID `D2760000850101` -> `90 00`
 *     (the URL is snapshotted here and the NDEF + CC files built for this
 *     read session).
 *  2. SELECT (by file id) the Capability Container `E103` -> `90 00`.
 *  3. READ_BINARY the CC -> 15-byte CC describing the NDEF file
 *     (id `E104`, max read, max NDEF len) + `90 00`.
 *  4. SELECT the NDEF file `E104` -> `90 00`.
 *  5. READ_BINARY offset 0, len 2 -> the 2-byte NLEN.
 *  6. READ_BINARY offset 2.. -> the NDEF message bytes.
 *
 * The APDU state machine is inherently byte-index heavy and branches per
 * command/file, so MagicNumber / ReturnCount / CyclomaticComplexMethod are
 * suppressed; the pure byte encoders live in [NdefTagCodec].
 */
@Suppress("MagicNumber", "ReturnCount", "CyclomaticComplexMethod")
public class BadaNdefApduService : HostApduService() {
    /** Which file the reader currently has selected. */
    private enum class Selected { NONE, CC, NDEF }

    private var selected: Selected = Selected.NONE

    /**
     * The NDEF file (NLEN prefix + message) and CC for the *current* read
     * session. Rebuilt on each SELECT-AID from [NfcLinkHolder.currentUrl]
     * so a tap always serves the link on screen at tap time. Initialised
     * to the empty-NDEF form for the case where a reader issues a
     * READ_BINARY before any SELECT-AID (defensive — a compliant reader
     * always selects first).
     */
    private var ndefFile: ByteArray = NdefTagCodec.buildNdefFile(EMPTY_NDEF_MESSAGE)
    private var ccFile: ByteArray = NdefTagCodec.buildCapabilityContainer(ndefFile.size)

    /**
     * Name Card v2: the token minted for the at-rest Name Card tap of THIS read session, or null
     * when this session is not a Name Card tap (QR link armed / feature off / locked). Set in
     * [refreshNdefForCurrentLink], consumed once by [maybeStartNameCardServer] on the first NDEF read.
     */
    private var pendingNameCardToken: ByteArray? = null

    /** Name Card v2: guards the one-shot BLE-server start so it fires on the first NDEF read only. */
    private var nameCardServerStarted = false

    override fun processCommandApdu(
        apdu: ByteArray?,
        extras: Bundle?,
    ): ByteArray {
        if (apdu == null || apdu.size < 4) {
            return SW_WRONG_LENGTH
        }

        // SELECT (INS A4).
        if (apdu[0] == 0x00.toByte() && apdu[1] == 0xA4.toByte()) {
            // SELECT by name (P1=04): match the NDEF Tag Application AID.
            if (apdu[2] == 0x04.toByte()) {
                if (NdefTagCodec.apduSelectsAid(apdu, NDEF_TAG_APP_AID)) {
                    refreshNdefForCurrentLink()
                    selected = Selected.NONE // app selected; no file selected yet
                    return SW_OK
                }
                return SW_FILE_NOT_FOUND
            }
            // SELECT by file id (P1=00, P2=0C, Lc=02, fid).
            if (apdu[2] == 0x00.toByte() && apdu.size >= 7) {
                val hi = apdu[5]
                val lo = apdu[6]
                if (hi == CC_FILE_ID[0] && lo == CC_FILE_ID[1]) {
                    selected = Selected.CC
                    return SW_OK
                }
                if (hi == NdefTagCodec.NDEF_FILE_ID[0] && lo == NdefTagCodec.NDEF_FILE_ID[1]) {
                    selected = Selected.NDEF
                    return SW_OK
                }
                return SW_FILE_NOT_FOUND
            }
            return SW_FILE_NOT_FOUND
        }

        // READ_BINARY (INS B0): P1P2 = offset, Le (last byte) = length.
        if (apdu[1] == INS_READ_BINARY) {
            val offset = ((apdu[2].toInt() and 0xFF) shl 8) or (apdu[3].toInt() and 0xFF)
            var le = if (apdu.size >= 5) apdu[4].toInt() and 0xFF else 0
            if (le == 0) le = 256 // Le=00 means 256 in short form

            val file =
                when (selected) {
                    Selected.CC -> ccFile
                    Selected.NDEF -> ndefFile
                    Selected.NONE -> return SW_FILE_NOT_FOUND
                }

            // Name Card v2: the reader is actually pulling our NDEF file → this is a real tap, not a
            // stray SELECT. Bring up the BLE GATT server (once) so the tapping phone can read our card
            // over Bluetooth using the token embedded in the NDEF. No-op unless this session minted a
            // Name Card token (pendingNameCardToken).
            if (selected == Selected.NDEF) {
                maybeStartNameCardServer()
            }

            if (offset > file.size) {
                return SW_WRONG_LENGTH
            }
            val len = minOf(le, file.size - offset)
            val resp = ByteArray(len + 2)
            System.arraycopy(file, offset, resp, 0, len)
            resp[len] = SW_OK[0]
            resp[len + 1] = SW_OK[1]
            return resp
        }

        return SW_INS_NOT_SUPPORTED
    }

    override fun onDeactivated(reason: Int) {
        selected = Selected.NONE
        // Name Card v2: end the read session so the next tap mints a fresh token + can restart the server.
        pendingNameCardToken = null
        nameCardServerStarted = false
    }

    /**
     * Name Card v2: bring up the BLE GATT server ONCE per read session, using the token embedded in
     * the NDEF we're serving. No-op unless this session is a Name Card tap ([pendingNameCardToken] set).
     * Best-effort — a failed FGS start must never break the NFC response.
     */
    private fun maybeStartNameCardServer() {
        val token = pendingNameCardToken ?: return
        if (nameCardServerStarted) return
        nameCardServerStarted = true
        runCatching {
            NameCardExchangeService.start(this, token)
        }.onFailure { Log.w(TAG, "Name Card NDEF read: start exchange service failed: ${it.message}") }
        Log.d(TAG, "Name Card NDEF read → server FGS (token ${token.size}B)")
    }

    /**
     * Name Card: is this at-rest tap a Name Card tap? True only when the user opted in (the master
     * switch, default OFF) AND the device is UNLOCKED (privacy — a locked/lost phone shares nothing).
     */
    private fun nameCardActive(): Boolean {
        val prefs = NameCardPreferences.from(this)
        if (!prefs.isEnabled()) return false
        val keyguard = getSystemService(KeyguardManager::class.java)
        return keyguard?.isDeviceLocked != true
    }

    /**
     * Snapshot [NfcLinkHolder.currentUrl] and (re)build the NDEF + CC
     * files for this read session. A non-null URL becomes a URI record; a
     * null/blank URL becomes the empty NDEF message (NLEN = 0) so the tap
     * is a no-op rather than opening a stale link.
     */
    private fun refreshNdefForCurrentLink() {
        // Reset the per-session Name Card state; only the Name Card branch below re-arms it.
        pendingNameCardToken = null
        nameCardServerStarted = false

        val url = NfcLinkHolder.currentUrl
        val message =
            when {
                // Feature 1 (iPhone/QR-link tap): a pairing link is armed (QR panel open) → serve it.
                // This ALWAYS wins over Name Card, matching NameDrop's mid-share-vs-at-rest priority.
                !url.isNullOrBlank() -> {
                    Log.d(TAG, "SELECT NDEF app AID -> OK; will serve $url")
                    NdefTagCodec.buildUriNdefMessage(url)
                }
                // Feature 3 (Name Card v2): at rest + enabled + unlocked → serve the Name Card NDEF
                // (external record with a fresh rendezvous token + our AAR so the reading phone launches
                // us from closed). This is the always-on default whenever features 1/2 aren't active.
                nameCardActive() -> {
                    val bootstrap = NameCardBootstrapHolder.newSession()
                    pendingNameCardToken = bootstrap.token
                    Log.d(TAG, "SELECT NDEF app AID -> OK; serving Name Card NDEF (v2 tap)")
                    NameCardNdef.build(bootstrap.token, packageName).toByteArray()
                }
                // Otherwise (feature off / locked / no link): empty NDEF = a deliberate dead tap.
                else -> {
                    Log.d(TAG, "SELECT NDEF app AID -> OK; no link + no Name Card, serving empty NDEF")
                    EMPTY_NDEF_MESSAGE
                }
            }
        ndefFile = NdefTagCodec.buildNdefFile(message)
        ccFile = NdefTagCodec.buildCapabilityContainer(ndefFile.size)
    }

    public companion object {
        private const val TAG = "BadaNfc"

        // ---- Status words (ISO 7816-4) ----
        private val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        private val SW_FILE_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val SW_WRONG_LENGTH = byteArrayOf(0x67.toByte(), 0x00)
        private val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D.toByte(), 0x00)

        // ---- File identifiers ----
        private val CC_FILE_ID = byteArrayOf(0xE1.toByte(), 0x03)

        // NDEF Tag Application AID (NFC Forum). The same AID an iPhone
        // SELECTs to read a Type-4 NDEF tag.
        private val NDEF_TAG_APP_AID =
            byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)

        // READ_BINARY INS.
        private const val INS_READ_BINARY: Byte = 0xB0.toByte()

        /** A valid empty NDEF message (single empty record, TNF=0x00). */
        private val EMPTY_NDEF_MESSAGE = byteArrayOf(0xD0.toByte(), 0x00, 0x00)
    }
}
