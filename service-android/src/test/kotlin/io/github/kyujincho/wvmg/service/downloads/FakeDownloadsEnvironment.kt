/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.service.downloads

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException

/**
 * In-memory [DownloadsEnvironment] used by JVM unit tests.
 *
 * The fake mirrors the contract of the two real environments:
 *
 *  - [insertPending] reserves a slot keyed by `displayName` and throws
 *    [FileAlreadyExistsException] on collision (drives the
 *    [DownloadsWriter] retry loop).
 *  - [openOutputStream] returns a [ByteArrayOutputStream] so tests can
 *    assert on the bytes that were actually written.
 *  - [commit] / [discard] flip status fields tests can inspect.
 *
 * The fake is intentionally simple — it does NOT model concurrent
 * inserts or filesystem timing. Tests requiring those concerns belong
 * in instrumentation, not here.
 */
internal class FakeDownloadsEnvironment(
    /**
     * If non-empty, [insertPending] throws [FileAlreadyExistsException]
     * for any [displayName] in this set. Used to drive the collision
     * suffix retry path without first calling [insertPending].
     */
    private val preReservedNames: MutableSet<String> = mutableSetOf(),
) : DownloadsEnvironment {
    /**
     * Final state of every reserved slot, indexed by display name.
     * Tests assert on this map after running the writer.
     */
    val slots: MutableMap<String, FakeSlot> = LinkedHashMap()

    override fun insertPending(
        displayName: String,
        mimeType: String?,
    ): DownloadsEnvironment.Destination {
        if (displayName in preReservedNames || slots.containsKey(displayName)) {
            throw FileAlreadyExistsException(displayName)
        }
        val slot =
            FakeSlot(
                displayName = displayName,
                mimeType = mimeType,
                buffer = ByteArrayOutputStream(),
                pending = true,
                committed = false,
                discarded = false,
            )
        slots[displayName] = slot
        return FakeDestination(displayName)
    }

    override fun openOutputStream(destination: DownloadsEnvironment.Destination): OutputStream {
        val slot =
            slots[destination.displayName]
                ?: error("openOutputStream for unknown destination ${destination.displayName}")
        return slot.buffer
    }

    override fun commit(
        destination: DownloadsEnvironment.Destination,
        lastModifiedTimestampMillis: Long,
    ) {
        val slot = slots[destination.displayName] ?: return
        if (slot.discarded) return
        slot.pending = false
        slot.committed = true
        // Record the timestamp the writer asked for so #41's tests can
        // assert that the per-payload value was preserved end-to-end
        // through the factory layer.
        slot.committedLastModifiedTimestampMillis = lastModifiedTimestampMillis
    }

    override fun discard(destination: DownloadsEnvironment.Destination) {
        val slot = slots[destination.displayName] ?: return
        slot.discarded = true
        slot.pending = false
    }

    /**
     * Snapshot of a fake destination's lifecycle state. Mutated in
     * place by [commit] / [discard] so tests can inspect terminal
     * state simply by reading the [slots] map.
     */
    data class FakeSlot(
        override val displayName: String,
        val mimeType: String?,
        val buffer: ByteArrayOutputStream,
        var pending: Boolean,
        var committed: Boolean,
        var discarded: Boolean,
        /**
         * Whatever value [commit] was last invoked with for this slot.
         * `0L` until commit fires; tests for #41 assert that the
         * factory routes the sender's
         * `PayloadHeader.last_modified_timestamp_millis` through here.
         */
        var committedLastModifiedTimestampMillis: Long = 0L,
    ) : DownloadsEnvironment.Destination {
        override val internalKey: Any get() = displayName
    }

    private data class FakeDestination(
        override val displayName: String,
    ) : DownloadsEnvironment.Destination {
        override val internalKey: Any get() = displayName
    }
}
