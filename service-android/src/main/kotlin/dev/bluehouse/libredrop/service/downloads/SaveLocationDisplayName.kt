/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.service.downloads

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Resolves a human-readable label for a SAF tree URI persisted by
 * [SaveLocationPreferences] (issue #42).
 *
 * The settings screen needs a friendly label to display alongside the
 * "Choose folder" button — something the user recognises as their
 * pick. The natural choice is the document's `_display_name` from the
 * source provider. We query that column directly via
 * [android.content.ContentResolver.query] against the URI converted
 * to a document URI; failing that (a stale URI, a provider that no
 * longer responds, a permission revoked between sessions) we fall
 * back to the URI's last path segment so the user still sees something
 * clickable.
 *
 * The lookup is read-only and quick; we do NOT cache the result —
 * the call site in [dev.bluehouse.libredrop.MainActivity] only
 * touches it on `onStart`, which is bounded by the user's interaction
 * cadence.
 */
public object SaveLocationDisplayName {
    /**
     * Look up a display label for [treeUri].
     *
     * @param context Caller context. Only the application
     *   `ContentResolver` is used.
     * @param treeUri A persisted tree URI from
     *   [SaveLocationPreferences.getSaveTreeUri].
     * @return The picked folder's display name, or a best-effort
     *   fallback derived from the URI when the provider cannot be
     *   queried.
     */
    @Suppress("ReturnCount")
    public fun resolve(
        context: Context,
        treeUri: Uri,
    ): String {
        val resolver = context.applicationContext.contentResolver
        val rootDocId =
            runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
                .getOrNull()
                ?: return treeUri.lastPathSegment ?: treeUri.toString()
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)

        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        runCatching {
            resolver.query(docUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        }
        // Fallback: the doc id often encodes the folder name (e.g.
        // `primary:Documents`). Strip the storage root prefix so the
        // user sees the leaf segment.
        val docIdSuffix = rootDocId.substringAfterLast(':').takeIf { it.isNotBlank() }
        return docIdSuffix ?: treeUri.lastPathSegment ?: treeUri.toString()
    }
}
