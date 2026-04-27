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

    override fun commit(destination: DownloadsEnvironment.Destination) {
        val slot = slots[destination.displayName] ?: return
        if (slot.discarded) return
        slot.pending = false
        slot.committed = true
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
    ) : DownloadsEnvironment.Destination {
        override val internalKey: Any get() = displayName
    }

    private data class FakeDestination(
        override val displayName: String,
    ) : DownloadsEnvironment.Destination {
        override val internalKey: Any get() = displayName
    }
}
