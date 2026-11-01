# Changelog

## 20261102.01

- **Improved messages when a send stops** - Now you can see why a send ended early: you cancelled it, the other person closed or stopped it, or the other phone is set to receive files only from contacts or from its own devices. In that last case, the message shows which Quick Share setting to change. (#319, #320)
- **The bada logo in the top bar** - Now you can see the bada logo at the top of the home screen. It is black in light mode and white in dark mode. (#323)
- **Sending to a Galaxy phone on another network** - Fixed transfers to a Galaxy phone that is not on your Wi-Fi. They reached 100% and then failed. Also fixed the send screen staying at 100% after a few transfers that start over Bluetooth Low Energy. (#314, #316, #322)
- **Duplicate entries for your phone** - Fixed your phone appearing again on Windows and Galaxy share screens after each transfer. On Windows, the list kept getting longer while it was open. (#197, #306, #315, #321)
- **File dates on Windows** - Fixed the modified dates of files sent to a Windows PC. Windows showed January 1, 1980 for every received file. (#310, #318)
- **Buttons with two lines of text** - Fixed the second line of a two-line button label being cut off. (#307, #317)
- **Large receive logs** - Fixed the diagnostic log growing by about 12 MB during one 2.7 GB transfer. (#304, #308)

## 20261007.01

- Send links and text shared from another app (YouTube, a browser, a chat app) over Quick Share instead of stopping on the "unsupported" screen: a whole http(s) link goes out as a URL the receiver can open, anything else as plain text, and the send sheet shows the host or a one-line preview. (#301, #302)
- Show a device-type icon for each peer in the send picker (laptop, tablet, or phone) instead of the same phone glyph for every device, and keep a real device type from being overwritten by a later BLE observation that carries none. (#277, #297)
- Fix the receive progress bar stalling near empty while the percent label counted up on fast Wi-Fi LAN transfers; the bar no longer restarts its animation on every payload chunk. (#298, #303)

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
