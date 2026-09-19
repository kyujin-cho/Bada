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

/**
 * Runtime-only bridge for API-37 NFC additions.
 *
 * Bada compiles and targets API 36, so every newer member is resolved by name
 * only after the caller has checked the runtime SDK. API 24–36 therefore never
 * verify or link an unavailable method or field.
 */
internal object Api37NfcSessionAdapter {
    fun readerExtras(
        adapter: NfcAdapter,
        physicallyValidated: Boolean,
    ): Bundle? =
        if (physicallyValidated && adapter.callBoolean("isReaderModeAnnotationSupported")) {
            val annotationKey =
                NfcAdapter::class.java
                    .getField("EXTRA_READER_TECH_A_POLLING_LOOP_ANNOTATION")
                    .get(null) as String
            Bundle().apply {
                putByteArray(annotationKey, POLLING_ANNOTATION)
            }
        } else {
            null
        }

    fun beginReceive(
        adapter: NfcAdapter,
        cardEmulation: CardEmulation,
        service: ComponentName,
    ) {
        cardEmulation.callServiceFlag("setRequireDeviceScreenOnForService", service, true)
        cardEmulation.callServiceFlag("setRequireDeviceUnlockForService", service, true)
        cardEmulation.callServiceFlag("setShouldDefaultToObserveModeForService", service, true)
        if (adapter.callBoolean("isObserveModeSupported")) adapter.callBooleanSetter("setObserveModeEnabled", true)
    }

    fun endReceive(
        adapter: NfcAdapter,
        cardEmulation: CardEmulation,
        service: ComponentName,
    ) {
        if (adapter.callBoolean("isObserveModeSupported") && adapter.callBoolean("isObserveModeEnabled")) {
            adapter.callBooleanSetter("setObserveModeEnabled", false)
        }
        cardEmulation.callServiceFlag("setShouldDefaultToObserveModeForService", service, false)
        cardEmulation.callServiceFlag("setRequireDeviceScreenOnForService", service, true)
        cardEmulation.callServiceFlag("setRequireDeviceUnlockForService", service, false)
    }

    private fun NfcAdapter.callBoolean(name: String): Boolean =
        NfcAdapter::class.java.getMethod(name).invoke(this) as Boolean

    private fun NfcAdapter.callBooleanSetter(
        name: String,
        value: Boolean,
    ): Boolean = NfcAdapter::class.java.getMethod(name, java.lang.Boolean.TYPE).invoke(this, value) as Boolean

    private fun CardEmulation.callServiceFlag(
        name: String,
        service: ComponentName,
        value: Boolean,
    ) {
        CardEmulation::class.java
            .getMethod(name, ComponentName::class.java, java.lang.Boolean.TYPE)
            .invoke(this, service, value)
    }

    private val POLLING_ANNOTATION = byteArrayOf(0x6A, 0x01, 0xCF.toByte(), 0x00, 0x00)
}
