/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.service.receiver

import android.content.SharedPreferences

internal class FakeSharedPreferences : SharedPreferences {
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
    ) {
        // No-op for unit tests.
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        // No-op for unit tests.
    }
}

private class FakeEditor(
    private val values: MutableMap<String, Any?>,
) : SharedPreferences.Editor {
    override fun putString(
        key: String?,
        value: String?,
    ): SharedPreferences.Editor =
        apply {
            values[key.orEmpty()] = value
        }

    override fun putStringSet(
        key: String?,
        values: MutableSet<String>?,
    ): SharedPreferences.Editor = throw UnsupportedOperationException()

    override fun putInt(
        key: String?,
        value: Int,
    ): SharedPreferences.Editor = throw UnsupportedOperationException()

    override fun putLong(
        key: String?,
        value: Long,
    ): SharedPreferences.Editor = throw UnsupportedOperationException()

    override fun putFloat(
        key: String?,
        value: Float,
    ): SharedPreferences.Editor = throw UnsupportedOperationException()

    override fun putBoolean(
        key: String?,
        value: Boolean,
    ): SharedPreferences.Editor = throw UnsupportedOperationException()

    override fun remove(key: String?): SharedPreferences.Editor =
        apply {
            values.remove(key)
        }

    override fun clear(): SharedPreferences.Editor =
        apply {
            values.clear()
        }

    override fun commit(): Boolean = true

    override fun apply() {
        // Changes are applied eagerly above.
    }
}
