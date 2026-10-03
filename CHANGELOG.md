# Changelog

## Unreleased — Radio Helper installation from Settings

- Added a Settings card that reports whether the matching Radio Helper companion is installed, installs the helper bundled inside Bada, and opens the helper setup screen after installation.
- Embedded the matching debug or release helper APK in generated app assets and applied Bada's release signing inputs to the release helper so the signature-protected radio service can bind.
- Hardened PackageInstaller confirmation, duplicate taps, session cleanup, unknown-source denial, and background confirmation handling.
## 20260914.01

- Add a master on/off switch at the top of Settings: off stops the receiver service entirely (no mDNS, no BLE advertisement, no listener) and every path that could bring it back honours the choice, while the receive tab greys out the visibility pill and says why. The Name Card entry is hidden until that flow is usable end to end. (#239, #293)
- Keep servicing the LAN channel while a receiver-side Wi-Fi Direct upgrade offer is pending and make the upgrade accept cancellable, so a stock sender that ignores the offer no longer stalls the transfer until the receiver gives up. Bug reports now include the inbound diagnostics log. (#285, #288, #289)
- Advertise real Wi-Fi band support and the current STA frequency to the peer on ConnectionRequest, UPGRADE_PATH_REQUEST and SAFE_TO_CLOSE_PRIOR_CHANNEL, so a stock group owner can form a 5 GHz Wi-Fi Direct group instead of pinning Bada senders to 2.4 GHz. (#287, #292)
- Show the Bluetooth-off warning in the send picker whenever Bluetooth is off, not only while the peer list is empty, with an inline button to turn it on. (#290, #291)
- Darken the night palette one step and pin the page background below the cards so the layering reads correctly in dark mode, stopping short of pure black to avoid OLED smearing. (#280, #294)

## 20260830.01

- Rewrite the Name Card exchange: set up a personal contact card in Settings and exchange it Bada-to-Bada with an NFC tap. (#251)
- Present the incoming-transfer consent as a bottom sheet that opens by itself the moment a transfer arrives, floating over whatever is on screen in its own task; the notification becomes a plain banner (no clipped buttons on OEM shades) whose tap opens the same sheet, and the completed-state photo blur now fills the sheet shape. (#279)
- Add an instant pressed wash to every frosted button so taps read immediately, including the send sheet's Cancel/Done plate where the ripple was invisible over the backdrop blur. (#278)
- Fix the send picker's peer-device names to inherit the DayNight theme text color instead of remaining dark in night mode. (#275)
- Harden the Bluetooth-to-Wi-Fi-Direct bandwidth upgrade: solicit only at upgrade-loop entry while still on Bluetooth, scale the pre-payload offer wait by time since the entry request, and track prior-channel teardown commitment on both roles. (#265, #266, #268)
- Stop MdnsAdvertisementGate from flooding the diagnostics log during peer sessions. (#269)
