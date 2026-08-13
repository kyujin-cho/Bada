/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.nfc

import dev.bluehouse.bada.protocol.namecard.NameCardBootstrap
import java.security.SecureRandom

/**
 * **Name Card rendezvous-token minter.** The Name Card NFC tap is only a TRIGGER:
 * on a tap the card-side
 * [NameCardHceService] calls [newSession] to mint a fresh random rendezvous token,
 * returns it to the tapping phone over NFC, and passes the same token to the
 * Bluetooth layer ([dev.bluehouse.bada.namecard.NameCardExchangeService]) to advertise.
 * The reader side reads the token from the NFC response and hands it straight to
 * the transfer Activity — so the token flows NFC-response → Intent, not through any
 * shared field here. This object only centralises secure token generation.
 */
internal object NameCardBootstrapHolder {
    private val secureRandom = SecureRandom()

    /** Mint a fresh bootstrap (new random [NameCardBootstrap.TOKEN_LEN]-byte token) for THIS tap. */
    fun newSession(): NameCardBootstrap {
        val token = ByteArray(NameCardBootstrap.TOKEN_LEN).also { secureRandom.nextBytes(it) }
        return NameCardBootstrap(version = NameCardBootstrap.CURRENT_VERSION, token = token)
    }
}
