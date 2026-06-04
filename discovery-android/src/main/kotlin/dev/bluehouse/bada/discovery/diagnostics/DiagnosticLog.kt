/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery.diagnostics

import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.ArrayDeque

/**
 * Shared Android logging facade that also mirrors recent lines into an in-memory
 * ring buffer for the bug-report flow.
 *
 * Android 16+ restricts third-party access to process-external logcat, so the
 * bug-report collector cannot rely on reading the system buffer at report time.
 * Instead, the app writes its own high-signal diagnostics into
 * [recentLogBuffer] as they are emitted.
 *
 * The ring buffer is bounded by both count (2048 lines) and age (15 minutes),
 * which is fine for an immediate shake-to-report but loses BLE bootstrap
 * context once a user retries a few times before reporting. When an on-disk
 * sink is configured via [configureFileSink], every emitted line is also
 * appended to a size-rotating file the bug-report collector can read back,
 * so the diagnostics survive past the ring-buffer window (#201).
 */
public object DiagnosticLog {
    private val recentLogBuffer: RecentLogRingBuffer = RecentLogRingBuffer()

    @Volatile
    private var fileSink: DiagnosticFileSink? = null

    /**
     * Point the on-disk sink at [directory] (typically
     * `getExternalFilesDir(null)`), creating `bada-diagnostics.log`. Safe to
     * call more than once; the last call wins. Failures are swallowed — the
     * in-memory buffer and logcat mirror keep working regardless.
     */
    public fun configureFileSink(
        directory: File,
        maxBytes: Long = DEFAULT_FILE_MAX_BYTES,
    ) {
        fileSink =
            runCatching { DiagnosticFileSink(File(directory, FILE_NAME), maxBytes) }
                .getOrNull()
    }

    public fun i(
        tag: String,
        message: String,
    ): Int {
        emit(level = 'I', tag = tag, message = message)
        return Log.i(tag, message)
    }

    public fun w(
        tag: String,
        message: String,
    ): Int {
        emit(level = 'W', tag = tag, message = message)
        return Log.w(tag, message)
    }

    public fun w(
        tag: String,
        message: String,
        throwable: Throwable,
    ): Int {
        emit(level = 'W', tag = tag, message = messageWithThrowable(message, throwable))
        return Log.w(tag, message, throwable)
    }

    public fun e(
        tag: String,
        message: String,
    ): Int {
        emit(level = 'E', tag = tag, message = message)
        return Log.e(tag, message)
    }

    public fun e(
        tag: String,
        message: String,
        throwable: Throwable,
    ): Int {
        emit(level = 'E', tag = tag, message = messageWithThrowable(message, throwable))
        return Log.e(tag, message, throwable)
    }

    /**
     * Dumps recent buffered lines as timestamped plain text, oldest first.
     */
    public fun dumpRecent(
        maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
        nowMillis: Long = System.currentTimeMillis(),
    ): String =
        recentLogBuffer
            .snapshotSince(
                cutoffMillis = nowMillis - maxAgeMillis,
            ).joinToString(separator = "\n") { line -> formatLine(line) }

    /**
     * Absolute path of the on-disk diagnostics file, or `null` if no sink is
     * configured. The bug-report collector reads this back into the archive.
     */
    public fun fileSinkPath(): String? = fileSink?.path

    private fun emit(
        level: Char,
        tag: String,
        message: String,
    ) {
        val timestampMillis = System.currentTimeMillis()
        recentLogBuffer.record(
            timestampMillis = timestampMillis,
            level = level,
            tag = tag,
            message = message,
        )
        fileSink?.append(
            formatLine(
                BufferedLogLine(
                    timestampMillis = timestampMillis,
                    level = level,
                    tag = tag,
                    message = message,
                ),
            ),
        )
    }

    private fun formatLine(line: BufferedLogLine): String =
        "${line.timestampMillis} ${line.level}/${line.tag}: ${line.message}"

    internal fun clearForTesting() {
        recentLogBuffer.clear()
        fileSink = null
    }

    private fun messageWithThrowable(
        message: String,
        throwable: Throwable,
    ): String {
        val stacktrace = StringWriter()
        PrintWriter(stacktrace).use { throwable.printStackTrace(it) }
        return "$message\n${stacktrace.toString().trimEnd()}"
    }

    public const val DEFAULT_MAX_AGE_MILLIS: Long = 15 * 60 * 1000L

    /** Per-file cap before the current log rotates to a single `.1` backup. */
    public const val DEFAULT_FILE_MAX_BYTES: Long = 512 * 1024L

    internal const val FILE_NAME: String = "bada-diagnostics.log"
}

/**
 * Append-only text sink with a single-backup size rotation. When [file] grows
 * past [maxBytes] it is renamed to `<file>.1` (replacing any previous backup)
 * and a fresh [file] is started. Reading both `<file>.1` then `<file>` yields
 * the lines in chronological order.
 *
 * Pure `java.io` so it stays JVM-testable; all writes are serialized on the
 * instance lock because diagnostics arrive from arbitrary BLE/GATT threads.
 */
internal class DiagnosticFileSink(
    private val file: File,
    private val maxBytes: Long,
) {
    val path: String = file.absolutePath
    private val backup: File = File(file.parentFile, "${file.name}.1")

    init {
        file.parentFile?.mkdirs()
    }

    @Synchronized
    fun append(line: String) {
        runCatching {
            if (file.length() >= maxBytes && file.exists()) {
                if (backup.exists()) backup.delete()
                file.renameTo(backup)
            }
            file.appendText("$line\n")
        }
    }
}

/**
 * Pure-Kotlin bounded buffer for recent log lines.
 */
internal class RecentLogRingBuffer(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val lines: ArrayDeque<BufferedLogLine> = ArrayDeque(maxEntries)

    @Synchronized
    fun record(
        timestampMillis: Long,
        level: Char,
        tag: String,
        message: String,
    ) {
        if (lines.size >= maxEntries) {
            lines.removeFirst()
        }
        lines.addLast(
            BufferedLogLine(
                timestampMillis = timestampMillis,
                level = level,
                tag = tag,
                message = message,
            ),
        )
    }

    @Synchronized
    fun snapshotSince(cutoffMillis: Long): List<BufferedLogLine> = lines.filter { it.timestampMillis >= cutoffMillis }

    @Synchronized
    fun clear() {
        lines.clear()
    }

    internal companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 2_048
    }
}

internal data class BufferedLogLine(
    val timestampMillis: Long,
    val level: Char,
    val tag: String,
    val message: String,
)
