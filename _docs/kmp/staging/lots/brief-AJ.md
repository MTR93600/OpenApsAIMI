# Lot AJ — deliberate graph: kinetics authority + snapshot builder

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `e3a7732974` (Lot AI)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. ADR G0 is frozen — do not reopen.

Lot AI landed `PkPdRuntime` and `AimiIntelligenceSnapshot` / `PredictionAuthorityView`. This lot is **3 dump copies**. Cap ~15.

**The cut:** `CausalKineticsModulator`, `InsulinKineticsAuthority`, and `AimiIntelligenceSnapshotBuilder` are dest-type (dest posterior, governors, Runtime, snapshot views, `fromAuthority`). Copy them. Do **not** copy `PkPdIntegration`, auditor orchestrator, tick, or `SmbInstructionExecutor` (`android.content.Context`).

**Compose-graph wall after this lot:** integration class / UAM builder / Compose screens stay dump. Auditor host still LiveData + integration builder. Tick / plugin stay parked. Dest builder is not live tick.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

From `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>`  
to `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/<rel>`.

If dest already exists: **skip and report**. Do not overwrite.

None of these dest files exist.

| rel | why |
|---|---|
| `pkpd/CausalKineticsModulator.kt` | dest `CausalStatePosterior`. `"%.2f".format` → `aimiFmt2` |
| `pkpd/InsulinKineticsAuthority.kt` | dest modulator / governors / Runtime / snapshot views |
| `orchestration/AimiIntelligenceSnapshotBuilder.kt` | dest authority + Runtime + `fromAuthority` |

Copy order: modulator → kinetics authority → snapshot builder.

---

## Skip — do not copy this lot

Do **not** copy `PkPdIntegration`, `SmbInstructionExecutor`, auditor orchestrator / collector, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

1. **Metro** — none. No `@IntKey(225)`.
2. **Format** — modulator conf in reason: `aimiFmt2`. No `aimiFmt3`.
3. Keep therapy math.
4. Drop unused dump imports only if Native compile fails.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-AJ.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Copy the integration class, executor, or tick.
- Register `@IntKey(225)`.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-AJ.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
