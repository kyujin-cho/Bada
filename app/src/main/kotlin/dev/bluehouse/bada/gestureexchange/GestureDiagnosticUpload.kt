/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.gestureexchange

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * Bounded, fire-and-forget Tap to Share diagnostics uploader. Records only
 * semantic states, sizes, and terminal reasons—never keys, contact/file data,
 * SSIDs, passwords, MACs, or endpoint-info bytes. Uploads use a validated
 * Internet network so a newly-created P2P interface cannot steal the request.
 */
internal object GestureDiagnosticUpload {
    private val queue = ArrayDeque<String>()
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var draining = false
    private val sessionHash =
        MessageDigest
            .getInstance("SHA-256")
            .digest(ByteArray(32).also(SecureRandom()::nextBytes))
            .take(8)
            .joinToString("") { "%02x".format(it) }

    fun record(
        context: Context,
        event: String,
    ) {
        val safe = event.replace('\n', '_').replace('\r', '_').take(MAX_LINE)
        synchronized(queue) {
            if (queue.size == MAX_QUEUE) queue.removeFirst()
            queue.addLast("${System.currentTimeMillis()} session=$sessionHash $safe")
            if (draining) return
            draining = true
        }
        executor.execute { drain(context.applicationContext) }
    }

    private fun drain(context: Context) {
        while (true) {
            val batch =
                synchronized(queue) {
                    val persisted = readFallback(context)
                    if (queue.isEmpty() && persisted.isEmpty()) {
                        draining = false
                        return
                    }
                    buildList {
                        addAll(persisted)
                        repeat(minOf(BATCH_SIZE, queue.size)) { add(queue.removeFirst()) }
                    }
                }
            if (!runCatching { post(context, batch.joinToString("\n")) }.getOrDefault(false)) {
                persistFallback(context, batch)
                executor.schedule({ drain(context) }, RETRY_DELAY_SECONDS, TimeUnit.SECONDS)
                return
            }
            clearFallback(context)
        }
    }

    private fun persistFallback(
        context: Context,
        lines: List<String>,
    ) {
        runCatching {
            val file = fallbackFile(context)
            file.parentFile?.mkdirs()
            file.writeText(lines.joinToString("\n").takeLast(MAX_FALLBACK_BYTES))
        }
    }

    private fun readFallback(context: Context): List<String> =
        runCatching {
            fallbackFile(context)
                .takeIf(File::isFile)
                ?.readLines()
                .orEmpty()
        }.getOrDefault(emptyList())

    private fun clearFallback(context: Context) {
        runCatching { fallbackFile(context).delete() }
    }

    private fun fallbackFile(context: Context): File = File(context.filesDir, "diagnostics/gesture-tap.log")

    private fun post(
        context: Context,
        body: String,
    ): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network =
            manager.allNetworks.firstOrNull { candidate ->
                manager.getNetworkCapabilities(candidate)?.let { capabilities ->
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                } == true
            } ?: return false
        val connection = network.openConnection(URL(ENDPOINT)) as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            connection.setRequestProperty("X-Device", Build.MODEL.take(MAX_DEVICE))
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            return connection.responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    }

    private const val ENDPOINT = "https://204-168-163-118.sslip.io/imelog/superdrop-tap-to-share"
    private const val MAX_QUEUE = 256
    private const val BATCH_SIZE = 32
    private const val MAX_LINE = 800
    private const val MAX_DEVICE = 80
    private const val CONNECT_TIMEOUT_MS = 3000
    private const val READ_TIMEOUT_MS = 3000
    private const val RETRY_DELAY_SECONDS = 30L
    private const val MAX_FALLBACK_BYTES = 256 * 1024
}
