# Lot AY — CODE report

Status: **DONE_WITH_CONCERNS**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `529e1b96a6` (Lot AX)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Composite / phone / HC steps not copied. Dest `ContextIntentDeserializer` not overwritten in this commit. `:pump:medtrum` not moved to `iosMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**The database steps provider is dest.** Dest Nightscout deserializer already existed (skip). Composite still needs dump HC + phone. Steps manager / Health Connect stay dump. Tick / plugin stay parked. Dest provider is not a live steps source. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: `AIMIDatabaseStepsProviderMTR` keeps dump `CoroutineScope` + `launch` for `PersistenceLayer.getStepsCountFromTimeToTime` (suspend). Same fire-and-forget cache as dump. Dispatcher is `aapsIoDispatcher`.

Concern: brief listed `ContextIntentDeserializer`; dest already had it. First compile failed on a dump-style elvis `.toFloat()` while overwriting dest. Dest original already had parens. Dest file restored. Second compile exit 0.

---

## Copied (1) — dest did not exist

| rel | notes |
|---|---|
| `steps/AIMIDatabaseStepsProviderMTR.kt` | dest interface. `kotlin.time.Instant`. kotlin atomics. `aapsIoDispatcher` |

## Dest-exists skip (1)

| rel | notes |
|---|---|
| `context/ContextIntentDeserializer.kt` | dest already KMP (`LTag.AIMI`, `OrgJsonCompat`). Not overwritten |

---

## Skipped — not this list

| rel | reason |
|---|---|
| `AIMICompositeStepsProviderMTR` | injects dump HC + phone |
| `AIMIPhoneStepsProviderMTR` | dump `StepService` |
| `AIMIStepsManagerMTR` | HC + phone sync |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- Clock / Instant / atomics / IO dispatcher / `LTag.AIMI` as brief.
- No Metro plugin map. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-AY.log`.

Attempt 1 **failed** (dump elvis on dest overwrite; dest restored).  
Attempt 2 **exit 0** (`--quiet`; no `e:`). Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE after dest-exists restore.

- Spec: DB steps only. Deserializer skip. Composite / HC / phone not copied.
- Next graph: Compose wall. Loop telemetry still `ReentrantLock`. Tick last.

Return DONE_WITH_CONCERNS.
