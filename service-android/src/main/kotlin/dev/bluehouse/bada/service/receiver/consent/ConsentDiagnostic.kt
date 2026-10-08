/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.receiver.consent

import android.content.Context
import dev.bluehouse.bada.discovery.diagnostics.DiagnosticLog
import dev.bluehouse.bada.service.receiver.InboundDiagnosticLog

/**
 * Append-only diagnostic logger for the receiver-side consent surface
 * lifecycle (issue #151 / PR #152 follow-up).
 *
 * Writes timestamped lines to
 * `getExternalFilesDir(null)/bada-inbound.log` through
 * [InboundDiagnosticLog], the same capped file the inbound-state
 * diagnostic logger uses, so
 * `adb shell cat bada-inbound.log.1 bada-inbound.log` pulls the
 * complete picture across the protocol layer and the consent surface.
 * The shared file matters on Funtouch /
 * OriginOS where logcat silently drops `Log.i` / `Log.w` lines from
 * apps that have not been hand-blessed by the vendor — `Log.e` plus
 * the on-disk fallback are the only reliable diagnostic channels.
 *
 * Lines look like:
 *
 * ```text
 * 1777870576772 consent: raise id=200181303 isForeground=true target=Modal
 * 1777870576773 consent: trampoline.onCreate id=200181303 lookup=present
 * 1777870576774 consent: trampoline.bindIntent.finish id=200181303 reason=lookup-null
 * ```
 *
 * The prefix `consent:` is intentional — `appendInboundLog` already
 * uses the file without a prefix, so a `grep '^[0-9]\+ consent:'`
 * isolates the consent-surface trace.
 *
 * The class is deliberately a `Context`-coupled object rather than a
 * static singleton because the writer needs a real `Context` to
 * resolve `getExternalFilesDir(null)`. The cost of plumbing the
 * `Context` through the few call sites is preferable to the
 * leak-prone alternative of holding an application reference in a
 * `companion object`.
 */
public object ConsentDiagnostic {
    private const val TAG: String = "BadaConsent"
    private const val PREFIX: String = "consent:"

    /**
     * Write [line] (a single short event description, no embedded
     * newlines) to logcat at error level and queue it for the
     * shared inbound diagnostic file. Best-effort instrumentation,
     * never load-bearing; the file write happens off the caller's thread.
     *
     * `public` rather than `internal` because [ConsentTrampolineActivity]
     * lives in the `:app` module (Kotlin `internal` is per-module).
     * The exposed surface is a single best-effort write — nothing
     * load-bearing leaks across the boundary.
     */
    public fun log(
        context: Context,
        line: String,
    ) {
        DiagnosticLog.e(TAG, line)
        InboundDiagnosticLog.append(context, "$PREFIX $line")
    }
}
