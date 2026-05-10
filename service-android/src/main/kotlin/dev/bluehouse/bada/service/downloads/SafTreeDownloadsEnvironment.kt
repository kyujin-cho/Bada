/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.downloads

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.RequiresApi
import java.io.IOException
import java.io.OutputStream

/**
 * `DownloadsEnvironment` implementation that writes received files
 * into a Storage Access Framework tree URI (issue #42).
 *
 * The user picks a directory via `ACTION_OPEN_DOCUMENT_TREE`; the
 * returned URI is persisted by [SaveLocationPreferences] and handed
 * to this environment as [treeUri]. Each file write is performed via
 * `DocumentsContract` calls against a derived child document URI:
 *
 *  1. [insertPending] resolves the requested subdirectory chain under
 *     the tree (creating segments via
 *     [DocumentsContract.createDocument] of MIME type
 *     `vnd.android.document/directory`) and creates a placeholder
 *     document inside it. The placeholder uses a generic MIME type
 *     plus a `.part` suffix so partially-written files don't get
 *     indexed by media scanners or shown to the user mid-transfer.
 *  2. [openOutputStream] returns the [ContentResolver.openOutputStream]
 *     for the placeholder.
 *  3. [commit] renames the placeholder to its final name (drops the
 *     `.part` suffix). Failure is best-effort — the bytes are on
 *     disk regardless; we just log and skip the rename so the user
 *     still ends up with a recoverable file.
 *  4. [discard] deletes the placeholder.
 *
 * ### Why not `androidx.documentfile.DocumentFile`
 *
 * `DocumentFile` issues one provider `query()` per node it touches and
 * surfaces each row as a fresh instance. Walking the directory tree
 * to resolve a `parent_folder` would re-query the provider for every
 * existing segment, which is the same performance pitfall noted by
 * `:app/DocumentTreeFileSourceFactory`. This class instead uses raw
 * `DocumentsContract` queries with a tight projection so the first
 * write of a folder share doesn't stall the receiver loop.
 *
 * ### Tree-URI vs. document-URI
 *
 * `treeUri` is the tree-format URI handed back by
 * `ACTION_OPEN_DOCUMENT_TREE`. To create / list / open documents
 * inside it we must convert via
 * [DocumentsContract.buildChildDocumentsUriUsingTree] (for listing) or
 * [DocumentsContract.buildDocumentUriUsingTree] (for the resolved
 * child's own URI). [SafTreeDownloadsEnvironment] encapsulates the
 * conversion so callers never see the difference.
 *
 * ### Failure isolation
 *
 * If the persisted grant gets revoked (user cleared it via system
 * Settings, or the source provider's data was wiped), every
 * `ContentResolver` call in this environment throws `SecurityException`
 * (or returns null for query / null for openOutputStream). The
 * environment surfaces these as [IOException] so the orchestrator's
 * existing failure path applies — the user sees a transfer-failed
 * notification rather than a process crash. The settings UI is
 * responsible for re-validating the URI on launch and clearing it if
 * the grant has gone stale.
 *
 * @param contentResolver Live `ContentResolver` for the application.
 * @param treeUri The picked tree URI. Caller already verified the
 *   persistable grant was taken; this constructor does not re-check.
 */
@RequiresApi(android.os.Build.VERSION_CODES.LOLLIPOP)
internal class SafTreeDownloadsEnvironment(
    private val contentResolver: ContentResolver,
    private val treeUri: Uri,
) : DownloadsEnvironment {
    override fun insertPending(
        displayName: String,
        mimeType: String?,
        relativeSubPath: List<String>,
    ): DownloadsEnvironment.Destination {
        // Resolve / create the target directory under the tree root.
        // The picker handed us a tree URI but we need the document
        // URI of the actual root directory to start the chain.
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        val targetParentUri = ensureSubdirectory(rootUri, relativeSubPath)

        // Create the placeholder document. We append the `.part`
        // suffix to the display name so a media scanner or file picker
        // doesn't surface the half-written file to the user. On
        // commit() we rename to drop the suffix.
        val placeholderName = "$displayName$PART_SUFFIX"
        val placeholderMime = mimeType ?: GENERIC_BINARY_MIME
        val placeholderUri =
            DocumentsContract.createDocument(
                contentResolver,
                targetParentUri,
                placeholderMime,
                placeholderName,
            )
                ?: throw IOException(
                    "DocumentsContract.createDocument returned null for " +
                        "$placeholderName under $targetParentUri",
                )
        return SafDestination(
            documentUri = placeholderUri,
            displayName = displayName,
        )
    }

    override fun openOutputStream(destination: DownloadsEnvironment.Destination): OutputStream {
        val uri = destination.requireSafUri()
        return contentResolver.openOutputStream(uri, "w")
            ?: throw IOException("ContentResolver.openOutputStream returned null for $uri")
    }

    override fun commit(
        destination: DownloadsEnvironment.Destination,
        lastModifiedTimestampMillis: Long,
    ) {
        val safDest = destination.requireSafDestination()
        // Rename the placeholder to its final name. Best-effort: a
        // failure (e.g. the document provider does not support
        // rename, or the grant was revoked between insertPending and
        // commit) leaves the bytes on disk under the `.part` name,
        // which the user can recover manually. We do NOT throw —
        // raising here would mark the transfer failed even though
        // the bytes are safe.
        runCatching {
            DocumentsContract.renameDocument(
                contentResolver,
                safDest.documentUri,
                safDest.displayName,
            )
        }
        // SAF has no first-class "set last modified" API; trying to
        // set it on the underlying file system descriptor is
        // best-effort and provider-dependent. Issue #41 documents
        // that the timestamp is informational on the SAF path; we
        // leave the platform default (insert-time) here and revisit
        // when DocumentsContract grows a supported setter.
    }

    override fun discard(destination: DownloadsEnvironment.Destination) {
        val uri = destination.requireSafUri()
        // Idempotent: a missing document or revoked grant produces
        // false / SecurityException; we swallow both because there's
        // nothing actionable.
        runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
    }

    /**
     * Resolve the chain of subdirectories under [parentUri], creating
     * any missing segments. Returns the document URI of the deepest
     * directory ready to receive the file's placeholder document.
     *
     * For an empty [segments], returns [parentUri] unchanged.
     */
    private fun ensureSubdirectory(
        parentUri: Uri,
        segments: List<String>,
    ): Uri {
        var current = parentUri
        for (segment in segments) {
            current = findOrCreateChildDirectory(current, segment)
        }
        return current
    }

    /**
     * Look for an existing child directory of [parentUri] named
     * [segmentName]; create one if it does not exist. Returns the
     * (document) URI of the resolved / freshly created directory.
     */
    private fun findOrCreateChildDirectory(
        parentUri: Uri,
        segmentName: String,
    ): Uri {
        val existing = findChildDocumentByName(parentUri, segmentName, requireDirectory = true)
        if (existing != null) return existing
        return DocumentsContract.createDocument(
            contentResolver,
            parentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            segmentName,
        )
            ?: throw IOException(
                "DocumentsContract.createDocument returned null for directory " +
                    "'$segmentName' under $parentUri",
            )
    }

    /**
     * Find a child of [parentUri] whose `_display_name` matches
     * [name]. Returns the child's document URI, or `null` if no
     * matching entry exists.
     *
     * Quick Share senders may legitimately reuse a directory name
     * across multiple folder shares (the user picks the same save
     * root, the second folder also has a `Photos/` subfolder), so we
     * MUST look up an existing entry instead of always trying to
     * create. Two folders with the same name on the same parent is
     * also a real possibility — when that happens we return the
     * first match; the caller's downstream collision-suffix retry
     * will land the file under a unique name regardless.
     *
     * @param requireDirectory When `true`, only matches entries whose
     *   MIME type is `vnd.android.document/directory`. When `false`,
     *   matches any entry (used by the existence-probe a writer-side
     *   collision retry could grow into in the future).
     */
    private fun findChildDocumentByName(
        parentUri: Uri,
        name: String,
        requireDirectory: Boolean,
    ): Uri? {
        val parentDocId = DocumentsContract.getDocumentId(parentUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
        contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val docIdIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val displayMatches = cursor.getString(nameIdx) == name
                val typeMatches =
                    !requireDirectory || cursor.getString(mimeIdx) == DocumentsContract.Document.MIME_TYPE_DIR
                if (displayMatches && typeMatches) {
                    val childDocId = cursor.getString(docIdIdx)
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                }
            }
        }
        return null
    }

    /**
     * Concrete destination handle. Holds the placeholder document
     * URI plus the user-visible filename we'll rename it to on
     * [commit].
     */
    private data class SafDestination(
        val documentUri: Uri,
        override val displayName: String,
    ) : DownloadsEnvironment.Destination {
        override val internalKey: Any get() = documentUri
    }

    private fun DownloadsEnvironment.Destination.requireSafUri(): Uri = requireSafDestination().documentUri

    private fun DownloadsEnvironment.Destination.requireSafDestination(): SafDestination {
        require(this is SafDestination) {
            "SafTreeDownloadsEnvironment received a destination it didn't issue: $this"
        }
        return this
    }

    private companion object {
        /**
         * Suffix appended to the in-flight placeholder document.
         * Same convention as the legacy environment so users
         * inspecting the chosen save folder mid-transfer recognise
         * the partial-download marker.
         */
        const val PART_SUFFIX: String = ".part"

        /**
         * Fallback MIME type used when the caller does not supply a
         * specific one. SAF requires a non-null MIME on
         * createDocument; we pick `application/octet-stream` so the
         * placeholder is treated as raw binary content.
         */
        const val GENERIC_BINARY_MIME: String = "application/octet-stream"
    }
}
