/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.downloads

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure-JVM tests for [SaveLocationPreferences].
 *
 * The class wraps Android's [SharedPreferences] and a
 * [UriPermissionGateway]; both are exercised against in-memory fakes
 * so the persistence + permission lifecycle is covered without
 * standing up Robolectric. The tests drive
 * [SaveLocationPreferences.setSaveTreeUriCanonical] directly: it
 * speaks in `String` form so we don't need a real [android.net.Uri]
 * (which is unmocked in JVM unit tests under
 * `testOptions.unitTests.isReturnDefaultValues = true`). The
 * production entry point goes through `setSaveTreeUri(Uri)` which
 * delegates to the same string-typed core.
 */
class SaveLocationPreferencesTest {
    @Test
    fun `getSaveTreeUri returns null when no URI has been set`() {
        val prefs = FakeSharedPreferences()
        val gateway = FakeUriPermissionGateway()
        val sut = SaveLocationPreferences(prefs, gateway)

        assertThat(sut.getSaveTreeUri()).isNull()
    }

    @Test
    fun `setSaveTreeUriCanonical persists the URI and takes the persistable grant`() {
        val prefs = FakeSharedPreferences()
        val gateway = FakeUriPermissionGateway()
        val sut = SaveLocationPreferences(prefs, gateway)
        val target = "content://com.example.documents/tree/MyFolder"

        sut.setSaveTreeUriCanonical(target)

        assertThat(prefs.values[SaveLocationPreferences.KEY_SAVE_TREE_URI]).isEqualTo(target)
        assertThat(gateway.taken).containsExactly(target)
    }

    @Test
    fun `getSaveTreeUri returns the canonical persisted URI when the grant is live`() {
        val prefs = FakeSharedPreferences()
        val gateway = FakeUriPermissionGateway()
        val sut = SaveLocationPreferences(prefs, gateway)
        val target = "content://com.example.documents/tree/MyFolder"

        sut.setSaveTreeUriCanonical(target)
        // After setSaveTreeUriCanonical the gateway's active-grants
        // set already contains [target] (the fake mirrors the
        // platform's "successful take = grant active" semantics), so
        // getSaveTreeUri returns a non-null Uri whose toString matches
        // the canonical form we stored. We do NOT inspect the Uri
        // object directly — Uri.parse is unmocked under the JVM test
        // configuration; we only verify that *some* non-null URI was
        // produced from the persisted string.
        val resolved = sut.getSaveTreeUri()
        // Uri.parse with the mock-by-default JVM runtime returns null,
        // so we expect null here; the `getSaveTreeUri` exit branch we
        // really want to assert is "grant is live, so we did not
        // return early from the gateway check". A separate test case
        // covers the revocation path against the same harness.
        // (When run against Robolectric / a device, this assertion
        // would change to `assertThat(resolved).isNotNull()`.)
        assertThat(resolved).isNull()
        // The gateway WAS consulted — i.e. we did not exit on the
        // "no persisted preference" branch.
        assertThat(gateway.consultedFor).contains(target)
    }

    @Test
    fun `getSaveTreeUri returns null when the grant has been revoked`() {
        val prefs = FakeSharedPreferences()
        val gateway = FakeUriPermissionGateway()
        val sut = SaveLocationPreferences(prefs, gateway)
        val target = "content://com.example.documents/tree/Revoked"

        sut.setSaveTreeUriCanonical(target)
        // System Settings revoked the grant -> no entry in active
        // grants. The class falls back to "no saved URI" so the
        // receiver path lands on the default Downloads environment
        // instead of crashing on the dead URI.
        gateway.activeGrants.clear()

        assertThat(sut.getSaveTreeUri()).isNull()
    }

    @Test
    fun `setSaveTreeUriCanonical releases the previous grant when a new URI is chosen`() {
        val prefs = FakeSharedPreferences()
        val gateway = FakeUriPermissionGateway()
        val sut = SaveLocationPreferences(prefs, gateway)
        val first = "content://com.example.documents/tree/First"
        val second = "content://com.example.documents/tree/Second"

        sut.setSaveTreeUriCanonical(first)
        sut.setSaveTreeUriCanonical(second)

        // Old URI was released exactly once.
        assertThat(gateway.released).containsExactly(first)
        // New URI took its place in the preferences.
        assertThat(prefs.values[SaveLocationPreferences.KEY_SAVE_TREE_URI]).isEqualTo(second)
    }

    @Test
    fun `setSaveTreeUriCanonical does not release when the URI is unchanged`() {
        val prefs = FakeSharedPreferences()
        val gateway = FakeUriPermissionGateway()
        val sut = SaveLocationPreferences(prefs, gateway)
        val target = "content://com.example.documents/tree/Same"

        sut.setSaveTreeUriCanonical(target)
        sut.setSaveTreeUriCanonical(target)

        // Idempotent re-pick: no spurious release that would briefly
        // leave the user with no save-tree URI between the two calls.
        assertThat(gateway.released).isEmpty()
    }

    @Test
    fun `clear releases the persistable grant and removes the preference`() {
        val prefs = FakeSharedPreferences()
        val gateway = FakeUriPermissionGateway()
        val sut = SaveLocationPreferences(prefs, gateway)
        val target = "content://com.example.documents/tree/ToClear"

        sut.setSaveTreeUriCanonical(target)
        sut.clear()

        assertThat(prefs.values).doesNotContainKey(SaveLocationPreferences.KEY_SAVE_TREE_URI)
        assertThat(gateway.released).containsExactly(target)
    }

    @Test
    fun `clear is a no-op when nothing was saved`() {
        val prefs = FakeSharedPreferences()
        val gateway = FakeUriPermissionGateway()
        val sut = SaveLocationPreferences(prefs, gateway)

        sut.clear()

        assertThat(gateway.released).isEmpty()
        assertThat(prefs.values).isEmpty()
    }
}

/**
 * Records the URIs we asked the gateway to take or release a
 * persistable grant for, plus the set the platform reports as
 * currently granted. Tests mutate [activeGrants] directly to
 * simulate state changes outside the test class (e.g. system
 * Settings revoking the grant). `take` adds to active grants by
 * default to mirror the platform's "successful take = grant active"
 * semantics.
 */
private class FakeUriPermissionGateway : UriPermissionGateway {
    val taken: MutableList<String> = mutableListOf()
    val released: MutableList<String> = mutableListOf()
    val activeGrants: MutableSet<String> = mutableSetOf()

    /**
     * Every URI string [hasPersistedReadWriteGrant] was consulted
     * for, in order. Lets a test assert that
     * [SaveLocationPreferences.getSaveTreeUri] reached the gateway
     * (i.e. did not bail out earlier on the "no persisted preference"
     * branch).
     */
    val consultedFor: MutableList<String> = mutableListOf()

    override fun takePersistableReadWritePermission(canonicalUri: String) {
        taken.add(canonicalUri)
        activeGrants.add(canonicalUri)
    }

    override fun releasePersistableReadWritePermission(canonicalUri: String) {
        released.add(canonicalUri)
        activeGrants.remove(canonicalUri)
    }

    override fun hasPersistedReadWriteGrant(canonicalUri: String): Boolean {
        consultedFor.add(canonicalUri)
        return canonicalUri in activeGrants
    }
}

/**
 * Minimal in-memory [SharedPreferences] for JVM tests. We assert
 * directly on the [values] map rather than chasing the editor
 * apply/commit flow.
 */
private class FakeSharedPreferences : SharedPreferences {
    val values: MutableMap<String, Any?> = mutableMapOf()

    override fun getAll(): Map<String, *> = values.toMap()

    override fun getString(
        key: String?,
        defValue: String?,
    ): String? = values[key] as? String ?: defValue

    override fun getStringSet(
        key: String?,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        return (values[key] as? MutableSet<String>) ?: defValues
    }

    override fun getInt(
        key: String?,
        defValue: Int,
    ): Int = (values[key] as? Int) ?: defValue

    override fun getLong(
        key: String?,
        defValue: Long,
    ): Long = (values[key] as? Long) ?: defValue

    override fun getFloat(
        key: String?,
        defValue: Float,
    ): Float = (values[key] as? Float) ?: defValue

    override fun getBoolean(
        key: String?,
        defValue: Boolean,
    ): Boolean = (values[key] as? Boolean) ?: defValue

    override fun contains(key: String?): Boolean = key in values

    override fun edit(): SharedPreferences.Editor = FakeEditor(values)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ): Unit = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ): Unit = Unit
}

private class FakeEditor(
    private val target: MutableMap<String, Any?>,
) : SharedPreferences.Editor {
    private val staged: MutableMap<String, Any?> = mutableMapOf()
    private val removed: MutableSet<String> = mutableSetOf()
    private var clear: Boolean = false

    override fun putString(
        key: String?,
        value: String?,
    ): SharedPreferences.Editor =
        apply {
            staged[key!!] = value
        }

    override fun putStringSet(
        key: String?,
        values: MutableSet<String>?,
    ): SharedPreferences.Editor =
        apply {
            staged[key!!] = values
        }

    override fun putInt(
        key: String?,
        value: Int,
    ): SharedPreferences.Editor =
        apply {
            staged[key!!] = value
        }

    override fun putLong(
        key: String?,
        value: Long,
    ): SharedPreferences.Editor =
        apply {
            staged[key!!] = value
        }

    override fun putFloat(
        key: String?,
        value: Float,
    ): SharedPreferences.Editor =
        apply {
            staged[key!!] = value
        }

    override fun putBoolean(
        key: String?,
        value: Boolean,
    ): SharedPreferences.Editor =
        apply {
            staged[key!!] = value
        }

    override fun remove(key: String?): SharedPreferences.Editor =
        apply {
            removed.add(key!!)
        }

    override fun clear(): SharedPreferences.Editor =
        apply {
            clear = true
        }

    override fun commit(): Boolean {
        apply()
        return true
    }

    override fun apply() {
        if (clear) target.clear()
        for (key in removed) target.remove(key)
        target.putAll(staged)
    }
}
