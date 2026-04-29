# Medium throughput reference

This document is the single summary for the transport-throughput numbers
used by the Phase 4 medium ladder. The figures below are not CI
benchmarks; they come from the medium-specific manual runbooks and are
intended to guide fallback ordering and manual validation on real
hardware.

| Medium | Throughput expectation | Source |
| --- | --- | --- |
| Wi-Fi LAN | Environment-dependent baseline. Measure this first on the same AP / channel and use it as the control for the upgraded mediums. | Phase 1 LAN transfer is the control path used by the runbooks in `docs/testing/`. |
| Wi-Fi Hotspot | Same Wi-Fi class as LAN, but no hard project-wide target is pinned yet because OEM hotspot implementations vary more than router-backed LAN. Measure it against the same-device Wi-Fi LAN baseline during the manual suite. | [`docs/testing/interop-wifi-hotspot.md`](testing/interop-wifi-hotspot.md) |
| Wi-Fi Direct | At least **150 Mbps** sustained for the 1 GiB transfer check. | [`docs/testing/medium-wifi-direct.md`](testing/medium-wifi-direct.md) |
| Wi-Fi Aware | Typically **100-250 Mbps** on a clean radio path; expected to be slower than pure Wi-Fi LAN but still far above Bluetooth-based fallbacks. | [`docs/testing/interop-wifi-aware.md`](testing/interop-wifi-aware.md) |
| BLE L2CAP | **2-3 Mbps** on BT 5.x hardware. Older BT 4.2 chipsets may fall to about **700 kbps**. | [`docs/testing/medium-ble-l2cap.md`](testing/medium-ble-l2cap.md) |
| Bluetooth RFCOMM | About **1 Mbps** on Bluetooth Classic 4.x and about **2 Mbps** on 5.x. Sustained rates below **500 kbps** are a red flag. | [`docs/testing/interop-bluetooth-rfcomm.md`](testing/interop-bluetooth-rfcomm.md) |

## How to use this table

1. Run the Wi-Fi LAN control transfer first on the exact same device pair.
2. Run the upgraded medium transfer with the same payload size.
3. Record elapsed time, compute Mbps, and append the measured pair to the
   relevant runbook when it materially differs from the expectation above.

`docs/testing/medium-integration-suite.md` is the operator runbook for the
whole suite.
