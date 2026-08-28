# Lot AT — meal estimate DTOs + sanitizer + user prompt

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `b43326bb5d` (Lot AS)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Dest already has `LlmWorldConservativePreamble` and `HarmoniaDecision`.

**The cut:** dump meal DTOs live in `AIVisionProvider.kt` with Bitmap + `org.json`. **Extract the DTOs.** Copy sanitizer + user prompt. Omit vision providers, camera Activities, and `FoodAnalysisPrompt` (`org.json`). Cap ~15. Copy count **1 extract + 2 files**.

**Compose-graph wall after this lot:** `PkPdIntegration` / UAM refresher / Compose screens stay dump. Tick / plugin stay parked. Camera Activities stay dump.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | why |
|---|---|
| dest `advisor/meal/MealEstimateModels.kt` (**extract**) | dump `VisibleFoodItem` / `MacroRange` / `EstimationResult` from `AIVisionProvider.kt` only |
| `advisor/meal/MealAdvisorResponseSanitizer.kt` | dest DTOs |
| `advisor/meal/MealVisionUserPrompt.kt` | dest sanitizer + Harmonia + preamble |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy `AIVisionProvider`, `FoodAnalysisPrompt`, `MealVisionJsonParser`, vision HTTP providers, camera Activities, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

None expected on sanitizer / prompt. DTO copy as-is. No Metro. No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-AT.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-AT.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
