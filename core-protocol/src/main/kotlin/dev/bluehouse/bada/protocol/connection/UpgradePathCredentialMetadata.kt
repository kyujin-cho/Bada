/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.connection

import dev.bluehouse.bada.protocol.medium.UpgradePathCredentials

internal fun UpgradePathCredentials.wifiDirectFrequencyMhzOrNull(): Int? =
    (this as? UpgradePathCredentials.WifiDirect)
        ?.frequency
        ?.takeIf { it != UpgradePathCredentials.WifiDirect.FREQUENCY_NOT_SET && it > 0 }
