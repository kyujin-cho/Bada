/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Empty launcher activity for the WhenVivoMeetsGoogle app.
 *
 * Phase 1's job here is purely scaffold — the real device list, transfer
 * status, and settings screens replace this implementation as the rest of
 * the phase rolls in.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
