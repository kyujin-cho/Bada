/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.receiver.consent

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-contract coverage for the background notification route from
 * issue #263.
 *
 * [ConsentNotification] deliberately lives in `:service-android`, while
 * the concrete trampoline activity lives in `:app` and is supplied as a
 * runtime class. A plain JVM test cannot instantiate that cross-module
 * Android `PendingIntent` without changing the module dependency graph,
 * so this test pins the small, security-sensitive routing contract at its
 * owner: an explicit immutable activity intent carries the connection id,
 * the notification body opens it, and the same intent is the full-screen
 * fallback. The app module separately pins the trampoline's manifest task
 * configuration.
 */
class ConsentNotificationRoutingSourceTest {
    private val source: String by lazy {
        val file =
            File(
                "src/main/kotlin/dev/bluehouse/bada/service/receiver/consent/ConsentNotification.kt",
            )
        assertThat(file.exists()).isTrue()
        file.readText()
    }

    @Test
    fun `notification body opens the consent trampoline for its connection`() {
        assertThat(source).contains("PendingIntent.getActivity(")
        assertThat(source).contains("action = ConsentIntents.ACTION_SHOW_CONSENT")
        assertThat(source).contains("putExtra(ConsentIntents.EXTRA_CONNECTION_ID, connectionId)")
        assertThat(source).contains("Intent.FLAG_ACTIVITY_NEW_TASK")
        assertThat(source).contains("PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE")
        assertThat(source).contains("builder.setContentIntent(tapIntent)")
    }

    @Test
    fun `full screen fallback opens the same consent trampoline`() {
        assertThat(source).contains("builder.setFullScreenIntent(tapIntent, true)")
    }
}
