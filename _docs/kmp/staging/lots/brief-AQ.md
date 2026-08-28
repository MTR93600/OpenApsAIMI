# Lot AQ — patient runtime snapshot + loop cache

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `e31a4159b9` (user review doc labeled Lot AP — not a peel)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Dest already has `AimiCascadeArbitrationArtifacts` atomics.

**The cut:** dump `PatientStateRuntimeRepository` + `PatientStateLoopCache` are dest-type. `PatientRefreshSource` lives in dump `PatientStateRuntimeRefresher.kt` (UAM builder — omit the refresher). Cap ~15. Copy count **2 + 1 extract**.

**Compose-graph wall after this lot:** presentation (`Locale`) stays dump. Refresher stays dump. Tick / plugin stay parked. Do **not** copy `AimiLoopGate` (`ReentrantLock`). Do **not** copy `:pump:medtrum`.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | why |
|---|---|
| dest `patient/PatientRefreshSource.kt` (**extract**) | dump `internal enum class PatientRefreshSource` from `patient/PatientStateRuntimeRefresher.kt` only |
| `patient/PatientStateLoopCache.kt` | dest physio / pattern / context types |
| `patient/PatientStateRuntimeRepository.kt` | includes dump `PatientRuntimeSnapshot` |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy `PatientStateRuntimeRefresher`, `PatientStatePresentation`, `UamHypothesisStateBuilder`, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

- `java.util.concurrent.atomic.AtomicReference` → `kotlin.concurrent.atomics.AtomicReference` (`load` / `store`, `@OptIn(ExperimentalAtomicApi::class)`). Same as dest `AimiCascadeArbitrationArtifacts`.
- `BufferOverflow` explicit import, not FQ.
- No Metro. No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-AQ.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Amend Lot AP. No push.

---

## Report

`_docs/kmp/staging/lots/report-AQ.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
