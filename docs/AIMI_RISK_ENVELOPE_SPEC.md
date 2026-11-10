# AIMI Risk Envelope — specification unifiée

**Status:** Implemented (Phase 1–3 foundation) — evaluate in production on `dev_OAPSAIMI_mergeDEV`  
**Related:** `AIMI_ORCHESTRATION_ROADMAP.md`, `docs/ARCHITECTURE.md`, PR_NegativeIobEbgInflation (message-20)

---

## Problem

AIMI consumes **multiple incompatible views** of the same metabolic risk:

| Signal | Source today | Used for |
|--------|----------------|----------|
| `minPred` across curves | PKPD series clamped to 39 mg/dL | Export, stacking, diagnostics |
| `pred_terminal` / `eventual` | Last PKPD step | Hypo guard SMB, MPC inputs |
| `minBg` composite | Early PRED_PIPE (pre-PKPD refresh) | Hypo **reason text**, V3 threshold |
| `IOB` | AAPS bilinear vs PKPD log-normal | Legacy eventual BG, curves |

This produces **false hypo blocks during hyper** (composite stale vs decision) and **false SMB during flat BG** (negative AAPS IOB sign artefact).

---

## Design principle

One **immutable snapshot per phase** of the tick. Decision code reads named fields — never implicit member state mixed across phases.

### Phases (tick order preserved)

1. **`EARLY`** — after `runAdvancedPredictionsAndPredPipePrep`  
   - Consumers: `trySafetyStart`, Autodrive V3/V2, drift/compression threshold  
   - **Not** reused for SMB hypo guard after PKPD refresh  

2. **`DECISION`** — after `computePkpdPredictions` + IOB consensus  
   - Consumers: `shouldBlockHypoWithHysteresis`, hypo reason strings, naive eventual BG, export JSONL fields  
   - **Authoritative** for insulin SMB blocking in the legacy/UAM path  

Orchestration invariants 4–9 in `AIMI_ORCHESTRATION_ROADMAP.md` are unchanged.

---

## Data contract: `AimiRiskEnvelope`

| Field | Meaning |
|-------|---------|
| `phase` | `EARLY` or `DECISION` |
| `bgNowMgdl` | Current BG used by the tick |
| `deltaMgdlPer5` | 5-min delta |
| `predTerminalMgdl` | Terminal prediction for **decisions** in this phase |
| `eventualTerminalMgdl` | Eventual BG for **decisions** in this phase |
| `pathMinRawMgdl` | Minimum along raw prediction path (before numeric floor) |
| `pathMinClampedMgdl` | Minimum after `max(39, …)` clamp |
| `pathMinHitNumericFloor` | True if raw min &lt; 39 and clamp raised it |
| `compositeMinMgdl` | `min(bg, predTerminal, eventualTerminal)` — hypo composite |
| `hypoThresholdMgdl` | `HypoThresholdMath.computeHypoThreshold(compositeMin, lgsThreshold)` |
| `aapsIobUnits` | AAPS `iobCobCalculator` IOB |
| `pkpdIobUnits` | Sum of per-bolus PKPD residual IOB (nullable if PKPD off) |
| `iobDecisionUnits` | Consensus IOB for sign-sensitive math |
| `iobSource` | `AAPS_ALIGNED`, `PKPD_WHEN_AAPS_NEGATIVE`, `AAPS_DEFAULT` |
| `naiveEbgSignGuardApplied` | Legacy naive eventual BG collapsed to current BG |

### Numeric floor (display vs clinical)

- **`AimiRiskConstants.NUMERIC_FLOOR_MGDL = 39.0`** — robustness clamp on **curve points only**.  
- **`pathMinHitNumericFloor = true`** → export/logs must **not** treat `pathMinClamped` as a clinical hypo prediction without context.

---

## IOB consensus

When `|pkpdIob - aapsIob| < 0.20 U` → trust AAPS.  
When `aapsIob < 0` and `pkpdIob > 0.10 U` → trust PKPD (sign artefact window).  
Else → AAPS.

Used in: `NaiveEventualBgSignGuard`, decision envelope, `RISK_DECISION` log line.

---

## Hypo guard unification

All predictive hypo decisions (`HypoGuard`, `LgsSafetyTriage` Tier 2/3, `setTempBasal` kill-switch when not partial) delegate to **`PredictiveHypoEvaluator`** with optional **`MealSafetyContext`**. See [LGS_PREDICTIVE_MEAL_BLIND_CASE_STUDY.md](LGS_PREDICTIVE_MEAL_BLIND_CASE_STUDY.md).

`shouldBlockHypoWithHysteresis` **must** use:

- `hypoThresholdMgdl` from **`DECISION`** envelope (same formula as V3 early threshold, but on **post-PKPD** predictions)  
- **Not** hard-coded `computeHypoThreshold(80.0, …)`

Hypo reason strings use `compositeMinMgdl` from **`DECISION`**, not early hoisted `minBg`.

---

## Logging (production evaluation)

grep APS logs for:

- `RISK_EARLY:` — early phase snapshot  
- `RISK_DECISION:` — authoritative SMB hypo inputs  
- `NAIVE_EBG_SIGN_GUARD:` — sign artefact mitigation  
- `IOB_CONSENSUS_PKPD:` — AAPS→PKPD IOB switch  

### Production acceptance criteria (2–4 weeks)

| Metric | Target |
|--------|--------|
| Hyper BG &gt; target+40 with `Hypo protection → SMB=0` | Decrease vs baseline export |
| `pathMinHitNumericFloor` + SMB=0 same tick | Review: should be rare; investigate if frequent |
| Flat/falling BG + SMB &gt; 0 without meal rise | Decrease (negative IOB window) |
| Clinically confirmed hypo from insulin | No increase |

---

## Future (Phase 4+)

- Ordered `RiskAdjustment` list for trajectory / PKPD guard / relief (see architecture doc)
- Optional: refresh V3 threshold from DECISION when V3 did not act (fallback only)

### Implemented (2026-05-29)

| Item | Detail |
|------|--------|
| **4A distinct curves** | `AdvancedPredictionEngine.predictCurves()` — IOB / COB / UAM / ZT / hybrid |
| **4B safety composite** | `SafetyPredictionTerminalsResolver` — UAM terminal replaces insulin-only floor during meal rise |
| **4C reconcile** | `RISK_SAFETY_EARLY` at `trySafetyStart`; `RISK_SAFETY_RECONCILE` after `RISK_DECISION` (invariant 5 unchanged) |
| **5 export** | `adjustments.safety_risk` in `AIMI_Decisions.jsonl`; hormonitor schema **1.1.0** fields |
