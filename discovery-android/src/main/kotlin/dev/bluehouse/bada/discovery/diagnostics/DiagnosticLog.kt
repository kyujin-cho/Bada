/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery.diagnostics

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.util.ArrayDeque
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

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
        val previous = fileSink
        fileSink =
            runCatching { DiagnosticFileSink(File(directory, FILE_NAME), maxBytes) }
                .getOrNull()
        previous?.shutdown()
    }

    /**
     * Block up to [timeoutMillis] until queued diagnostics are written and
     * flushed to disk. The bug-report collector calls this before reading the
     * file back so the lines that prompted the report are not still buffered.
     */
    public fun flushFileSink(timeoutMillis: Long = DEFAULT_FLUSH_TIMEOUT_MILLIS) {
        fileSink?.flush(timeoutMillis)
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
        fileSink?.shutdown()
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

    /** Default barrier for [flushFileSink] at bug-report time. */
    public const val DEFAULT_FLUSH_TIMEOUT_MILLIS: Long = 2_000L
}

/**
 * Append-only text sink with a single-backup size rotation. When the current
 * file grows past [maxBytes] it is renamed to `<file>.1` (replacing any
 * previous backup) and a fresh file is started; reading `<file>.1` then
 * `<file>` yields the lines in chronological order.
 *
 * Diagnostics arrive from arbitrary BLE/GATT binder threads, where blocking on
 * disk I/O could stall callback processing during the very bootstrap path we
 * are trying to diagnose (#201). So [append] only hands the line to an
 * unbounded queue drained by a single daemon writer thread that holds one
 * long-lived [BufferedWriter] — no `open`/`write`/`close` per line, and no disk
 * work on the caller. The writer flushes after each drained batch (so a burst
 * lands on disk promptly once it quiesces); [flush] adds an explicit barrier
 * for read-back at report time.
 */
internal class DiagnosticFileSink(
    private val file: File,
    private val maxBytes: Long,
) {
    private val backup: File = File(file.parentFile, "${file.name}.1")
    private val queue: BlockingQueue<Item> = LinkedBlockingQueue()

    init {
        file.parentFile?.mkdirs()
        Thread(::runLoop, "bada-diag-sink").apply {
            isDaemon = true
            start()
        }
    }

    fun append(line: String) {
        queue.offer(Item.Line(line))
    }

    fun flush(timeoutMillis: Long) {
        val latch = CountDownLatch(1)
        queue.offer(Item.Flush(latch))
        runCatching { latch.await(timeoutMillis, TimeUnit.MILLISECONDS) }
    }

    fun shutdown() {
        queue.offer(Item.Stop)
    }

    @Suppress("NestedBlockDepth")
    private fun runLoop() {
        var out: BufferedWriter? = null
        // Append mode preserves earlier-session logs; seed the byte counter
        // from disk so the size cap accounts for them.
        var written: Long = if (file.exists()) file.length() else 0L
        try {
            while (true) {
                val batch = ArrayList<Item>()
                batch.add(queue.take())
                queue.drainTo(batch)
                for (item in batch) {
                    when (item) {
                        is Item.Line -> {
                            val text = item.line + "\n"
                            if (written >= maxBytes) {
                                out?.close()
                                out = null
                                rotate()
                                written = 0L
                            }
                            val writer = out ?: openWriter().also { out = it }
                            writer.write(text)
                            written += text.toByteArray(Charsets.UTF_8).size
                        }

                        is Item.Flush -> {
                            runCatching { out?.flush() }
                            item.latch.countDown()
                        }

                        Item.Stop -> {
                            runCatching {
                                out?.flush()
                                out?.close()
                            }
                            return
                        }
                    }
                }
                runCatching { out?.flush() }
            }
        } catch (_: InterruptedException) {
            runCatching {
                out?.flush()
                out?.close()
            }
        }
    }

    private fun openWriter(): BufferedWriter =
        BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))

    private fun rotate() {
        runCatching {
            if (backup.exists()) backup.delete()
            // If the rename fails (filesystem-dependent), truncate instead so
            // the cap stays a real bound rather than letting the file grow.
            if (!file.renameTo(backup)) file.writeText("")
        }
    }

    private sealed interface Item {
        data class Line(
            val line: String,
        ) : Item

        data class Flush(
            val latch: CountDownLatch,
        ) : Item

        data object Stop : Item
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
