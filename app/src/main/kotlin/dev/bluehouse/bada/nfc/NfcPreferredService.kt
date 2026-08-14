/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.nfc

import android.app.Activity
import android.content.ComponentName
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import dev.bluehouse.bada.discovery.diagnostics.DiagnosticLog
import dev.bluehouse.bada.gestureexchange.GestureExchangeHceService
import dev.bluehouse.bada.gestureexchange.GestureTapToSharePreferences

/**
 * Claims SuperDrop's enabled file-share HCE while a receive surface is in the
 * foreground. Google Gesture Exchange (`A00000047609`) is preferred when its
 * independent setting is enabled; otherwise the legacy Quick Share AID
 * (`F00000FE2C`) remains available. Name Card is never selected here.
 *
 * ### Why this exists
 *
 * On a phone with stock Quick Share, both GMS and our app register the same
 * `F00000FE2C` HCE AID. On Android 14 a tap shows a chooser; on Android 15 the
 * OS routes the tap straight to Google (the default wallet/NFC handler) and our
 * HCE is never invoked (confirmed on-device: a tap opened native Quick Share's
 * receive screen). Per the HCE docs, the ONLY way to beat that for a shared AID
 * is `CardEmulation.setPreferredService(activity, ourService)` called while one
 * of our Activities is in the foreground — it overrides AID conflict resolution
 * AND the wallet default. So while our receive sheet is up, WE win the tap;
 * once it's closed we release the claim and taps fall back to native Quick Share.
 *
 * ### How it's used
 * [ConsentTrampolineActivity] calls [prefer] from `onResume` and [release] from
 * `onPause`. Best-effort: no NFC adapter / pre-conditions → logged no-op.
 *
 * ### Status
 * Compile-built; the on-device "tap reaches us while the sheet is open" behaviour
 * is the make-or-break to verify on an Android 15 phone with stock Quick Share.
 */
internal object NfcPreferredService {
    /** Prefer our tap HCE while [activity] is foreground. Returns true if claimed. */
    fun prefer(activity: Activity): Boolean = apply(activity, prefer = true)

    /** Release the preference (call when leaving the foreground). */
    fun release(activity: Activity): Boolean = apply(activity, prefer = false)

    private fun apply(
        activity: Activity,
        prefer: Boolean,
    ): Boolean {
        val adapter =
            NfcAdapter.getDefaultAdapter(activity) ?: run {
                DiagnosticLog.w(TAG, "no NFC adapter -> cannot ${if (prefer) "claim" else "release"} tap AID")
                return false
            }
        return runCatching {
            val cardEmulation = CardEmulation.getInstance(adapter)
            val component =
                if (GestureTapToSharePreferences.from(activity).isEnabled()) {
                    GestureExchangeHceService::class.java
                } else {
                    BadaTapHceService::class.java
                }
            val name = ComponentName(activity, component)
            val ok =
                if (prefer) {
                    cardEmulation.setPreferredService(activity, name)
                } else {
                    cardEmulation.unsetPreferredService(activity)
                }
            DiagnosticLog.w(TAG, "${if (prefer) "setPreferredService" else "unsetPreferredService"} -> $ok")
            ok
        }.getOrElse {
            DiagnosticLog.w(TAG, "preferred-service ${if (prefer) "set" else "unset"} failed: ${it.message}")
            false
        }
    }

    private const val TAG = "BadaNfcPreferred"
}
