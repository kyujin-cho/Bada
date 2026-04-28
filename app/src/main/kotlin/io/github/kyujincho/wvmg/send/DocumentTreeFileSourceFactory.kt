/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.send

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import io.github.kyujincho.wvmg.protocol.connection.FileSource
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel

/**
 * Walks a Storage Access Framework `tree://` URI (delivered by
 * `ACTION_OPEN_DOCUMENT_TREE`) and emits one [FileSource] per descendant
 * file, with `parent_folder` populated relative to the picked root (#38).
 *
 * NearDrop (the macOS reference implementation) explicitly rejects
 * multi-attachment introductions whose files carry `parent_folder` —
 * issue grishka/NearDrop#211 documents the gap. The Quick Share wire
 * spec itself supports nested directories via `FileMetadata.parent_folder`
 * (proto field 7) and `PayloadHeader.parent_folder` (offline_wire_formats
 * proto field 6); this factory is the sender-side glue that puts the
 * field to use.
 *
 * ### Why a raw `DocumentsContract` walk instead of `DocumentFile`
 *
 * `androidx.documentfile.DocumentFile` is the documented entry point but
 * it issues one `query()` per node and surfaces each row as a fresh
 * `DocumentFile` instance. On a tree of any meaningful size that is
 * cripplingly slow — every `listFiles()` call re-queries the system
 * provider for the row data we already have. We instead query
 * `buildChildDocumentsUriUsingTree(...)` directly with a tight
 * projection, walking the tree iteratively (depth-first, deterministic
 * order) so a 10 MB-plus folder doesn't stall the UI.
 *
 * ### Empty folders
 *
 * Quick Share's wire protocol has no "create empty directory" frame —
 * receivers implicitly create intermediate directories when they write
 * a file inside one. The walker therefore skips empty subdirectories
 * entirely; the receiver will simply not see them. This matches the
 * issue acceptance criteria.
 *
 * ### Lazy channels
 *
 * Each emitted [FileSource] defers `ContentResolver.openInputStream`
 * until the orchestrator calls `openChannel()`. Folders can contain
 * thousands of files and pre-opening every stream would exhaust the
 * file-descriptor limit before the user has even confirmed the send.
 *
 * ### Symbolic links / cycles
 *
 * The SAF document model has no first-class loop concept (each
 * `DOCUMENT_ID` is a distinct node), but defensively we cap recursion at
 * [MAX_DEPTH] to bound runaway provider behaviour. A cap of 32 covers
 * any sane on-device folder layout while being shallow enough that an
 * intentional pathological tree cannot wedge the walker.
 */
public class DocumentTreeFileSourceFactory internal constructor(
    private val contentResolver: ContentResolver,
    private val payloadIdGenerator: () -> Long,
) {
    /** Production constructor wired against a real Android [ContentResolver]. */
    public constructor(contentResolver: ContentResolver) : this(
        contentResolver = contentResolver,
        payloadIdGenerator = UriFileSourceFactory::randomPositivePayloadId,
    )

    /**
     * Traverse the tree under [treeUri] and emit one [FileSource] per
     * file descendant, deepest-children-last in deterministic
     * directory-then-name order. Returns an empty list if the tree
     * contains no files (e.g. only empty subdirectories).
     *
     * The picked tree's own display name is the convention for what
     * the receiver should call the top-level folder; we do NOT prepend
     * it to `parent_folder`. Reason: the receiver-side materialization
     * (issue #39) lands files under a single chosen download root and
     * the user already perceives "this came from the folder I shared",
     * so an extra level of nesting only makes the on-disk path longer.
     * For example, a folder `Trip/photos/sunset.jpg` is emitted as a
     * file with `name = "sunset.jpg"` and `parent_folder = "photos"`
     * — the `Trip/` prefix is implicit in the receiver's choice of
     * download location.
     *
     * @throws IllegalArgumentException if [treeUri] is not a tree URI
     *   (i.e. does not carry a [DocumentsContract.getTreeDocumentId]).
     */
    public fun walk(treeUri: Uri): List<FileSource> {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val collected = ArrayList<FileSource>()
        walkNode(
            treeUri = treeUri,
            documentId = rootDocId,
            // The root's relative path is empty — its files become
            // top-level attachments with `parent_folder = ""`.
            relativePath = "",
            depth = 0,
            collected = collected,
        )
        return collected
    }

    private fun walkNode(
        treeUri: Uri,
        documentId: String,
        relativePath: String,
        depth: Int,
        collected: ArrayList<FileSource>,
    ) {
        if (depth > MAX_DEPTH) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val children = queryChildren(childrenUri)
        // Sort directories last and within-kind by name so the
        // receiver-side display order is deterministic — Quick Share
        // does not specify an order, but stock Android sends top-level
        // files first followed by deeper levels and we mirror that for
        // observability.
        val sorted =
            children.sortedWith(
                compareBy({ it.isDirectory }, { it.displayName }),
            )
        for (child in sorted) {
            if (child.isDirectory) {
                val nextPath = if (relativePath.isEmpty()) child.displayName else "$relativePath/${child.displayName}"
                walkNode(
                    treeUri = treeUri,
                    documentId = child.documentId,
                    relativePath = nextPath,
                    depth = depth + 1,
                    collected = collected,
                )
            } else {
                collected.add(buildFileSource(treeUri, child, relativePath))
            }
        }
    }

    /**
     * Issue a single tight `query()` against [childrenUri] and surface
     * the results as a list of [DocumentNode]s. The projection is the
     * smallest set of columns we need — the SAF system provider walks
     * the metadata once per row and additional columns multiply that
     * cost on large trees.
     */
    private fun queryChildren(childrenUri: Uri): List<DocumentNode> {
        val out = ArrayList<DocumentNode>()
        contentResolver
            .query(childrenUri, CHILD_PROJECTION, null, null, null)
            ?.use { cursor ->
                val ix = ChildColumns.from(cursor)
                if (!ix.usable) return@use
                while (cursor.moveToNext()) {
                    readChildRow(cursor, ix)?.let(out::add)
                }
            }
        return out
    }

    /**
     * Map a single cursor row onto a [DocumentNode], or `null` when the
     * row is missing the mandatory document-id column. Pulled out of
     * [queryChildren] so the per-row mapping stays detekt-clean (one
     * `continue`-equivalent path) while keeping every column lookup
     * indexed (no by-name `getColumnIndexOrThrow` hot-path cost).
     */
    private fun readChildRow(
        cursor: android.database.Cursor,
        ix: ChildColumns,
    ): DocumentNode? {
        val documentId = cursor.getString(ix.docId) ?: return null
        val rawDisplayName = cursor.getString(ix.displayName)
        val displayName = sanitizeDisplayName(rawDisplayName, documentId)
        val mime = cursor.getString(ix.mime) ?: ""
        val size = if (ix.size >= 0 && !cursor.isNull(ix.size)) cursor.getLong(ix.size) else -1L
        val lastModified =
            if (ix.lastModified >= 0 && !cursor.isNull(ix.lastModified)) {
                cursor.getLong(ix.lastModified)
            } else {
                0L
            }
        return DocumentNode(
            documentId = documentId,
            displayName = displayName,
            mimeType = mime,
            size = size,
            lastModifiedMillis = lastModified,
            isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
        )
    }

    /**
     * Pre-resolved column indices for the children cursor. Indices are
     * looked up once per cursor and reused on every row rather than
     * paid per-row — large folder walks notice the difference.
     */
    private data class ChildColumns(
        val docId: Int,
        val displayName: Int,
        val mime: Int,
        val size: Int,
        val lastModified: Int,
    ) {
        /** True when the mandatory columns (docId, name, mime) are all present. */
        val usable: Boolean
            get() = docId >= 0 && displayName >= 0 && mime >= 0

        companion object {
            fun from(cursor: android.database.Cursor): ChildColumns =
                ChildColumns(
                    docId = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                    displayName = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    mime = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE),
                    size = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE),
                    lastModified = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                )
        }
    }

    /**
     * Build a [FileSource] from a single non-directory [node] under
     * [parentRelativePath].
     *
     * Defers `openInputStream` until the orchestrator calls
     * `openChannel()` — see the class-level "Lazy channels" note.
     */
    private fun buildFileSource(
        treeUri: Uri,
        node: DocumentNode,
        parentRelativePath: String,
    ): FileSource {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, node.documentId)
        val coercedSize = node.size.coerceAtLeast(0L)
        return FileSource(
            name = node.displayName,
            size = coercedSize,
            mimeType = node.mimeType,
            lastModifiedTimestampMillis = node.lastModifiedMillis,
            payloadId = payloadIdGenerator(),
            parentFolder = parentRelativePath,
            open = {
                val stream =
                    contentResolver.openInputStream(docUri)
                        ?: error("ContentResolver returned null InputStream for $docUri")
                Channels.newChannel(stream) as ReadableByteChannel
            },
        )
    }

    /**
     * Lightweight value carrier for one row of the children cursor.
     */
    private data class DocumentNode(
        val documentId: String,
        val displayName: String,
        val mimeType: String,
        val size: Long,
        val lastModifiedMillis: Long,
        val isDirectory: Boolean,
    )

    public companion object {
        /**
         * Maximum directory nesting depth the walker will descend into.
         * Stops a runaway SAF provider from looping forever; 32 is
         * deeper than any sane on-device layout, and a malicious or
         * buggy provider hitting the cap simply yields a partial result
         * rather than wedging the UI thread.
         */
        public const val MAX_DEPTH: Int = 32

        /**
         * Fallback display name when SAF returns a null/blank
         * `DISPLAY_NAME`. Falls back to the `DOCUMENT_ID` slug as a
         * minimal-but-stable identifier — the receiver still sees a
         * filename, just an opaque one.
         */
        public const val FALLBACK_NAME: String = "document"

        /** Tight projection used by [queryChildren]. */
        private val CHILD_PROJECTION =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )

        /**
         * Map the raw `DISPLAY_NAME` from the cursor into a non-blank
         * filename. Trims path separators a misbehaving provider could
         * inject — the receiver-side materialization (#39) treats the
         * name as a leaf component, never as a path, so any embedded
         * `/` would silently land the file in the wrong place.
         */
        internal fun sanitizeDisplayName(
            raw: String?,
            documentIdFallback: String,
        ): String {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() }
            val withoutSeparators = trimmed?.replace('/', '_')?.replace('\\', '_')
            return withoutSeparators ?: documentIdFallback.substringAfterLast(':').ifEmpty { FALLBACK_NAME }
        }
    }
}
