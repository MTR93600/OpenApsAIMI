# Lot BT — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `e6f793e0c1` (Lot BS)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Health Connect library not added. `:pump:medtrum` not moved to `iosMain`. Autodrive trainer / backfiller / `MechanismAttentionGate` not copied.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Autodrive CSV lake File I/O is dest (androidMain).** Tick / plugin stay parked. Dest lake is not a live Autodrive host. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: `ReentrantLock.tryLock` on the caller (APS) thread, same as dump. `synchronized(deferredMonitor)` for the carry-forward buffer. No coroutines. Backfiller / trainer stay dump, so dest lock is unused until they peel.

---

## Copied (2) — dest did not exist

| rel | notes |
|---|---|
| androidMain `autodrive/learning/AutodriveDatasetLock.kt` | `ReentrantLock` + `tryLock`. `AapsLock` has no `tryLock` |
| androidMain `autodrive/learning/AutodriveDataLake.kt` | dest `AimiStorageHelper` + dest schema/models |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `AutodriveNeuralTrainer` / `AutodriveDataBackfiller` / workers | training host |
| `MechanismAttentionGate` | File + dump trainer |
| Health Connect | no `connect-client` on `:plugins:aps` |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- `System.currentTimeMillis()` → `aimiWallClockMs()`.
- `"%.1f"` → `aimiFmt1`. `"%.3f"` → `NumberFormat.DECIMAL_3`. `"%.4f"` → `NumberFormat.withDecimals(4)`.
- `LTag.APS` → `LTag.AIMI`.
- Keep `File` / `FileWriter` / `SimpleDateFormat` / `synchronized`.
- KDoc dump-only `[AutodriveDataBackfiller]` / `[AutodriveNeuralTrainer]` → backticks.
- No Metro plugin map. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-BT.log`.

Attempt 1 **exit 0**. Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: dataset lock + CSV append only. Trainer / backfiller not copied.
- Next graph: `BasalNeuralLearner`, Autodrive trainer, or Compose. Tick last.

Return DONE.
