/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.nfc

import android.content.ComponentName
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Bundle
import androidx.annotation.RequiresApi

private const val API_37 = 37

/** API-37-only NFC additions, isolated so API 24–36 never resolve these members. */
@RequiresApi(API_37)
internal object Api37NfcSessionAdapter {
    fun readerExtras(
        adapter: NfcAdapter,
        physicallyValidated: Boolean,
    ): Bundle? =
        if (physicallyValidated && adapter.isReaderModeAnnotationSupported) {
            Bundle().apply {
                putByteArray(NfcAdapter.EXTRA_READER_TECH_A_POLLING_LOOP_ANNOTATION, POLLING_ANNOTATION)
            }
        } else {
            null
        }

    fun beginReceive(
        adapter: NfcAdapter,
        cardEmulation: CardEmulation,
        service: ComponentName,
    ) {
        cardEmulation.setRequireDeviceScreenOnForService(service, true)
        cardEmulation.setRequireDeviceUnlockForService(service, true)
        cardEmulation.setShouldDefaultToObserveModeForService(service, true)
        if (adapter.isObserveModeSupported) adapter.setObserveModeEnabled(true)
    }

    fun endReceive(
        adapter: NfcAdapter,
        cardEmulation: CardEmulation,
        service: ComponentName,
    ) {
        if (adapter.isObserveModeSupported && adapter.isObserveModeEnabled) adapter.setObserveModeEnabled(false)
        cardEmulation.setShouldDefaultToObserveModeForService(service, false)
        cardEmulation.setRequireDeviceScreenOnForService(service, true)
        cardEmulation.setRequireDeviceUnlockForService(service, false)
    }

    private val POLLING_ANNOTATION = byteArrayOf(0x6A, 0x01, 0xCF.toByte(), 0x00, 0x00)
}
