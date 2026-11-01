/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.send

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import dev.bluehouse.bada.protocol.connection.FileSource
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import kotlin.math.absoluteValue
import dev.bluehouse.bada.discovery.diagnostics.DiagnosticLog as Log

/**
 * Translates Android `content://` URIs (received from the system share
 * sheet) into protocol-level [FileSource] instances that
 * [dev.bluehouse.bada.protocol.connection.OutboundConnection.run]
 * understands.
 *
 * `:core-protocol` is platform-independent and cannot reference
 * `android.net.Uri` / [ContentResolver] / [OpenableColumns]. This adapter
 * lives in `:app` and bridges the two worlds:
 *
 *  - **Name** — read from `OpenableColumns.DISPLAY_NAME` if available,
 *    falling back to the URI's last path segment, falling back to a
 *    generic placeholder. The Quick Share spec does not mandate a
 *    particular name shape, but receivers display this string verbatim.
 *  - **Size** — read from `OpenableColumns.SIZE`, with an
 *    `AssetFileDescriptor` length/stat fallback for providers that can
 *    stream the URI but omit a usable cursor size. If all paths are
 *    unknown (`-1`, missing column, or a `null` cursor), we report `0`.
 *    The receiver will then either trust the announced size or simply
 *    receive bytes until the channel runs out (current orchestrator
 *    behaviour: streams up to `size` bytes — so an unknown size yields
 *    a zero-byte transfer).
 *  - **MIME type** — `ContentResolver.getType(uri)`. May be `null`
 *    (e.g. for plain `file://` URIs without a registered mapping); we
 *    surface an empty string in that case so the receiver renders the
 *    item with no MIME hint rather than `null`-ing out the column.
 *  - **openChannel** — wraps `ContentResolver.openInputStream(uri)` in
 *    a fresh [ReadableByteChannel] every time the orchestrator asks
 *    for one. The Quick Share orchestrator opens the channel exactly
 *    once per transfer attempt, but the factory is callable multiple
 *    times for retries and the channel is closed by the orchestrator,
 *    so we must produce a brand-new stream on each invocation.
 *
 * ### Why a separate class instead of an inline helper
 *
 * Two reasons:
 *
 *  1. **Testability.** The Activity itself is hard to unit-test on a
 *     pure JVM (Robolectric is overkill for #24's scope per the issue
 *     instructions). Lifting the URI → FileSource conversion behind an
 *     interface lets the host test pass in a fake [ContentResolver]-
 *     equivalent and verify name/size/MIME extraction without an
 *     emulator.
 *  2. **Single responsibility.** The Activity drives discovery + UI;
 *     this class translates URIs. Mixing the two would couple the
 *     unit-of-test for URI handling to the Activity lifecycle.
 *
 * Note: We do NOT call `ContentResolver.takePersistableUriPermission`
 * here. The share-sheet flow grants the receiving Activity a temporary
 * read grant for the lifetime of the task, which is enough for our
 * one-shot send. Persistable URI permissions only matter when an app
 * remembers a URI across cold starts — that is not our use case.
 */
public class UriFileSourceFactory internal constructor(
    private val metadataReader: UriMetadataReader,
    private val channelOpener: UriChannelOpener,
    private val payloadIdGenerator: () -> Long,
) {
    /** Production constructor, bound to a real Android [ContentResolver]. */
    public constructor(contentResolver: ContentResolver) : this(
        metadataReader = ContentResolverMetadataReader(contentResolver),
        channelOpener = ContentResolverChannelOpener(contentResolver),
        payloadIdGenerator = ::randomPositivePayloadId,
    )

    /**
     * Build a [FileSource] from a single content URI.
     *
     * Never throws on missing columns — falls back to sensible defaults
     * (see the class-level docs). Throws only when [channelOpener] is
     * invoked later by the orchestrator and the underlying input stream
     * cannot be opened.
     *
     * @param uri Content URI as delivered in the share intent's
     *   `EXTRA_STREAM`. Must be readable by the running app under the
     *   share-sheet grant.
     */
    public fun fromUri(uri: Uri): FileSource {
        val metadata = metadataReader.read(uri)
        return buildFileSource(
            metadata = metadata,
            fallbackPathSegment = uri.lastPathSegment,
            payloadId = payloadIdGenerator(),
            open = { channelOpener.open(uri) },
        )
    }

    /**
     * Pure-JVM core of the URI-to-[FileSource] mapping. Lifted out of
     * [fromUri] so the field-level translation rules (display name
     * fallbacks, size coercion, MIME defaulting) can be exercised in a
     * unit test without constructing a real `android.net.Uri` — `Uri`
     * is an Android type and cannot be parsed in host JVM tests.
     *
     * @param metadata The OpenableColumns row. May carry `null` /
     *   missing fields; this function applies the documented fallbacks.
     * @param fallbackPathSegment The URI's last path segment, used when
     *   [UriMetadata.displayName] is missing. Pass `null` when the URI
     *   has no useful path (then [DEFAULT_NAME] is used).
     * @param payloadId Pre-generated positive payload id.
     * @param open Channel factory the resulting [FileSource] surfaces
     *   verbatim. The factory is only invoked once the orchestrator
     *   begins streaming.
     */
    internal fun buildFileSource(
        metadata: UriMetadata,
        fallbackPathSegment: String?,
        payloadId: Long,
        open: () -> ReadableByteChannel,
    ): FileSource {
        val name = metadata.displayName ?: fallbackPathSegment ?: DEFAULT_NAME
        val size = metadata.size.coerceAtLeast(0L)
        val mimeType = metadata.mimeType ?: ""
        return FileSource(
            name = name,
            size = size,
            mimeType = mimeType,
            // 0 means unknown; the encoder then leaves the header field
            // unset so the receiver uses its own clock (#310).
            lastModifiedTimestampMillis = metadata.lastModifiedMillis.coerceAtLeast(0L),
            payloadId = payloadId,
            open = open,
        )
    }

    public companion object {
        /** Fallback display name when neither MediaStore nor the URI carries one. */
        public const val DEFAULT_NAME: String = "shared-file"

        /** Conversion factor between Unix seconds and the wire's millis. */
        internal const val SECOND_IN_MILLIS: Long = 1000L

        /**
         * Unix seconds stay below this until the year 5138, while Unix
         * millis passed it in 1973, so it tells the two apart.
         */
        private const val MIN_EPOCH_MILLIS: Long = 100_000_000_000L

        /**
         * Normalizes a provider timestamp to Unix millis, or `0L` when it
         * is unknown. `MediaStore.MediaColumns.DATE_MODIFIED` is documented
         * as seconds and `DocumentsContract.Document.COLUMN_LAST_MODIFIED`
         * as millis, but providers do not always follow their contract;
         * the magnitude decides the unit (#310).
         */
        internal fun normalizeEpochMillis(raw: Long): Long =
            when {
                raw <= 0L -> 0L
                raw < MIN_EPOCH_MILLIS -> raw * SECOND_IN_MILLIS
                else -> raw
            }

        /**
         * Generates a positive 63-bit payload id. The Quick Share
         * `payload_id` proto field is `int64`; the orchestrator requires
         * positive values (see `OutboundConnection.validateFiles`).
         *
         * `kotlin.random.Random.nextLong()` covers `Long.MIN_VALUE..Long.MAX_VALUE`,
         * so we take the absolute value and `or` with `1` to guarantee
         * non-zero. The collision probability across a single transfer's
         * file list is vanishingly small (the orchestrator only requires
         * uniqueness within a single connection), and the orchestrator
         * itself rejects any list with duplicates.
         */
        public fun randomPositivePayloadId(): Long {
            val raw =
                kotlin.random.Random.Default
                    .nextLong()
                    .absoluteValue
            return if (raw == 0L) 1L else raw
        }
    }
}

/**
 * Lightweight value carrier for the three columns we read off a URI.
 * Modeled as a plain data class instead of a `Pair`/`Triple` so the
 * call site can read field names instead of indices.
 */
public data class UriMetadata(
    /** `OpenableColumns.DISPLAY_NAME`. `null` when the column is missing or empty. */
    val displayName: String?,
    /** `OpenableColumns.SIZE`. `-1` when the column is unknown. */
    val size: Long,
    /** `ContentResolver.getType(uri)`. `null` when no mapping is known. */
    val mimeType: String?,
    /**
     * Last modification time in Unix **millis**, `0L` when unknown.
     * [ContentResolverMetadataReader] takes it from
     * `MediaStore.MediaColumns.DATE_MODIFIED` (MediaStore URIs),
     * `DocumentsContract.Document.COLUMN_LAST_MODIFIED` (picker and
     * document URIs), or `fstat` on a regular-file descriptor (e.g.
     * FileProvider shares from file managers), in that order. Some
     * providers (NFC share, in-app stream URIs) expose none of these;
     * that is a documented "unknown", not an error. See #41 and #310.
     */
    val lastModifiedMillis: Long = 0L,
)

/**
 * Reads display name + size + MIME for a single URI.
 *
 * Lifted to an interface so that JVM unit tests can supply a fake
 * implementation without pulling Robolectric in.
 */
public interface UriMetadataReader {
    public fun read(uri: Uri): UriMetadata
}

/**
 * Opens a fresh [ReadableByteChannel] over a URI. Separated from
 * [UriMetadataReader] because the metadata read happens once per share
 * intent (cheap, on the UI thread) but channel opening happens during
 * the actual transfer (potentially on a different dispatcher).
 */
public interface UriChannelOpener {
    public fun open(uri: Uri): ReadableByteChannel
}

/**
 * Production [UriMetadataReader] backed by a real [ContentResolver].
 *
 * Queries OpenableColumns. Both columns are documented as supported by
 * any `content://` provider that participates in the system share flow,
 * but real-world providers occasionally return cursors with missing
 * columns or null entries — we handle both cases without throwing.
 */
internal class ContentResolverMetadataReader(
    private val contentResolver: ContentResolver,
) : UriMetadataReader {
    override fun read(uri: Uri): UriMetadata {
        val cursorMetadata = readCursorColumns(uri)
        val size = resolveSize(uri, cursorMetadata.size)
        val mime = contentResolver.getType(uri)
        return UriMetadata(
            displayName = cursorMetadata.displayName,
            size = size,
            mimeType = mime,
            lastModifiedMillis = resolveLastModifiedMillis(uri, cursorMetadata.lastModifiedSeconds),
        )
    }

    /**
     * Share-sheet and picker URIs mostly come from DocumentsProviders,
     * which expose `COLUMN_LAST_MODIFIED` instead of MediaStore's
     * `DATE_MODIFIED`, and FileProvider shares expose neither. Each
     * fallback only runs when the previous source came back empty (#310).
     */
    private fun resolveLastModifiedMillis(
        uri: Uri,
        mediaStoreSeconds: Long,
    ): Long =
        UriFileSourceFactory.normalizeEpochMillis(mediaStoreSeconds).takeIf { it > 0L }
            ?: UriFileSourceFactory.normalizeEpochMillis(readDocumentLastModified(uri)).takeIf { it > 0L }
            ?: readDescriptorModifiedMillis(uri)

    /**
     * Separate query because MediaStore rejects projections that name
     * columns it does not know, which would also lose the name and size
     * read above.
     */
    private fun readDocumentLastModified(uri: Uri): Long =
        runCatching {
            contentResolver
                .query(uri, DOCUMENT_PROJECTION, null, null, null)
                ?.use { cursor: Cursor ->
                    val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    if (cursor.moveToFirst() && index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else 0L
                } ?: 0L
        }.getOrDefault(0L)

    /**
     * Last resort for providers that expose no timestamp column. Only a
     * regular file has a meaningful mtime; pipes and sockets are skipped.
     */
    private fun readDescriptorModifiedMillis(uri: Uri): Long =
        runCatching {
            contentResolver
                .openFileDescriptor(uri, READ_MODE)
                ?.use { descriptor ->
                    val stat = Os.fstat(descriptor.fileDescriptor)
                    if (OsConstants.S_ISREG(stat.st_mode)) {
                        UriFileSourceFactory.normalizeEpochMillis(stat.st_mtime)
                    } else {
                        0L
                    }
                } ?: 0L
        }.getOrElse { e ->
            Log.e(TAG, "URI descriptor mtime fallback failed uri=$uri: ${e.message}", e)
            0L
        }

    /**
     * Issues a `query()` against [PROJECTION] and extracts the display
     * name, size, and last-modified timestamp with documented fallbacks
     * (`null` / `-1` / `0`) for missing or null cells. Lifted out of
     * [read] so the cursor-walk depth stays well under detekt's
     * `NestedBlockDepth` threshold.
     *
     * `MediaStore.MediaColumns.DATE_MODIFIED` is queried alongside the
     * `OpenableColumns`. Providers that do not expose it return a
     * missing column, which we map to `0L`; [resolveLastModifiedMillis]
     * then tries the document column and the file descriptor.
     */
    private fun readCursorColumns(uri: Uri): CursorMetadata {
        var displayName: String? = null
        var size: Long = -1L
        var lastModifiedSeconds: Long = 0L
        // ContentResolver.query: (uri, projection, selection, selectionArgs, sortOrder).
        // Selection / args / order are unused — we only need the
        // projection columns for the single referenced URI.
        contentResolver
            .query(uri, PROJECTION, null, null, null)
            ?.use { cursor: Cursor ->
                if (cursor.moveToFirst()) {
                    displayName = readDisplayName(cursor)
                    size = readSize(cursor)
                    lastModifiedSeconds = readLastModifiedSeconds(cursor)
                }
            }
        return CursorMetadata(displayName, size, lastModifiedSeconds)
    }

    private fun readDisplayName(cursor: Cursor): String? {
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index < 0 || cursor.isNull(index)) return null
        val raw = cursor.getString(index)
        return if (raw.isNullOrBlank()) null else raw
    }

    private fun readSize(cursor: Cursor): Long {
        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (index < 0 || cursor.isNull(index)) return -1L
        return cursor.getLong(index)
    }

    private fun resolveSize(
        uri: Uri,
        cursorSize: Long,
    ): Long {
        if (cursorSize > 0L) return cursorSize

        val descriptorSize = readDescriptorSize(uri)
        val resolvedSize =
            when {
                descriptorSize > 0L -> descriptorSize
                cursorSize >= 0L -> cursorSize
                else -> -1L
            }
        Log.e(
            TAG,
            "URI size resolved uri=$uri cursorSize=$cursorSize " +
                "descriptorSize=$descriptorSize resolvedSize=$resolvedSize",
        )
        return resolvedSize
    }

    private fun readDescriptorSize(uri: Uri): Long =
        runCatching {
            contentResolver
                .openAssetFileDescriptor(uri, READ_MODE)
                ?.use(::sizeFromDescriptor)
                ?: -1L
        }.getOrElse { e ->
            Log.e(TAG, "URI descriptor size fallback failed uri=$uri: ${e.message}", e)
            -1L
        }

    private fun sizeFromDescriptor(descriptor: AssetFileDescriptor): Long {
        val length = descriptor.length
        if (length >= 0L) return length

        val statSize = descriptor.parcelFileDescriptor.statSize
        return if (statSize >= 0L) statSize else -1L
    }

    private fun readLastModifiedSeconds(cursor: Cursor): Long {
        val index = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
        if (index < 0 || cursor.isNull(index)) return 0L
        // MediaStore documents DATE_MODIFIED as Unix-time seconds. We
        // surface negative or zero values as 0L so the upstream caller
        // can route them through the "unknown timestamp" path.
        return cursor.getLong(index).coerceAtLeast(0L)
    }

    /**
     * Internal value carrier for the three cursor-derived columns. A
     * `Triple` would compile but reading `.third` for a long-since-epoch
     * timestamp is unfriendly to call sites.
     */
    private data class CursorMetadata(
        val displayName: String?,
        val size: Long,
        val lastModifiedSeconds: Long,
    )

    private companion object {
        const val TAG: String = "BadaOutbound"
        const val READ_MODE: String = "r"

        val PROJECTION =
            arrayOf(
                OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
            )

        val DOCUMENT_PROJECTION = arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
    }
}

/**
 * Production [UriChannelOpener] backed by a real [ContentResolver].
 */
internal class ContentResolverChannelOpener(
    private val contentResolver: ContentResolver,
) : UriChannelOpener {
    override fun open(uri: Uri): ReadableByteChannel {
        val stream =
            contentResolver.openInputStream(uri)
                ?: error("ContentResolver returned null InputStream for $uri")
        return Channels.newChannel(stream)
    }
}
