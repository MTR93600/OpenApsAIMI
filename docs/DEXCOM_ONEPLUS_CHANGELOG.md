# Dexcom ONE+ — development changelog (fork)

Dated journal of what landed on `feature/dexcom-oneplus-native`.  
**Not a claim of production readiness.** Device A3 confirmation remains required.

| Date | Milestone |
|------|-----------|
| 2026-07-18 | Product Q1–Q12 GO; agent plan; merge constraint; module `:plugins:dexcom_oneplus` + DI `@IntKey(446)` |
| 2026-07-18 | `SourceSensor.DEXCOM_ONEPLUS_NATIVE`, converters, UE Sources, notif remap `d1plus`/`dexcomone` |
| 2026-07-18 | AIMI: One+ is not G6 lead; Phase A follower wiring |
| 2026-07-18 | `:plugins:libkeks` vendored (xDrip pin `1e86d9a2…` / tag `2026.07.15`) + NOTICE |
| 2026-07-18 | GATT Android, KEKS auth, scan UI (DXC/FEBC), eng pref `UseRealSkeleton` |
| 2026-07-18 | EGV parse, TransmitterTime, SessionStart/Stop codecs, BackFillTx2, EGV poll loop |
| 2026-07-18 | Attach-safe policy (no auto SessionStop); reconnect without SessionStart; ingest dedup |
| 2026-07-18 | Start steps UX; USER_GUIDE when-to-enter-code |
| 2026-07-19 | UI i18n for phases/messages; session link on warm-up; `OnePlusSessionStartPolicy` tests |
| 2026-07-19 | Docs refresh: DEV_ONBOARDING, this changelog, status drift fix |

Pin provenance: see `plugins/dexcom_oneplus/NOTICE`, `plugins/libkeks/NOTICE`, `docs/DEXCOM_ONEPLUS_LICENCE_MEMO.md`.
