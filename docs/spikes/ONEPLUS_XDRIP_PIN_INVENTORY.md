# xDrip pin inventory — verified at A1 commit

**Pin:** `1e86d9a2a52577ed2c30fbf7b69d75fd56e6918f` (tag `2026.07.15`)  
**Repo:** https://github.com/NightscoutFoundation/xDrip  
**Date inventoried:** 2026-07-18  
**Licence:** GPL-3.0 — see [DEXCOM_ONEPLUS_LICENCE_MEMO.md](../DEXCOM_ONEPLUS_LICENCE_MEMO.md)

Tree queried via GitHub git trees API (recursive) at the pin. App paths preferred over `wear/` duplicates.

---

## Priority port set (v1 native ONE+)

| Upstream (app) | Role | AAPS target |
|----------------|------|-------------|
| `libkeks/src/main/java/jamorham/keks/**` (~30 module files) | J-PAKE / ExtraData auth | `session/OnePlusSessionAuth*` |
| `.../services/Ob1G5CollectionService.java` | BLE collector state machine | extract → scan/gatt/reconnect (**not** whole Service) |
| `.../g5model/Ob1G5StateMachine.java` | Control / EGV / backfill / G7 | parse + session |
| `.../g5model/BluetoothServices.java` | GATT UUIDs | **ported** → `gatt/OnePlusBluetoothUuids.kt` |
| `.../g5model/EGlucoseRxMessage.java` (+ `EGlucoseRxMessage2`, `EGlucoseTxMessage`) | G7-style glucose | `parse/OnePlusGlucoseParser` |
| `.../cgm/dex/g7/EGlucoseRxMessage.java`, `BaseMessage.java`, `BackfillControlRx.java` | G7 helpers | parse / backfill |
| `.../g5model/SessionStartTxMessage.java` (+ Rx) | Session start | session |
| `.../g5model/CalibrationState.java` | Warm-up / OK mapping | `OnePlusWarmupState` |
| `.../cgm/dex/TxIdHelper.java` | Short pairing validation | **ported rules** → `OnePlusSessionStart` + `OnePlusInvalidShortPairingCodes` |
| `.../cgm/dex/BlueTails.java`, `ClassifierAction.java` | ADV classify | `scan/OnePlusBleScanner` |

---

## Already landed (attribution)

| Location | Source |
|----------|--------|
| `:plugins:libkeks` (full Java tree) | `libkeks/**` + `IPluginDA.java` |
| `gatt/OnePlusBluetoothUuids.kt` | `BluetoothServices.java` |
| `gatt/OnePlusGattClientAndroid.kt` | AAPS rewrite (Eversense-style executor; UUIDs above) |
| `session/OnePlusSessionAuthKeks.kt` | IPluginDA pump over GATT (Ob1 extract still incomplete) |
| `session/OnePlusInvalidShortPairingCodes.kt` | `TxIdHelper.INVALID_SHORT_PAIRING_CODES` |
| `session/OnePlusSessionStart.kt` | `TxIdHelper.isValidShortPairingCode` rules |

---

## Do not port yet

- Whole Ob1 Android Service shell / RxAndroidBle glue as-is  
- xDrip Pref / JoH / UI / Nightscout upload  
- Remote plugin DEX download hosts  

---

## Remaining after device A3 GO

1. Harden first-pair bond UX from device logs.  
2. Optional: 24h backfill window; DexTimeKeeper persistence across process death.  
3. Keep Stub as rollback until user confirms Real; then consider flipping default.  
4. See [DEXCOM_ONEPLUS_CHANGELOG.md](../DEXCOM_ONEPLUS_CHANGELOG.md) for what already landed (GATT, libkeks, EGV, SessionStart, Backfill).
