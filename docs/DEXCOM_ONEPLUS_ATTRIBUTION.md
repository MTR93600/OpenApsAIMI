# Dexcom ONE+ — cross-module attribution map

Single reference for the provenance of the native Dexcom ONE+ / G7 CGM feature.
It consolidates the per-module NOTICE files and the per-file `Provenance:` markers.

**Licensing.** OpenApsAIMI is AGPL-3.0-or-later (root `LICENSE.txt`). Derived parts
come from **xDrip+** (GPL-3.0) and **Juggluco** (GPL-3.0); both are AGPL-3
compatible (GPLv3 §13). No copyright holder is invented; where upstream files
lack `Copyright (C)` lines this is stated, not guessed.

**Upstream pins**
| Upstream | Repo | License | Pin |
|---|---|---|---|
| xDrip+ | https://github.com/NightscoutFoundation/xDrip | GPL-3.0 | `1e86d9a2a52577ed2c30fbf7b69d75fd56e6918f` (tag 2026.07.15) |
| Juggluco | https://github.com/j-kaltes/Juggluco | GPL-3.0 | **TODO — commit not yet recorded** |
| libkeks (jamorham) | vendored from xDrip above | GPL-3.0 | same pin as xDrip |
| BouncyCastle | Gradle (transitive via libkeks) | MIT-style | — |

## Provenance by file

Legend: **X** = derived from xDrip · **J** = derived from Juggluco · **X+J** =
co-derived · **AAPS** = original OpenApsAIMI · **vendor** = third-party verbatim.

### `:plugins:libkeks` (1 794 LOC) — vendor (xDrip/jamorham)
All 24 `.java` files vendored from xDrip; each carries an
`SPDX-License-Identifier: GPL-3.0-only` header. See `plugins/libkeks/NOTICE`.

### `:plugins:dexcom_oneplus` (5 391 LOC)

| File | LOC | Origin | Upstream |
|---|--:|:--:|---|
| gatt/OnePlusBluetoothUuids.kt | 33 | X | BluetoothServices.java |
| gatt/OnePlusKeksNotify.kt | 25 | X | Ob1 notify routing |
| parse/OnePlusFastCrc16.kt | 43 | X | FastCRC16.java |
| parse/OnePlusGlucoseParser.kt | 203 | X | EGlucoseRxMessage(2) |
| parse/OnePlusCalibrationState.kt | 76 | X | CalibrationState |
| parse/OnePlusEGlucoseTx.kt | 24 | X | EGlucoseTxMessage |
| parse/OnePlusTransmitterTimeTx/Rx.kt | 23+58 | X | TransmitterTimeTx/RxMessage |
| parse/OnePlusSessionStartTx/Rx.kt | 34+68 | X | SessionStartTx/RxMessage |
| parse/OnePlusSessionStopTx/Rx.kt | 30+48 | X | SessionStopTx/RxMessage |
| parse/OnePlusBackFillTx/ControlRx/Stream.kt | 26+15+93 | X | BackFillTxMessage2 / BackFillControlRx / BackFillStream |
| session/OnePlusBackfillSession.kt | 147 | X | Ob1G5StateMachine.backFillIfNeeded |
| session/OnePlusInvalidShortPairingCodes.kt | 44 | X | TxIdHelper.INVALID_SHORT_PAIRING_CODES |
| session/OnePlusSessionStart.kt | 32 | X | TxIdHelper.isValidShortPairingCode |
| session/OnePlusKeksGuideCerts.kt | 68 | X | xDrip G7 keks QR / Ob1 setPersistence |
| identity/OnePlusAdvCandidate.kt | 87 | J | isG7 (DX+CM/02/01) |
| identity/OnePlusGs1ApplicatorParser.kt | 104 | J | BarCode GS1 AIs / dexcomEnd |
| identity/OnePlusSensorIdentity.kt | 41 | J | GS1 Data Matrix / DexDeviceName |
| identity/OnePlusSensorStore.kt | 92 | J | short-auth reconnect persistence |
| scan/OnePlusBleScannerAndroid.kt | 201 | J | isG7 scan soft-filter |
| gatt/OnePlusGattClient.kt | 119 | X+J | Ob1 indications / Juggluco notifications |
| gatt/OnePlusGattClientAndroid.kt | 1077 | X+J* | Ob1 CCCD constraints + Juggluco bond/createBond/removeBond/MTU |
| oem/OemDeviceProfile.kt | 71 | X+J* | Ob1 settle + Juggluco Dex MTU/interval |
| oem/DeviceProfileRegistry.kt | 115 | X+J* | Juggluco "never requestMtu" defaults |
| session/OnePlusAuthStatusRx.kt | 34 | X+J | libkeks AuthStatusRxMessage + Juggluco ChallengeReply |
| session/OnePlusSessionAuth.kt | 59 | X+J | libkeks pin + Juggluco short-path |
| session/OnePlusSessionAuthKeks.kt | 352 | X+J | libkeks + Ob1 doNext + Juggluco short-auth recovery |
| session/OnePlusEgvSession.kt | 427 | X+J | Ob1 doGetData + Juggluco getdatacmd |
| OnePlusCgmDriver(Real/Stub/s).kt | 650 | AAPS | — |
| session/OnePlusBleSession.kt | 615 | AAPS* | reconnect/OEM orchestration (Juggluco-informed patterns) |
| reconnect/OnePlusReconnectPolicy.kt | 38 | AAPS | — |
| scan/OnePlusBleScanner.kt + OnePlusScanResult.kt | 61 | AAPS | — |
| warmup/OnePlusWarmupClock.kt | 43 | AAPS | — |
| session/OnePlusBackfill.kt + OnePlusSessionStartPolicy.kt | 49 | AAPS | — |
| OnePlusGlucoseSample/Watcher/LogMarkers/WarmupState.kt | 66 | AAPS | — |

`*` = file is mostly original AAPS implementation (Android GATT plumbing / OEM
profile abstraction / reconnect orchestration); only the protocol *choreography*
within it is derived.

### `:plugins:source` (ONE+ files, 1 209 LOC)

| File | LOC | Origin | Upstream |
|---|--:|:--:|---|
| DexcomOnePlusIngest.kt | 82 | X | Ob1 getForPreciseTimestamp (4-min dedup) |
| DexcomOnePlusPlugin.kt | 173 | AAPS | — |
| activities/DexcomOnePlus{Start,Status,Warmup}Activity.kt | 781 | AAPS | Compose UI |
| compose/DexcomOnePlusUiLabels.kt + WarmupCountdown.kt | 101 | AAPS | — |
| keys/DexcomOnePlus{Boolean,Intent}Key.kt | 72 | AAPS | — |

## LOC rollup (~8 394 total)

| Provenance | LOC | % (raw, file-level) |
|---|--:|--:|
| Vendored (libkeks = xDrip) | 1 794 | 21 % |
| xDrip-derived | 1 172 | 14 % |
| Juggluco-derived | 525 | 6 % |
| Co-derived xDrip+Juggluco | 2 254 | 27 % |
| Original AAPS | 2 649 | 32 % |

**Calibrated reading** (the co-derived bucket is dominated by
`OnePlusGattClientAndroid` 1 077 LOC, mostly original Android plumbing with only
choreography derived): roughly **~49 % original AAPS · ~21 % libkeks(xDrip) ·
~19 % xDrip · ~11 % Juggluco**. xDrip owns the messages/auth layer; Juggluco owns
device-identity/scan + bond/EGV choreography.

## Open verification points (not legal advice — maintainer/counsel to confirm)
1. SPDX `GPL-3.0-only` vs `-or-later` — confirm against xDrip/Juggluco LICENSE.
2. libkeks unmodified from the pin — confirm with a diff.
3. Record the Juggluco commit pin (currently TODO in `dexcom_oneplus/NOTICE` §2).
4. Root `LICENSE.txt` is AGPL-3.0(-or-later).

_This is a compliance-support document, not legal advice; a project maintainer /
counsel should validate before wide distribution._
