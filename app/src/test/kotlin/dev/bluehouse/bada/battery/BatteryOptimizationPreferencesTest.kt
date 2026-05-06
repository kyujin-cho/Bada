/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.battery

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the persistence semantics of [BatteryOptimizationPreferences]
 * without pulling in Robolectric — a fake [SharedPreferences] backed by
 * a HashMap is enough to exercise the `getBoolean` / `putBoolean` / apply
 * sequence the production code uses.
 */
class BatteryOptimizationPreferencesTest {
    @Test
    fun `dismissed flag defaults to false`() {
        val prefs = BatteryOptimizationPreferences(FakeSharedPreferences())
        assertFalse(prefs.hasBeenDismissed())
    }

    @Test
    fun `markDismissed flips the flag and persists across reads`() {
        val backing = FakeSharedPreferences()
        val prefs = BatteryOptimizationPreferences(backing)

        prefs.markDismissed()

        assertTrue(prefs.hasBeenDismissed())
        // A separate wrapper around the same backing store must observe
        // the same flag — confirms we are reading from the SharedPreferences,
        // not an in-instance cache.
        assertTrue(BatteryOptimizationPreferences(backing).hasBeenDismissed())
    }

    @Test
    fun `markDismissed is idempotent`() {
        val prefs = BatteryOptimizationPreferences(FakeSharedPreferences())
        prefs.markDismissed()
        prefs.markDismissed()
        assertTrue(prefs.hasBeenDismissed())
    }
}

/**
 * In-memory [SharedPreferences] stand-in. We only implement the methods
 * [BatteryOptimizationPreferences] reaches for; the rest throw so the
 * test fails loudly if the production code ever grows a dependency on
 * a different SharedPreferences feature without updating the fake.
 */
private class FakeSharedPreferences : SharedPreferences {
    private val store: MutableMap<String, Any?> = mutableMapOf()

    override fun getAll(): Map<String, *> = store.toMap()

    override fun getString(
        key: String?,
        defValue: String?,
    ): String? = store[key] as? String ?: defValue

    override fun getStringSet(
        key: String?,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? = throw UnsupportedOperationException()

    override fun getInt(
        key: String?,
        defValue: Int,
    ): Int = (store[key] as? Int) ?: defValue

    override fun getLong(
        key: String?,
        defValue: Long,
    ): Long = (store[key] as? Long) ?: defValue

    override fun getFloat(
        key: String?,
        defValue: Float,
    ): Float = (store[key] as? Float) ?: defValue

    override fun getBoolean(
        key: String?,
        defValue: Boolean,
    ): Boolean = (store[key] as? Boolean) ?: defValue

    override fun contains(key: String?): Boolean = store.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor(store)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        // No-op — production code does not register listeners.
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        // No-op.
    }
}

private class FakeEditor(
    private val store: MutableMap<String, Any?>,
) : SharedPreferences.Editor {
    private val pending: MutableMap<String, Any?> = mutableMapOf()
    private var clear = false

    override fun putString(
        key: String?,
        value: String?,
    ): SharedPreferences.Editor =
        apply {
            pending[requireNotNull(key)] = value
        }

    override fun putStringSet(
        key: String?,
        values: MutableSet<String>?,
    ): SharedPreferences.Editor = throw UnsupportedOperationException()

    override fun putInt(
        key: String?,
        value: Int,
    ): SharedPreferences.Editor =
        apply {
            pending[requireNotNull(key)] = value
        }

    override fun putLong(
        key: String?,
        value: Long,
    ): SharedPreferences.Editor =
        apply {
            pending[requireNotNull(key)] = value
        }

    override fun putFloat(
        key: String?,
        value: Float,
    ): SharedPreferences.Editor =
        apply {
            pending[requireNotNull(key)] = value
        }

    override fun putBoolean(
        key: String?,
        value: Boolean,
    ): SharedPreferences.Editor =
        apply {
            pending[requireNotNull(key)] = value
        }

    override fun remove(key: String?): SharedPreferences.Editor =
        apply {
            pending[requireNotNull(key)] = REMOVED
        }

    override fun clear(): SharedPreferences.Editor =
        apply {
            clear = true
        }

    override fun commit(): Boolean {
        applyToStore()
        return true
    }

    override fun apply() {
        applyToStore()
    }

    private fun applyToStore() {
        if (clear) store.clear()
        for ((key, value) in pending) {
            if (value === REMOVED) {
                store.remove(key)
            } else {
                store[key] = value
            }
        }
        pending.clear()
        clear = false
    }

    private companion object {
        val REMOVED = Any()
    }
}
