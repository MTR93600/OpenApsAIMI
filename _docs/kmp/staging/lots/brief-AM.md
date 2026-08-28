# Lot AM — deliberate graph: OREF feature builder (Calendar rewrite)

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `8c943c13fe` (Lot AL)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. ADR G0 is frozen — do not reopen.

Lot AL landed `AimiProfileSnapshot`. Lot AL report named `OrefFeatureBuilder` as next after a `Calendar` rewrite. This lot is **1 dump copy**. Cap ~15.

**The cut:** copy `advisor/oref/OrefFeatureBuilder.kt`. Dest already has `OrefReasonParser` / `OrefModelFeatures`. Dump hour uses `Calendar` + UTC — rewrite to `kotlinx.datetime` `TimeZone.UTC` (same UTC hour, not device zone). Do **not** copy `OrefLocalPipeline` (`Context` + `Dispatchers` + DB) or `OrefOnnxScorer` / `OrefPersonalMlTrainer` / `OrefUserInsightFormatter` (ONNX / File / `R.string`).

**Compose-graph wall after this lot:** `PkPdIntegration` / UAM builder / Compose screens stay dump. Auditor host still LiveData + integration builder. Tick / plugin stay parked. Dest feature builder is not live tick.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

From `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>`  
to `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/<rel>`.

If dest already exists: **skip and report**. Do not overwrite.

This dest file does not exist.

| rel | why |
|---|---|
| `advisor/oref/OrefFeatureBuilder.kt` | dest `AimiProfileSnapshot` / `OrefReasonParser` / `RT`. `Calendar` UTC hour → `Instant` + `TimeZone.UTC`. Explicit `abs` import |

---

## Skip — do not copy this lot

Do **not** copy `OrefLocalPipeline`, `OrefOnnxScorer`, `OrefPersonalMlTrainer`, `OrefUserInsightFormatter`, `PkpdAdvisor`, `PkPdIntegration`, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

1. **Metro** — none. No `@IntKey(225)`.
2. **Time** — UTC hour from `gv.timestamp` via `kotlinx.datetime` (same as dump `TimeZone.getTimeZone("UTC")`).
3. Keep feature math and `OrefModelFeatures` index order.
4. Drop unused dump imports only if Native compile fails.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-AM.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Copy ONNX / File / Advisor UI.
- Copy or rewrite `DetermineBasalAIMI2.kt`. Tick last.
- Register `@IntKey(225)`.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-AM.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
