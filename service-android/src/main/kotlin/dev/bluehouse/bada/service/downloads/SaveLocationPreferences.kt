/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.downloads

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

/**
 * Persistent store for the user's chosen "Save to" tree URI (issue #42).
 *
 * NearDrop's tracker has a recurring "save somewhere other than
 * Downloads" request (grishka/NearDrop#170). On Android the natural
 * primitive is `ACTION_OPEN_DOCUMENT_TREE` paired with
 * [ContentResolver.takePersistableUriPermission], which gives us a
 * tree URI that survives reboots. This class is the single source of
 * truth for that URI:
 *
 *  - **Persistence** uses the same lightweight [SharedPreferences]
 *    pattern as [dev.bluehouse.bada.service.downloads.SaveLocationPreferences]'
 *    sibling settings classes (e.g. battery-optimization preferences in
 *    `:app`). Pulling in `androidx.datastore` for one URI string would
 *    add Kotlin/coroutines weight that isn't justified yet; the API
 *    is narrow enough that a future migration is a one-class swap.
 *  - **Permission lifecycle** is centralised here so callers don't
 *    duplicate the `takePersistableUriPermission` /
 *    `releasePersistableUriPermission` pair. [setSaveTreeUri] grants;
 *    [clear] revokes.
 *  - **Validation** verifies the URI is still in our persisted-grant
 *    list before returning it. If the user revoked the grant via
 *    system Settings or wiped the source provider's data, the URI
 *    becomes useless and we fall back to Downloads instead of letting
 *    the receive path crash mid-transfer.
 *
 * The class is platform-agnostic at its public surface — it holds
 * onto a `Context` only to obtain the application-wide
 * `SharedPreferences` and `ContentResolver`. Tests exercise the
 * persistence + permission flow against an in-memory
 * [SharedPreferences] fake and a recording [UriPermissionGateway],
 * keeping the JVM unit test seam Robolectric-free.
 */
public class SaveLocationPreferences internal constructor(
    private val prefs: SharedPreferences,
    private val permissionGateway: UriPermissionGateway,
) {
    /**
     * The currently-configured save tree URI, or `null` if the user
     * has not chosen one (we should fall back to Downloads).
     *
     * Returns `null` for the "URI was revoked" / "URI is no longer
     * accessible" cases too, so callers always see a usable value
     * without re-checking the permission list themselves.
     */
    @Suppress("ReturnCount")
    public fun getSaveTreeUri(): Uri? {
        val raw = prefs.getString(KEY_SAVE_TREE_URI, null) ?: return null
        // Defer Uri.parse until after the gateway has confirmed the
        // grant: the gateway works in the same `String` representation
        // we persist, so JVM unit tests can drive the
        // "grant revoked between sessions" branch without depending on
        // the not-mocked-on-JVM Uri.parse static.
        if (!permissionGateway.hasPersistedReadWriteGrant(raw)) return null
        return Uri.parse(raw)
    }

    /**
     * Persist [treeUri] as the user's chosen save location and take
     * the persistable read+write grant so the choice survives reboots.
     *
     * If a different URI was previously saved, its grant is released
     * before the new one is taken — Android caps the per-app
     * persisted-grant list (default 128 entries) and a settings
     * screen that toggled rapidly without releasing would eventually
     * exhaust the cap.
     *
     * @throws SecurityException if the platform refuses to take the
     *   grant (e.g. the URI did not come from
     *   `ACTION_OPEN_DOCUMENT_TREE`). Callers should treat this as a
     *   "picker returned a non-tree URI" error and surface a toast.
     */
    public fun setSaveTreeUri(treeUri: Uri) {
        setSaveTreeUriCanonical(treeUri.toString())
    }

    /**
     * Internal variant that takes the URI's canonical string form
     * directly. Lifted out so JVM unit tests can drive the persist
     * + permission lifecycle without depending on
     * [Uri.parse] (which returns `null` under
     * `testOptions.unitTests.isReturnDefaultValues = true`). The
     * production entry point goes through [setSaveTreeUri] which
     * serialises the [Uri] argument the same way the platform would.
     */
    @androidx.annotation.VisibleForTesting
    internal fun setSaveTreeUriCanonical(canonicalUri: String) {
        // Release the previously-saved grant first. We do this before
        // taking the new grant so a failed take leaves the user with
        // no save-tree URI rather than silently keeping the old one
        // behind their back.
        releasePreviousIfDifferent(canonicalUri)
        permissionGateway.takePersistableReadWritePermission(canonicalUri)
        prefs.edit().putString(KEY_SAVE_TREE_URI, canonicalUri).apply()
    }

    /**
     * Clear the saved save location and release the persistable
     * grant. After this call, [getSaveTreeUri] returns `null` and the
     * receiver falls back to writing under `Downloads/`.
     *
     * Idempotent: calling on an already-cleared state is a no-op
     * beyond the (trivially fast) preferences write.
     */
    public fun clear() {
        val raw = prefs.getString(KEY_SAVE_TREE_URI, null)
        if (raw != null) {
            // Best-effort release. A failure here (e.g. the grant
            // was already revoked by the system) is not actionable;
            // we still want to clear the preference so the receiver
            // stops trying to use the dead URI.
            runCatching { permissionGateway.releasePersistableReadWritePermission(raw) }
        }
        prefs.edit().remove(KEY_SAVE_TREE_URI).apply()
    }

    private fun releasePreviousIfDifferent(newCanonical: String) {
        val previousRaw = prefs.getString(KEY_SAVE_TREE_URI, null) ?: return
        if (previousRaw == newCanonical) return
        runCatching { permissionGateway.releasePersistableReadWritePermission(previousRaw) }
    }

    public companion object {
        /**
         * SharedPreferences file name. Distinct from the
         * battery-optimization preferences so a future settings UI
         * can mix-and-match without naming collisions.
         */
        public const val PREFS_NAME: String = "libredrop.save_location"

        /** Key under which the chosen tree URI is stored. */
        internal const val KEY_SAVE_TREE_URI: String = "save_tree_uri"

        /**
         * Production accessor. The underlying [SharedPreferences] is
         * process-singleton-cached by the platform, so repeated calls
         * are cheap.
         */
        @JvmStatic
        public fun from(context: Context): SaveLocationPreferences {
            val app = context.applicationContext
            return SaveLocationPreferences(
                prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                permissionGateway = ContentResolverPermissionGateway(app.contentResolver),
            )
        }
    }
}

/**
 * Narrow seam over the three [ContentResolver] methods
 * [SaveLocationPreferences] needs. The interface trades in
 * canonical-string form of the URI rather than [Uri] itself: that
 * lets JVM unit tests substitute a recording fake without depending
 * on `Uri.parse` (which is unmocked under
 * `testOptions.unitTests.isReturnDefaultValues = true` and would
 * therefore return `null`).
 */
internal interface UriPermissionGateway {
    /** Grant `FLAG_GRANT_READ|WRITE_URI_PERMISSION` persistence for the URI represented by [canonicalUri]. */
    fun takePersistableReadWritePermission(canonicalUri: String)

    /** Drop a previously-taken read+write grant for [canonicalUri]. */
    fun releasePersistableReadWritePermission(canonicalUri: String)

    /** True iff the platform still reports a persisted read+write grant for [canonicalUri]. */
    fun hasPersistedReadWriteGrant(canonicalUri: String): Boolean
}

/**
 * Production [UriPermissionGateway] backed by a real
 * [ContentResolver]. The mode flags are pinned to read+write so the
 * receiver-side environment can both enumerate existing children
 * (read) and create new placeholders (write). The string ↔ Uri
 * conversion happens here, at the I/O boundary, so the rest of the
 * codebase keeps speaking [Uri] while the testable seam stays string-typed.
 */
internal class ContentResolverPermissionGateway(
    private val contentResolver: ContentResolver,
) : UriPermissionGateway {
    override fun takePersistableReadWritePermission(canonicalUri: String) {
        contentResolver.takePersistableUriPermission(Uri.parse(canonicalUri), FLAG_GRANT_READ_WRITE)
    }

    override fun releasePersistableReadWritePermission(canonicalUri: String) {
        contentResolver.releasePersistableUriPermission(Uri.parse(canonicalUri), FLAG_GRANT_READ_WRITE)
    }

    override fun hasPersistedReadWriteGrant(canonicalUri: String): Boolean =
        contentResolver.persistedUriPermissions.any {
            it.uri.toString() == canonicalUri && it.isReadPermission && it.isWritePermission
        }

    private companion object {
        const val FLAG_GRANT_READ_WRITE: Int =
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
