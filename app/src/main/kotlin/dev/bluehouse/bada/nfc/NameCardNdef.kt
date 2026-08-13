/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord

/**
 * **Name Card NDEF codec** — builds and parses the NDEF message that carries the Name Card
 * rendezvous token over the both-background NFC tap (symmetric NameDrop-style trigger).
 *
 * Uses the REAL platform `android.nfc.NdefMessage`/`NdefRecord` APIs (not hand-rolled bytes) so the
 * emitted records — in particular the AAR — are exactly what the framework produces and Android's
 * tag dispatch expects. NOTE: this codec has no JVM/Robolectric unit tests (the platform NDEF
 * classes are Android-only in this build setup); it is exercised on-device only.
 *
 * MESSAGE = two records:
 *  - Record 1 ([NdefRecord.createExternal]): external type `bada.dev:namecard`,
 *    payload `[0x01 version][16B token]`.
 *  - Record 2 ([NdefRecord.createApplicationRecord]): the **AAR** for our package — makes the READING
 *    phone's OS launch our app from CLOSED after the tap.
 *
 * WHO CALLS:
 *  - CARD side [BadaNdefApduService] serves `build(token, pkg).toByteArray()` as its Type-4 NDEF
 *    file (it needs the raw bytes for `buildNdefFile`).
 *  - READER side [dev.bluehouse.bada.namecard.NameCardTransferActivity] passes the OS-delivered
 *    `NdefMessage` straight to [parseToken].
 *
 * Domain+type are ALL-LOWERCASE, matching the manifest `pathPrefix="/bada.dev:namecard"` so the
 * `vnd.android.nfc://ext/<domain>:<type>` mapping never misses on case. Status: compile-verified on
 * the box; on-device tap UNVERIFIED.
 */
internal object NameCardNdef {
    /** External-type record domain — MUST stay lowercase + in sync with the manifest filter. */
    const val EXT_DOMAIN: String = "bada.dev"

    /** External-type record type — MUST stay lowercase + in sync with the manifest filter. */
    const val EXT_TYPE: String = "namecard"

    /** First payload byte; bump only with a manifest/parse compatibility plan. */
    const val PAYLOAD_VERSION: Byte = 0x01

    private const val TOKEN_LEN = 16

    /** Exact type bytes (`bada.dev:namecard`) an incoming record must carry — see [parseToken]. */
    private val EXT_TYPE_BYTES = "$EXT_DOMAIN:$EXT_TYPE".toByteArray(Charsets.US_ASCII)

    /**
     * Build the NDEF message: external record `[version][16B token]` + AAR for [packageName]
     * (pass `context.packageName` / `BuildConfig.APPLICATION_ID`, never a literal — debug/release differ).
     * The card side calls `.toByteArray()` on the result.
     */
    fun build(
        token: ByteArray,
        packageName: String,
    ): NdefMessage {
        require(token.size == TOKEN_LEN) { "token must be $TOKEN_LEN bytes, got ${token.size}" }
        val payload = ByteArray(1 + TOKEN_LEN)
        payload[0] = PAYLOAD_VERSION
        System.arraycopy(token, 0, payload, 1, TOKEN_LEN)
        val ext = NdefRecord.createExternal(EXT_DOMAIN, EXT_TYPE, payload)
        val aar = NdefRecord.createApplicationRecord(packageName)
        return NdefMessage(arrayOf(ext, aar))
    }

    /**
     * Extract the rendezvous token from a received message, or null when it carries no well-formed
     * `bada.dev:namecard` record. MUST match our exact external type: the AAR is ALSO a
     * TNF_EXTERNAL_TYPE record (type `android.com:pkg`), so matching any external record would hit it.
     */
    fun parseToken(msg: NdefMessage): ByteArray? {
        for (record in msg.records) {
            if (record.tnf == NdefRecord.TNF_EXTERNAL_TYPE &&
                record.type.contentEquals(EXT_TYPE_BYTES)
            ) {
                val p = record.payload
                if (p.size == 1 + TOKEN_LEN && p[0] == PAYLOAD_VERSION) {
                    return p.copyOfRange(1, 1 + TOKEN_LEN)
                }
            }
        }
        return null
    }
}
