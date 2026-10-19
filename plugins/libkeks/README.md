# `:plugins:libkeks`

Vendored **KEKS** J-PAKE / ExtraData auth from [NightscoutFoundation/xDrip](https://github.com/NightscoutFoundation/xDrip) at pin `1e86d9a2a52577ed2c30fbf7b69d75fd56e6918f` (GPL-3.0).

Consumed by `:plugins:dexcom_oneplus`. **Kotlin Multiplatform**: Android, iOS and the JVM. The
sources were translated from the vendored Java in October 2026 so the ONE+/G7 handshake can run on
an iPhone; BouncyCastle, Lombok and `javax.crypto` are gone from the shared code. See `NOTICE` for
exactly what changed and `src/commonTest` for the golden values that prove the bytes did not.

Only three things are left to the platform, each behind an `expect` in `crypto/KeksPlatform.kt`:
a secure random generator, one AES block in ECB mode, and where a log line goes.
