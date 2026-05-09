/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.receiver

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent store for the receiver's user-configured advertised device name.
 *
 * The surface is intentionally tiny: a single optional string backed by
 * [SharedPreferences]. The rest of the stack treats `null` as "use the
 * platform-derived default name" and never has to know how the value is stored.
 */
public class AdvertisedDeviceNamePreferences internal constructor(
    private val prefs: SharedPreferences,
) {
    /** Returns the stored custom name, or `null` when the user wants the default resolution path. */
    public fun getCustomName(): String? =
        AdvertisedDeviceNameSanitizer.sanitize(
            prefs.getString(KEY_CUSTOM_NAME, null),
        )

    /**
     * Persist [rawName] as the custom advertised name.
     *
     * Blank / invalid input is treated as unset and clears the preference.
     * Returns the canonical stored value after trimming + UTF-8-safe truncation,
     * or `null` when the preference was cleared.
     */
    public fun setCustomName(rawName: String?): String? {
        val sanitized = AdvertisedDeviceNameSanitizer.sanitize(rawName)
        prefs
            .edit()
            .apply {
                if (sanitized == null) {
                    remove(KEY_CUSTOM_NAME)
                } else {
                    putString(KEY_CUSTOM_NAME, sanitized)
                }
            }.apply()
        return sanitized
    }

    /** Clear the custom-name override so default resolution is used again. */
    public fun clearCustomName() {
        prefs.edit().remove(KEY_CUSTOM_NAME).apply()
    }

    public companion object {
        /** Dedicated preferences file for the advertised-name setting. */
        public const val PREFS_NAME: String = "bada.advertised_device_name"

        internal const val KEY_CUSTOM_NAME: String = "custom_name"

        @JvmStatic
        public fun from(context: Context): AdvertisedDeviceNamePreferences =
            AdvertisedDeviceNamePreferences(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
    }
}
