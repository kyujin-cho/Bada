/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.receiver.consent

/**
 * Shared colour constants for the incoming-transfer consent notification.
 *
 * The right-side "gallery" glyph is no longer a runtime-drawn bitmap — it
 * is a theme-tinted vector ([R.drawable.ic_consent_gallery] +
 * `?attr/textColorSecondary`) set directly in
 * [R.layout.notification_consent] so it follows the notification card's
 * light / dark background. Only the small-icon tint, applied by
 * [ConsentNotification.build], remains here.
 */
public object ConsentThumbnail {
    /** Brand-blue tint for the notification (colours the left small-icon circle). */
    public const val LEFT_ICON_TINT: Int = 0xFF0A84FF.toInt()
}
