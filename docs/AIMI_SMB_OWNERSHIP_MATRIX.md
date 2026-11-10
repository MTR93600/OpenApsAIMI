# AIMI — SMB Ownership Matrix

> **Scope.** Precise, ordered map of *who decides the SMB* in a determine_basal tick and *every gate it
> passes*, end to end. Built from a code trace of
> `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt`.
> Line numbers are a snapshot (current at writing) — rely on the **function names**. The delivered SMB
> is `rT.units`; the universal exit is [`finalizeAndCapSMB`](#3-universal-finalize-gate-finalizeandcapsmb-§10559).

---

## 0. The ownership principle

1. **First early-return wins.** determine_basal runs ordered stages; the **first** stage that
   `return rT` *owns the tick's SMB*. Everything after it is skipped.
2. **If no stage returns early**, the **global AIMI SMB** path computes the SMB (the default engine).
3. **Every** SMB — from any owner — exits through **`finalizeAndCapSMB`** (or `finalizeSmbToGive`),
   which applies the universal safety cap chain before writing `rT.units`.
4. **Post-hypo delivery authority** and the **basal-first channel** (T3C / Harmonia) are *cross-cuts*
   that bind or zero SMB regardless of which owner is active.

---

## 1. Ordered ownership gates (top → bottom = precedence)

| # | Stage / function | file:line | Owns SMB how | Gate condition | Can early-return? |
|---|---|---|---|---|---|
| 1 | `applyLegacyMealModes` | §2304 | Sets `rT.units` = legacy meal prebolus | An explicit meal mode active (meal/bfast/lunch/dinner/highcarb/snack) | ✅ returns `rT` |
| 2 | Safety halt (`runPredPipelineSafetyHaltOrReturn`) | §13958 | Forces SMB→0 / TBR-safe | Prediction-pipeline hard safety (hypo terminal, bad data) | ✅ `Halt` |
| 3 | **Meal Advisor** (`runMealAdvisorDecisionOrReturn`) — *PRIORITY 3* | §13977 | Sets `rT.units` = advisor-validated bolus | Active Meal Advisor request | ✅ returns `rT` |
| 4 | Hard-brake Lyra (`runHardBrakeLyraOrReturn`) | §13991 | Zeroes/limits SMB | Sharp drop / hard-brake trigger | ✅ returns `rT` |
| 5 | Post-hypo classify + **authority** (`refreshPostHypoDeliveryAuthorityForTick`) | §14006–14026 | *No delivery* — sets `lastPostHypoDeliveryAuthority` that **caps every later SMB** (`capSmbU`) | Post-hypo state / rebound | ❌ cross-cut |
| 6 | **Autodrive V3** (`runAutodriveV3MultiVariableBranch`) | §14045 | Delivers SMB = `max(modelSmb, aggressiveFloor)` via `deliverV3SmbFromRbt` + RBT; may set `skipLegacySmbBlender` | `AutoDriveGater.shouldEngageV3` (BG>150, or BG≥120 & Δ>1.2, or meal-rise) **and** `v3CommandSafe` | ❌ (delivers in place) |
| 7 | RBT live tick (`resolveAndWireRbtLiveTick`) | §14031 (if V3 off) / inside V3 §4326 | Sets **release authority** + **Harmonia SMB modulation** | RBT resolution | ❌ cross-cut |
| 8 | Compression / **Drift Terminator** (`runPostHypoCompressionAndDriftTerminatorOrReturn`) | §14070 | Compression → zero+return; Drift → micro-SMB via `finalizeAndCapSMB` | Compression rebound, or plateau drift (BG≥80, not post-hypo, no recent bolus) | ✅ returns `rT` |
| 9 | **Global AIMI SMB** (main path, after `buildGlobalAimiBasalScheduleBootstrap`) | §14091+ | Computes the SMB when no early owner claimed the tick | default | terminal |

> **Note on the basal-finalize stage** (runs late, §7372+): it decides the **basal** channel, but it
> also gates/modulates SMB — see §2.

---

## 2. Basal-finalize sub-arbitration (T3C ↔ Harmonia) — §7372+

Runs in the basal-finalize bundle. Order and effect on SMB:

| Component | file:line | SMB effect | Precedence rule |
|---|---|---|---|
| **T3C native basal-first** (`planT3cBasalFirstProduction`) | §7372 | **No SMB** (basal-only). When it owns, Harmonia is **skipped**. | Absolute priority |
| **Harmonia production basal-first** (`planHarmoniaProductionBranch`) | §7373 | **Basal only** (`adds_smb_authority=false` on production record). | Only if T3C not owning **and** `releaseAuthority == NONE` (§7027) |
| **Pattern catalog soft/hard** (`PhysiologicalPatternPolicy`) | pattern package | Soft meal (`MEAL_FIRST_WAVE` / `MEAL_UNDECLARED_FAST`) = **proposal 1.20**, not binding `min()`. Co-active HARD protectors (exercise, stacking, post-hypo…) still bind via `hardBindingCapU()`. | Before Harmonia SMB arbiter |
| **Harmonia SMB authority** (`HarmoniaSmbArbiter` + RBT) | RecursiveBeliefResolver | Tree `insulin_intent` + soft proposal + rise → `ACCEPT` / `LIFT_WITHIN_ENVELOPE` / `REDUCE` within `min(mpc, maxSMBHB, IOB headroom, hard envelope)`. Never above maxSMBHB path. | Only if `releaseAuthority != NONE` **and** `basalFirstChannel == NONE` |
| **Auditor** | AuditorOrchestrator | CONFIRM / SOFTEN only — **never lift**. External async advisory. | After finalize |

So on a given tick Harmonia is **either** a basal owner **or** an SMB arbitrator, never both.
Soft meal catalog proposals no longer mute MPC/HTR before arbitration. See
[AIMI_HARMONIA_SMB_ARBITRATION.md](AIMI_HARMONIA_SMB_ARBITRATION.md).

Tree deploys `insulin_intent` (`NEED_MORE_INSULIN` / `MEAL_SUPPORT` / …); label
`insulin_authority=harmonia_basal_and_smb_arbitration`.

---

## 3. Universal finalize gate (`finalizeAndCapSMB`) — §10559

**Every** SMB exits here (or via `finalizeSmbToGive`). Applied **in this order**:

| Order | Gate | Effect |
|---|---|---|
| 1 | HTR hyper-release floor (IOB-gated) | May raise SMB toward a credible-hyper floor (IOB < 0.75/0.92·maxIob) |
| 2 | `applySafetyPrecautions` → coerce to `baseLimit` | Core safety net; caps to the maxSMB-derived base limit |
| 3 | Thyroid NORMALIZING guard | Blocks (→0) or caps SMB during thyroid normalization |
| 4 | Refractory window | Blocks if `sinceBolus < window` unless explicit/`bypassSmbRefractory`; progressive relax for confirmed meal rise (×0.35–0.70) |
| 5 | Insulin stacking cap (`smbAbsoluteCapU`) | Caps SMB under surveillance stacking |
| 6 | **maxSMB / maxSMBHB + IOB headroom** | `min(candidate, maxSmbCap)` then `min(·, maxIob − iob)`; hard absolute cap |
| 7 | Meal-force override | A confirmed meal may exceed *minor* checks (still capped at 30 U) |
| 8 | HTR finalize floor (re-floor) | Re-applies the hyper floor after caps, bounded by IOB space |
| 9 | **Write** `rT.units = finalUnits.coerceAtLeast(0.0)` | Final delivered SMB; stamps `internalLastSmbMillis` |

**Cross-cut applied at source:** the V3 path pre-caps with `lastPostHypoDeliveryAuthority.capSmbU(...)`
before this pipeline; the same post-hypo authority also suppresses meal/SMB delivery elsewhere
(gate #5 above in §1).

---

## 4. Cross-cut authorities (bind SMB regardless of owner)

- **Post-hypo delivery authority** (`lastPostHypoDeliveryAuthority`): set at §14020; `capSmbU(...)` caps
  any SMB; `suppressMealDelivery` / `forceMealInterpretationSuppressed` zero meal-driven SMB.
- **RBT release authority** (`ReleaseAuthority`): `NONE` ⇒ basal-first eligible & Harmonia SMB
  modulation disabled; `!= NONE` ⇒ basal-first blocked & Harmonia may modulate SMB.
- **`skipLegacySmbBlender`** (set in V3 when authoritative): when V3 delivered SMB and
  `OApsAIMIautoDriveAuthoritative` is on, the legacy MPC/PI SMB blender is skipped this tick.

---

## 5. Notes

- **Classic (V1/V2) autodrive removed** — there is no longer a `tryAutodrive`/V2 SMB owner; its
  aggressive-rise prebolus was absorbed into **V3** as the opt-in `aggressiveRiseSmbFloorU`
  (`OApsAIMIautodriveAggressiveSmbFloor`, default off), delivered as `max(modelSmb, floor)` and fully
  re-bounded by the §3 pipeline. See `docs/AIMI_ARCHITECTURE_MAP.md` §9.
- The aggressive floor **never reduces** the model SMB and **never bypasses** §3 caps (verified).
- This matrix is delivery-side (SMB). Basal-side ownership (TBR) is mapped in
  `AIMI_ARCHITECTURE_MAP.md` (Harmonia production basal-first, Harmonizer, T3C native).
