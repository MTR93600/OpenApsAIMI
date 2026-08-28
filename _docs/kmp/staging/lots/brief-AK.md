# Lot AK — deliberate graph: snapshot JSON + PKPD prediction engine

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `54389cd694` (Lot AJ)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. ADR G0 is frozen — do not reopen.

Lot AJ landed the snapshot builder. `IntelligenceSnapshotJson` was parked on dump snapshot. `PredictionPhysioModulation` / `AdvancedPredictionEngine` were parked on dump `PkPdRuntime` (Lot V). Runtime, meal engine, UAM DTO, and latent state are dest. This lot is **3 dump copies**. Cap ~15.

**The cut:** copy the JSON writer and the PKPD prediction graph. Dest already has `AdvancedPredictionCurves`. Do **not** overwrite it. Do **not** copy tick, `PkPdIntegration`, auditor host, or `SmbInstructionExecutor`.

**Compose-graph wall after this lot:** integration class / UAM builder / Compose screens stay dump. Auditor still LiveData + integration builder. Tick / plugin stay parked. Dest engine is not live tick.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

From `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>`  
to `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/<rel>`.

If dest already exists: **skip and report**. Do not overwrite.

Dest `pkpd/AdvancedPredictionCurves.kt` already exists. Do **not** overwrite.

| rel | why |
|---|---|
| `orchestration/IntelligenceSnapshotJson.kt` | dest `AimiIntelligenceSnapshot`. kotlinx JSON already. Nullable strings → `JsonNull` (no `put(String?)` on Native) |
| `pkpd/PredictionPhysioModulation.kt` | dest Runtime / meal engine / UAM DTO / latent. `Locale` `String.format` → `aimiFmt2` |
| `pkpd/AdvancedPredictionEngine.kt` | dest curves + this-lot modulation. `System.currentTimeMillis()` → `aimiWallClockMs()`. Drop `@JvmStatic` / `@JvmOverloads`. `Constants.PREDICTION_GRAPH_MIN_MINUTES` is freeze-only (2 h = 120) — inline, do **not** add it to kmp `Constants` |

Copy order: JSON → modulation → engine.

---

## Skip — do not copy this lot

Do **not** copy `PkPdIntegration`, `AimiLoopTelemetry` / `AimiLoopGate` (JVM lock / hormonitor exporter), tick, `OpenAPSAIMIPlugin`, auditor orchestrator.

---

## Rewrite on copy

1. **Metro** — none. No `@IntKey(225)`.
2. **JSON** — kotlinx only. Nullable string keys stay present as `JsonNull`.
3. **Time / format / JVM** — wall clock, `aimiFmt2`, drop Jvm annotations.
4. Keep therapy math. French dump comments stay.
5. Do not overwrite dest `AdvancedPredictionCurves.kt`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-AK.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Copy tick / integration / auditor host.
- Add `PREDICTION_GRAPH_MIN_MINUTES` to `:core:data` `Constants`.
- Register `@IntKey(225)`.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-AK.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
