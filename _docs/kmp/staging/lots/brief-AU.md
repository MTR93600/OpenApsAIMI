# Lot AU — FoodAnalysisPrompt extract + meal JSON parsers

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `8a3aa81748` (Lot AT)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Dest already has meal DTOs, sanitizer, `OrgJsonCompat`.

**The cut:** dump `FoodAnalysisPrompt` lives in `AIVisionProvider.kt` with Bitmap + `org.json`. **Extract the prompt parser.** Copy dest-type JSON parsers. Omit Bitmap interface and HTTP/camera providers. Cap ~15. Copy count **1 extract + 2 files**.

**Compose-graph wall after this lot:** `PkPdIntegration` / UAM refresher / Compose screens stay dump. Tick / plugin stay parked. Camera Activities stay dump.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | why |
|---|---|
| dest `advisor/meal/FoodAnalysisPrompt.kt` (**extract**) | dump `object FoodAnalysisPrompt` from `AIVisionProvider.kt` only |
| `advisor/meal/MealVisionJsonParser.kt` | dest prompt + `EstimationResult` |
| `advisor/meal/MealVisionChatCompletionsParser.kt` | dest parser + `OrgJsonCompat` |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy `AIVisionProvider`, camera Activities, HTTP vision providers, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

- `org.json` → `kotlinx.serialization.json` + `OrgJsonCompat` (same read quirks).
- `JSONObject(json)` → `Json.parseToJsonElement(json).jsonObject`.
- Keep clamp / FPU / recommended-carb math unchanged.
- No Metro. No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-AU.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-AU.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
