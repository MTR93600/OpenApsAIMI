# Lot BC — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `fb9be233d3` (Lot BB)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest common: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Composite / HC / steps manager not copied. `:pump:medtrum` not moved to `iosMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Phone step counter host is dest (androidMain).** Dest `AIMIStepsProviderMTR` is the interface. Sensor is not registered (plugin parked). Tick / plugin stay parked. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: none. Sensor callback on the Android listener thread, same as dump.

---

## Copied (2) — dest did not exist

| rel | notes |
|---|---|
| androidMain `StepService.kt` | `aimiWallClockMs`. Dump `Log` kept (host object) |
| androidMain `steps/AIMIPhoneStepsProviderMTR.kt` | dest interface. `kotlin.time.Instant`. `LTag.AIMI` |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `AIMICompositeStepsProviderMTR` | dump HC provider |
| HC steps / manager | Health Connect T2 |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- Clock / Instant / `LTag.AIMI` as brief.
- No Metro plugin map. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-BC.log`.

Attempt 1 **exit 0** (`--quiet`; no `e:`). Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: phone sensor host only. Plugin does not register the sensor yet.
- Next graph: HC steps host, or Compose. Tick last.

Return DONE.
