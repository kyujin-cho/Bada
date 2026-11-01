/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.send

import android.net.Uri
import android.os.StatFs
import dev.bluehouse.bada.protocol.connection.FileSource
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Validates Share Sheet URI access and materializes unknown-length streams.
 *
 * Called on `Dispatchers.IO` before the Send surface arms NFC. Staged files are
 * bounded by size and free-space policy, monitored for stalled reads, and owned
 * by [PreparedUriSource] until the enclosing send attempt reaches a terminal.
 */
internal class ContentUriPreparer(
    private val factory: UriFileSourceFactory,
    private val stagingDirectory: File,
) {
    fun prepare(
        uri: Uri,
        payloadId: Long,
    ): PreparedUriSource {
        val metadata = factory.readMetadata(uri)
        if (metadata.size >= 0L) {
            factory.openChannel(uri).use { /* readability probe */ }
            return PreparedUriSource(factory.fromUri(uri, metadata, payloadId))
        }
        return stageUnknownLength(uri, metadata, payloadId)
    }

    fun scavengeStale(nowMillis: Long = System.currentTimeMillis()) {
        stagingDirectory.listFiles()?.forEach { file ->
            if (nowMillis - file.lastModified() >= STALE_FILE_AGE_MS) file.delete()
        }
    }

    @Suppress(
        "CyclomaticComplexMethod",
        "NestedBlockDepth",
        "ThrowsCount",
        "LoopWithTooManyJumpStatements",
        "TooGenericExceptionCaught",
        "InstanceOfCheckForException",
    ) // Staging keeps one cleanup boundary around watchdog, stream, budget, fsync, and typed failure mapping.
    private fun stageUnknownLength(
        uri: Uri,
        metadata: UriMetadata,
        payloadId: Long,
    ): PreparedUriSource {
        check(stagingDirectory.mkdirs() || stagingDirectory.isDirectory) { "Unable to create staging directory" }
        val staged = File.createTempFile("gesture-", ".payload", stagingDirectory)
        val lastProgressNanos = AtomicLong(System.nanoTime())
        val timedOut = AtomicBoolean(false)
        val input = factory.openChannel(uri)
        val watchdog =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "gesture-staging-watchdog").apply { isDaemon = true }
            }
        watchdog.scheduleAtFixedRate(
            {
                if (System.nanoTime() - lastProgressNanos.get() >= STALL_TIMEOUT_NANOS) {
                    timedOut.set(true)
                    runCatching { input.close() }
                }
            },
            STALL_CHECK_SECONDS,
            STALL_CHECK_SECONDS,
            TimeUnit.SECONDS,
        )
        try {
            var written = 0L
            FileOutputStream(staged).channel.use { output ->
                val buffer = ByteBuffer.allocateDirect(COPY_BUFFER_BYTES)
                while (true) {
                    buffer.clear()
                    val read =
                        try {
                            input.read(buffer)
                        } catch (failure: Exception) {
                            if (timedOut.get()) {
                                throw SourcePreparationException(SourcePreparationFailure.SOURCE_STALLED, failure)
                            }
                            throw failure
                        }
                    if (read < 0) break
                    if (read == 0) continue
                    written += read
                    enforceBudget(written)
                    lastProgressNanos.set(System.nanoTime())
                    buffer.flip()
                    while (buffer.hasRemaining()) output.write(buffer)
                }
                output.force(true)
            }
            val stagedMetadata = metadata.copy(size = written)
            val source =
                factory.buildFileSource(stagedMetadata, uri.lastPathSegment, payloadId) {
                    staged.inputStream().channel
                }
            return PreparedUriSource(source) { staged.delete() }
        } catch (failure: Throwable) {
            staged.delete()
            if (failure is SourcePreparationException) throw failure
            if (timedOut.get()) throw SourcePreparationException(SourcePreparationFailure.SOURCE_STALLED, failure)
            throw SourcePreparationException(SourcePreparationFailure.SOURCE_UNREADABLE, failure)
        } finally {
            runCatching { input.close() }
            watchdog.shutdownNow()
        }
    }

    private fun enforceBudget(written: Long) {
        if (written > MAX_STAGED_ITEM_BYTES) {
            throw SourcePreparationException(SourcePreparationFailure.SOURCE_TOO_LARGE_TO_STAGE)
        }
        val stats = StatFs(stagingDirectory.absolutePath)
        val reserve = max(MIN_FREE_RESERVE_BYTES, stats.totalBytes / FREE_RESERVE_DIVISOR)
        if (stats.availableBytes <= reserve) {
            throw SourcePreparationException(SourcePreparationFailure.INSUFFICIENT_STAGING_SPACE)
        }
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 512 * 1024
        const val STALL_CHECK_SECONDS = 1L
        const val MAX_STAGED_ITEM_BYTES = 20L * 1024L * 1024L * 1024L
        const val MIN_FREE_RESERVE_BYTES = 512L * 1024L * 1024L
        const val FREE_RESERVE_DIVISOR = 10L
        const val STALL_TIMEOUT_NANOS = 30L * 1_000_000_000L
        const val STALE_FILE_AGE_MS = 24L * 60L * 60L * 1000L
    }
}

internal class PreparedUriSource(
    val source: FileSource,
    private val cleanup: () -> Unit = {},
) : AutoCloseable {
    override fun close() = cleanup()
}

internal enum class SourcePreparationFailure {
    SOURCE_UNREADABLE,
    SOURCE_STALLED,
    SOURCE_TOO_LARGE_TO_STAGE,
    INSUFFICIENT_STAGING_SPACE,
}

internal class SourcePreparationException(
    val failure: SourcePreparationFailure,
    cause: Throwable? = null,
) : Exception(failure.name, cause)
