/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.receiver

import android.content.Context
import dev.bluehouse.bada.discovery.diagnostics.DiagnosticFileSink
import dev.bluehouse.bada.discovery.diagnostics.DiagnosticLog
import java.io.File

/**
 * Process-wide writer for `getExternalFilesDir(null)/bada-inbound.log`, the
 * receiver's on-disk diagnostic log: inbound connection states, TCP server
 * lines, and the consent-surface trace from `ConsentDiagnostic`.
 *
 * Every writer goes through one [DiagnosticFileSink], so the file is capped at
 * [DiagnosticLog.DEFAULT_FILE_MAX_BYTES] with a single `.1` backup and no caller
 * touches the disk (#304). The writers used to open, append, and close the file
 * per line with no cap; one 2.7 GB transfer wrote about 82,000 lines to it.
 * Read it back with `cat bada-inbound.log.1 bada-inbound.log`.
 */
public object InboundDiagnosticLog {
    public const val FILE_NAME: String = "bada-inbound.log"

    @Volatile
    private var sink: DiagnosticFileSink? = null

    /**
     * Append [line] (no embedded newlines), prefixed with the current
     * wall-clock millis taken on the caller's thread. Best-effort: a missing
     * external files dir drops the line.
     */
    public fun append(
        context: Context,
        line: String,
    ) {
        val target = sinkFor(context) ?: return
        target.append("${System.currentTimeMillis()} $line")
    }

    /** Barrier for the bug-report reader. No-op before the first [append]. */
    public fun flush(timeoutMillis: Long = DiagnosticLog.DEFAULT_FLUSH_TIMEOUT_MILLIS) {
        sink?.flush(timeoutMillis)
    }

    private fun sinkFor(context: Context): DiagnosticFileSink? {
        sink?.let { return it }
        return synchronized(this) {
            sink ?: runCatching {
                context.applicationContext.getExternalFilesDir(null)?.let { dir ->
                    DiagnosticFileSink(File(dir, FILE_NAME), DiagnosticLog.DEFAULT_FILE_MAX_BYTES)
                }
            }.getOrNull()?.also { sink = it }
        }
    }
}
