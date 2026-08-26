# ADR G0 — defaults that unlock coding

> **Status:** accepted 2026-08-26.  
> **Branch:** `kmp-aimi-migration-study`.  
> **AIMI freeze:** tag `aimi-baseline-2026-08-26` on `1ae418e106`.  
> **Change rule:** a later change of these defaults needs a new ADR. A therapy behaviour change also needs replay before/after.

## Context

The first KMP study left G0 open. Waiting for every product question blocked all code. This ADR sets defaults so week 1 can start. Soft items stay open on purpose.

## Decision

| ID | Default | Why |
|---|---|---|
| D1 host | iOS host is **Trio / LoopKit**. AIMI is an XCFramework (`AimiKit`) behind a Swift `DosingEngine`. | A full AAPS iOS app is a different programme. Trio already owns BLE wake and restore. |
| D2 CGM | **First CGM is Dexcom ONE+ / G7.** Android uses `:plugins:dexcom_oneplus`. iOS uses `G7SensorKit`. Both feed one `GlucosePort`. Libre 3 is wave 2 (Android native plugin, then `LibreTransmitter` + CoreNFC). | Direct BLE is the iOS loop heartbeat. Followers (xDrip, Notification Reader, Nightscout, Share) cannot keep iOS looping in the background. |
| D2 pump | **VirtualPump** until week 8. After the W8 go/no-go, pick **Dana-i** or **Medtrum** (LoopKit BLE). No Bluetooth Classic pump. | Pump choice must not block CGM landing or empty KMP modules. |
| D4 model | Keep **`modelUAM.tflite`**. SHA-256 `741c5248fb81a2551ee4c612c9cbf2be97dbf6b434db7b7407a3ba2214235092`. LiteRT on Android, TFLite on iOS, same bytes. Do not rewrite it as `AimiNeuralNetwork`. | The Kotlin net is not the TFLite graph. |
| D8 training | Training is opportunistic. A tick must dose from the last valid model even if training is late, cancelled, or missing. | iOS has no guaranteed 5 minute worker. |
| D9 parity | Final command is exact after pump quantize. Inner floats have documented tolerances. | Matches blueprint P2. |
| D12 pairing | Do not copy Android pairings, secrets, or pending pump commands to iOS. Re-pair on the phone. | Annex 7. |
| M0.6 defects | **Reproduce** known Android defects in the freeze (including SMB `trendIndicator` skew). Fix only with a new ADR and a new freeze tag. | A silent fix during extract looks like a KMP bug. |
| Strategy | **S2 extract.** Do not rebase `dev_OAPSAIMI` onto `kmp`. Translate AIMI Hilt to **Metro**. | This branch already left Hilt. |

## Still open (does not block W1–W8)

- Exact iOS pump after week 8 (Dana-i vs Medtrum).
- User import of UAM vs factory-only.
- Persist vs rebuild of each memory after process death.
- v1 product extras: Advisor, TPO, Hormonitor viewer, HealthKit depth.
- Apple distribution and Critical Alerts entitlement.
- Shadow length and go/no-go numbers for M11.

## Consequences

- Android gets the real AIMI One+ and Libre 3 plugins, adapted to Metro.
- iOS does **not** get the Android BLE/NFC stack in `iosMain`. It gets the same sensors through LoopKit behind `GlucosePort`.
- `AimiTickContext` is not the KMP API. The KMP API is `evaluate(input, state, models)`.
- `Ports.kt` on AIMI must not be copied as-is: it still has pump actuators. The engine must not command a pump.
