# WCycle Audit Map

## Executive Summary
The `wcycle` module (Women's Cycle) implements a bi-phasic hormonal adaptation layer (Follicular/Luteal) with automated phase estimation. It intercepts therapeutic decisions (Basal & SMB) to apply multipliers (`basalMultiplier`, `smbMultiplier`) based on cycle day, profile settings (PCOS/Endo/Thyroid), and adaptive learning (`WCycleLearner`). This audit confirms that WCycle is correctly integrated *downstream* of the decision logic but *upstream* of the final safety clamping.

## Dataflow & Call Sites

| Module | Function | Trigger (Call Site) | Input | Output | Side Effects |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Estimator** | `estimate()` | `WCycleAdjuster.getInfo()` | Prefs (Start date, Length) | `(Day, Phase)` | None |
| **Adjuster** | `getInfo()` | `DetermineBasal.ensureWCycleInfo()` | Phase, Defaults, Learned | `WCycleInfo` object | Reads Prefs |
| **Facade** | `infoAndLog()` | `DetermineBasal` (post-apply) | Applied multipliers | CSV Row | Writes `wcycle.csv` |
| **Learner** | `update()` | `DetermineBasal` (post-apply) | Ratio (Given/Prop) | Updates SQLite | Adapts future factors |
| **Decision** | `applySafetyPrecautions`| `finalizeAndCapSMB` | `smbProp`, `wCycleInfo` | `smbScaled` | Modifies SMB dose |
| **Decision** | `calculateTempBasal` | `determine_basal` | `rateProp`, `wCycleInfo` | `rateScaled` | Modifies Basal Rate |

## Execution Timeline (The Tick)

1.  **Prediction Engine:** Generates BG forecast.
2.  **Safety Check (`trySafetyStart`):** Evaluates LGS. *Exit if Safety Applied.*
3.  **Manual Modes (`tryManualModes`):**
    *   Calls `finalizeAndCapSMB(..., ignoreSafety=true)`
    *   -> `applySafetyPrecautions`
    *   -> **WCycle Applied** (Scaled) -> *proposed patch removes this for Manual*
    *   -> `capSmbDose` (Hard Limits)
    *   *Exit if Applied.*
4.  **Meal Advisor (`tryMealAdvisor`):**
    *   Similar flow to Manual Modes.
5.  **Autodrive (`tryAutodrive`):**
    *   Calls `calculateDynamicMicroBolus` or `pbolus`
    *   Calls `finalizeAndCapSMB` -> **WCycle Applied**
6.  **Global Fallback (SMB/Basal):**
    *   Calculates `smbToGive` / `rate`
    *   Calls `finalizeAndCapSMB` / `calculateTempBasal`
    *   -> **WCycle Applied**

## Gates & Priority Rules

*   **Safety Priority:** WCycle is applied *inside* `applySafetyPrecautions`.
    *   **Rule:** Critical Safety conditions (e.g., Hypo) force return `0.0` *before* WCycle logic is reached.
    *   **Verdict:** **SAFE**. WCycle cannot force insulin during a safety cut.
*   **Basal Floor:** WCycle multipliers are applied to the `rate`.
    *   **Rule:** `rate` is clamped to `[0.0, MaxSafe]`.
    *   **Verdict:** **SAFE**. No negative basal possible.
*   **Manual Mode Integrity:**
    *   **Risk:** Currently, WCycle scales Manual Mode Fixed Boluses (e.g., "Prebolus 1: 2.0U" becomes 2.4U or 1.8U).
    *   **Verdict:** **AMBIGUOUS**. While physiologically correct, "Manual" usually implies "Exact".
    *   **Fix:** The provided patch disables WCycle scaling for explicit Priority 1/2 actions (`ignoreSafetyConditions=true`).

## Logging Plan
*   **CSV:** `wcycle.csv` records all factors and decisions.
*   **Console:** `DetermineBasal` logs `reason`: `♀️ LUTEAL J21 | amp=1.00 ...` 
*   **UI:** Shows the icon and phase in the "Reason" field.

## Risks & Fixes
*   **Risk:** Double application of "Dawn Phenomenon" (Basal Profile + WCycle Dawn Boost).
    *   *Mitigation:* User education (Profile should be flat-ish if using WCycle).
*   **Risk:** Manual Boluses scaled by WCycle.
    *   *Fix:* See `WCYCLE_LOGGING_PATCH.diff`.
