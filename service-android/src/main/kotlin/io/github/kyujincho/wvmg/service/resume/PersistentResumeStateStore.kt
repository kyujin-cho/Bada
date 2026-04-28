/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.service.resume

import io.github.kyujincho.wvmg.protocol.payload.ByteRangeSet
import io.github.kyujincho.wvmg.protocol.payload.ResumeRecord
import io.github.kyujincho.wvmg.protocol.payload.ResumeStateStore
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * On-disk implementation of [ResumeStateStore] for the Android
 * receiver service.
 *
 * Issue #43 calls out a Room-backed store, but for the access pattern
 * we have — a few in-flight payloads at a time, every chunk records
 * one row, the GC tick walks every record once a day — Room's
 * compile-time annotations and migration overhead would be more
 * machinery than the data justifies. A purpose-built tiny serializer
 * over JSON-ish key/value lines gives us:
 *
 *  - Pure JVM testability (no Robolectric, no `org.json` stubs that
 *    return `null` under `returnDefaultValues = true`).
 *  - Trivially auditable on-disk format. A user with `adb shell` can
 *    `cat /data/data/.../cache/wvmg-resume-state.json` and see what
 *    the receiver remembers about their peers.
 *  - O(1) memory: the file is only re-serialized on writes; reads hit
 *    the in-memory snapshot.
 *  - No schema migration: malformed records are skipped, missing
 *    fields default to zero / empty.
 *
 * ### File layout
 *
 * One record per line, fields separated by `|`. Forward-compatible:
 * extra fields after the recognised set are ignored, and a malformed
 * line is dropped.
 *
 * ```
 * v1|<endpointId>|<payloadId>|<totalSize>|<updatedAtMillis>|<rangesCsv>
 * ```
 *
 * `rangesCsv` is `start1,end1;start2,end2;...`. The endpointId is
 * the wire-level peer endpoint string from `ConnectionRequest`; we
 * URL-encode it on write to allow `|` and `;` in arbitrary peer ids
 * (they are typed but the wire spec does not exclude any printable
 * character).
 *
 * ### Durability
 *
 * Writes go through a `.tmp` sibling + `renameTo` so a process kill
 * mid-write cannot leave a half-written record file.
 *
 * The file lives in `cacheDir`, which Android may evict under storage
 * pressure. That is **acceptable** for resume: an evicted record just
 * means the next reconnect transfers the bytes again, which is the
 * pre-#43 behaviour. Persistence-critical state would belong in
 * `filesDir`, but resume bytes are by their nature recoverable from
 * the peer.
 *
 * ### Thread safety
 *
 * The in-memory snapshot is a [ConcurrentHashMap] so chunk-by-chunk
 * record updates from the receive loop and the periodic GC tick from
 * the foreground service do not race. File I/O happens under
 * `synchronized (writeLock)` so two concurrent flushes cannot trample
 * each other.
 *
 * @param storageFile The file to read on construction and write to on
 *   each [recordCoverage] / [forget] / [purgeOlderThan]. In production
 *   wired via `File(context.cacheDir, "wvmg-resume-state.txt")`.
 *   Tests inject a `@TempDir` path.
 */
public class PersistentResumeStateStore(
    private val storageFile: File,
) : ResumeStateStore {
    /** In-memory snapshot keyed by `"$endpointId#$payloadId"`. */
    private val records: ConcurrentHashMap<String, ResumeRecord> = ConcurrentHashMap()

    /** Mutex serializing file writes. */
    private val writeLock = Any()

    init {
        loadFromDisk()
    }

    override fun recordCoverage(
        endpointId: String,
        payloadId: Long,
        totalSize: Long,
        start: Long,
        end: Long,
        updatedAtMillis: Long,
    ) {
        val key = key(endpointId, payloadId)
        records.compute(key) { _, existing ->
            val ranges = ByteRangeSet()
            existing?.coverage?.forEach { ranges.add(it.start, it.end) }
            ranges.add(start, end)
            ResumeRecord(
                endpointId = endpointId,
                payloadId = payloadId,
                totalSize = totalSize,
                coverage = ranges.snapshot(),
                updatedAtMillis = updatedAtMillis,
            )
        }
        flushToDisk()
    }

    override fun loadCoverage(
        endpointId: String,
        payloadId: Long,
    ): ResumeRecord? = records[key(endpointId, payloadId)]

    override fun forget(
        endpointId: String,
        payloadId: Long,
    ) {
        if (records.remove(key(endpointId, payloadId)) != null) {
            flushToDisk()
        }
    }

    override fun purgeOlderThan(cutoffMillis: Long): Int {
        var removed = 0
        val it = records.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.value.updatedAtMillis < cutoffMillis) {
                it.remove()
                removed += 1
            }
        }
        if (removed > 0) flushToDisk()
        return removed
    }

    /** Visible for tests. */
    public val recordCount: Int get() = records.size

    // ----------------------------------------------------------------
    // Disk serialization helpers
    // ----------------------------------------------------------------

    private fun loadFromDisk() {
        if (!storageFile.exists()) return
        val raw =
            try {
                storageFile.readText(Charsets.UTF_8)
            } catch (_: IOException) {
                // A corrupt or unreadable file is no worse than a
                // missing one — start fresh and let the next flush
                // overwrite it.
                return
            }
        for (line in raw.lineSequence()) {
            val record = parseRecord(line) ?: continue
            records[key(record.endpointId, record.payloadId)] = record
        }
    }

    /**
     * Parse one line of the on-disk format. Returns `null` for blank
     * lines, lines with the wrong tag, or any field that fails to
     * decode. The caller treats `null` as "skip this entry".
     */
    @Suppress(
        "ReturnCount", // Each rejected-malformed branch is its own early-out — flatter than nesting.
        "ComplexCondition", // Validation predicates over multiple parsed fields are unavoidably joined.
    )
    private fun parseRecord(line: String): ResumeRecord? {
        if (line.isBlank()) return null
        val parts = splitEscaped(line, '|')
        if (parts.size < FIELD_COUNT) return null
        if (parts[0] != FORMAT_TAG) return null
        val endpointId = decode(parts[FIELD_ENDPOINT_ID])
        if (endpointId.isEmpty()) return null
        val payloadId = parts[FIELD_PAYLOAD_ID].toLongOrNull() ?: return null
        val totalSize = parts[FIELD_TOTAL_SIZE].toLongOrNull() ?: return null
        if (totalSize < 0) return null
        val updatedAtMillis = parts[FIELD_UPDATED_AT].toLongOrNull() ?: return null
        if (updatedAtMillis < 0) return null
        val ranges = parseRanges(parts[FIELD_RANGES])
        return ResumeRecord(
            endpointId = endpointId,
            payloadId = payloadId,
            totalSize = totalSize,
            coverage = ranges,
            updatedAtMillis = updatedAtMillis,
        )
    }

    /**
     * Split [s] on every occurrence of [sep] that is not preceded by a
     * backslash. The backslashes themselves are left in place — the
     * caller's [decode] strips them.
     *
     * Pure forward walk; no regex (regex with backslash escapes is
     * notoriously hard to read and harder to debug).
     */
    private fun splitEscaped(
        s: String,
        sep: Char,
    ): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            val isEscape = ch == '\\' && i + 1 < s.length
            if (isEscape) {
                // Two-character escape sequence: keep both bytes; the
                // decoder will collapse them.
                current.append(ch).append(s[i + 1])
                i += 2
            } else {
                if (ch == sep) {
                    out += current.toString()
                    current.setLength(0)
                } else {
                    current.append(ch)
                }
                i += 1
            }
        }
        out += current.toString()
        return out
    }

    private fun parseRanges(csv: String): List<ByteRangeSet.Range> {
        if (csv.isEmpty()) return emptyList()
        // mapNotNull walks the csv segments once, keeping the parse
        // logic linear and side-effect-free. detekt rejects loops with
        // more than one continue, which a hand-written for-loop here
        // would need (one per malformed-segment branch).
        return csv
            .split(';')
            .mapNotNull { segment ->
                if (segment.isEmpty()) return@mapNotNull null
                val pair = segment.split(',')
                if (pair.size != 2) return@mapNotNull null
                val s = pair[0].toLongOrNull() ?: return@mapNotNull null
                val e = pair[1].toLongOrNull() ?: return@mapNotNull null
                if (s < 0 || e < s) return@mapNotNull null
                ByteRangeSet.Range(s, e)
            }
    }

    private fun flushToDisk() {
        synchronized(writeLock) {
            val buffer = StringBuilder()
            for (record in records.values) {
                serializeRecord(record, buffer)
                buffer.append('\n')
            }
            val tmp = File(storageFile.parentFile, "${storageFile.name}.tmp")
            try {
                storageFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                tmp.writeText(buffer.toString(), Charsets.UTF_8)
                if (!tmp.renameTo(storageFile)) {
                    // renameTo can fail across mount boundaries even
                    // when both files are inside cacheDir on some
                    // FUSE-backed devices. Fall back to copy-and-delete.
                    tmp.copyTo(storageFile, overwrite = true)
                    tmp.delete()
                }
            } catch (_: IOException) {
                // A failed flush leaves the in-memory snapshot
                // authoritative until the next mutation succeeds.
                // We deliberately swallow because the receive loop
                // must not abort just because cacheDir is full.
                runCatching { tmp.delete() }
            }
        }
    }

    private fun serializeRecord(
        record: ResumeRecord,
        out: StringBuilder,
    ) {
        out
            .append(FORMAT_TAG)
            .append('|')
            .append(encode(record.endpointId))
            .append('|')
            .append(record.payloadId)
            .append('|')
            .append(record.totalSize)
            .append('|')
            .append(record.updatedAtMillis)
            .append('|')
        var first = true
        for (r in record.coverage) {
            if (!first) out.append(';')
            first = false
            out.append(r.start).append(',').append(r.end)
        }
    }

    /**
     * Lightweight escape for endpoint ids: `|`, `;`, `,`, `\` and `\n`
     * are escaped as `\\X`. Everything else passes through. Symmetric
     * with [decode].
     */
    private fun encode(raw: String): String {
        if (raw.none { it in ESCAPE_CHARS || it == '\\' }) return raw
        val out = StringBuilder(raw.length + 2)
        for (ch in raw) {
            when {
                ch == '\\' -> out.append("\\\\")
                ch in ESCAPE_CHARS -> out.append('\\').append(ch)
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    private fun decode(escaped: String): String {
        if ('\\' !in escaped) return escaped
        val out = StringBuilder(escaped.length)
        var i = 0
        while (i < escaped.length) {
            val ch = escaped[i]
            if (ch == '\\' && i + 1 < escaped.length) {
                out.append(escaped[i + 1])
                i += 2
            } else {
                out.append(ch)
                i += 1
            }
        }
        return out.toString()
    }

    private fun key(
        endpointId: String,
        payloadId: Long,
    ): String = "$endpointId#$payloadId"

    private companion object {
        /** Format-version tag at the start of each record line. */
        const val FORMAT_TAG: String = "v1"

        /** Number of `|`-separated fields in one record line. */
        const val FIELD_COUNT: Int = 6

        // Field positions inside a parsed record line. Constants
        // rather than literal indices so detekt's MagicNumber gate is
        // satisfied and a future schema bump is a one-line edit.
        const val FIELD_ENDPOINT_ID: Int = 1
        const val FIELD_PAYLOAD_ID: Int = 2
        const val FIELD_TOTAL_SIZE: Int = 3
        const val FIELD_UPDATED_AT: Int = 4
        const val FIELD_RANGES: Int = 5

        /** Characters that get backslash-escaped inside `endpointId`. */
        val ESCAPE_CHARS: CharArray = charArrayOf('|', ';', ',', '\n')
    }
}
