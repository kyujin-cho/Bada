/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.discovery.diagnostics

import android.util.Log
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
 */
public object DiagnosticLog {
    private val recentLogBuffer: RecentLogRingBuffer = RecentLogRingBuffer()

    public fun i(
        tag: String,
        message: String,
    ): Int {
        recentLogBuffer.record(
            timestampMillis = System.currentTimeMillis(),
            level = 'I',
            tag = tag,
            message = message,
        )
        return Log.i(tag, message)
    }

    public fun w(
        tag: String,
        message: String,
    ): Int {
        recentLogBuffer.record(
            timestampMillis = System.currentTimeMillis(),
            level = 'W',
            tag = tag,
            message = message,
        )
        return Log.w(tag, message)
    }

    public fun w(
        tag: String,
        message: String,
        throwable: Throwable,
    ): Int {
        recentLogBuffer.record(
            timestampMillis = System.currentTimeMillis(),
            level = 'W',
            tag = tag,
            message = messageWithThrowable(message, throwable),
        )
        return Log.w(tag, message, throwable)
    }

    public fun e(
        tag: String,
        message: String,
    ): Int {
        recentLogBuffer.record(
            timestampMillis = System.currentTimeMillis(),
            level = 'E',
            tag = tag,
            message = message,
        )
        return Log.e(tag, message)
    }

    public fun e(
        tag: String,
        message: String,
        throwable: Throwable,
    ): Int {
        recentLogBuffer.record(
            timestampMillis = System.currentTimeMillis(),
            level = 'E',
            tag = tag,
            message = messageWithThrowable(message, throwable),
        )
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
            ).joinToString(separator = "\n") { line ->
                "${line.timestampMillis} ${line.level}/${line.tag}: ${line.message}"
            }

    internal fun clearForTesting() {
        recentLogBuffer.clear()
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
