# User Manual – OpenAPS AIMI

Welcome to AIMI (Adaptive Insulin Management Intelligence), the predictive engine of AndroidAPS that combines machine learning, physiological monitoring, and advanced safety systems to manage basal and SMB (Super Micro-Bolus). AIMI observes your glycemic history, boluses, steps/heart rate, and declared modes to dynamically adjust sensitivity, insulin duration of action, and micro-boluses, all while preserving historical OpenAPS safety features.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OpenAPSAIMIPlugin.kt†L95-L175】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L2480-L2648】

AIMI is not a black box: think of it as a co-pilot. The cleaner your data (up-to-date profile, meal logging, closing night loops), the more accurately AIMI anticipates and stabilizes your blood glucose.

---

## Table of Contents
1. [Installation and Activation](#installation-and-activation)
2. [General Principles and Operation Check](#general-principles-and-operation-check)
3. [🔧 General Settings](#-general-settings)
4. [⚙️ Basal & SMB Regulation](#️-basal--smb-regulation)
5. [🧠 Adaptive Intelligence (ISF, PeakTime, PK/PD)](#-adaptive-intelligence-isf-peaktime-pkpd)
6. [💡 Modes & Meal Detection](#-modes--meal-detection)
7. [💪 Exercise & Safety Rules](#-exercise--safety-rules)
8. [🌙 Night Mode & Night Growth](#-night-mode--night-growth)
9. [❤️ Heart Rate & Steps Integration (Wear OS)](#️-heart-rate--steps-integration-wear-os)
10. [♀️ WCycle – Menstrual Cycle Monitoring](#️-wcycle--menstrual-cycle-monitoring)
11. [Tips for Quick Adjustments](#tips-for-quick-adjustments)
12. [Troubleshooting and Log Interpretation](#troubleshooting-and-log-interpretation)
13. [Educational Summary](#educational-summary)

---

## Installation and Activation
1. **Activate the plugin** from *Configuration ▶️ Plugins ▶️ APS* and select **OpenAPS AIMI**. AIMI automatically verifies that your pump supports temporary basals.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OpenAPSAIMIPlugin.kt†L226-L238】
2. **Restart the loop**: Upon startup, AIMI reloads your past variable sensitivities and installs its Kalman/PK-PD calculator.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OpenAPSAIMIPlugin.kt†L140-L175】
3. **Authorize Permissions**: If activating steps/HR, ensure your Wear OS watch synchronizes correctly to AAPS (see section ❤️).
4. **Check Status**
   - The OpenAPS screen shows *AIMI Algorithm* and the date of the last calculation (`lastAPSRun`).【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OpenAPSAIMIPlugin.kt†L162-L165】
   - Logs contain `AIMI+` reasons when the adaptive basal triggers a kicker or a micro-resume.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/AIMIAdaptiveBasal.kt†L79-L112】
   - The `SMB`/`Basal` columns in the status show the WCycle or NightGrowth multipliers when active.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L2493-L2531】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L417-L444】

---

## General Principles and Operation Check
- **Full Loop**: AIMI retrieves the `GlucoseStatusAIMI`, calculates a basal plan via `BasalPlanner`, applies `AIMIAdaptiveBasal` for plateaus, and adjusts SMBs via PK/PD and adaptive ISF.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/basal/BasalDecisionEngine.kt†L25-L113】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/pkpd/PkPdIntegration.kt†L27-L109】
- **Continuous Learning**: PK/PD parameters (DIA and Peak Time) are updated when sufficient IOB is available, unless sport or delayed-absorption meals are detected.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/pkpd/AdaptivePkPdEstimator.kt†L20-L52】
- **Useful Logs**: `rT.reason` includes triggers (plateau kicker, NGR, WCycle). AIMI CSVs (`AAPS/oapsaimi*.csv`) record every decision for later analysis.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L205-L276】

---

## 🔧 General Settings
These parameters define the physiological basis used by all AIMI modules.

### 🔹 `OApsAIMIMLtraining`
- **Default Value**: `false` (off).【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L123-L136】
- **Purpose**: Allow training of the local SMB model (`oapsaimiML_records.csv` file).【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L205-L223】
- **Effect**: In training mode, AIMI records your loops to fine-tune the `neuralnetwork5` network after accumulating at least 60 min of data.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L236-L244】
- **Adjust if**:
  - **Frequent Hypos**: Leave off to identify the source before re-training.
  - **Frequent Hypers**: Turn on to learn your patterns, but monitor safety (SMB always capped).
  - **Variability**: Only train after stabilizing profiles (at least 3-4 days of homogeneous data).

### 🔹 `OApsAIMIweight`, `OApsAIMICHO`, `OApsAIMITDD7`
- **Default Values**: 50 kg, 50 g, 40 U respectively.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L67-L69】
- **Purpose**: Provide physiological limits used to initialize the Kalman ISF filter and PK/PD if your history is empty.
- **Effect**: An underestimated weight/TDD makes the ISF too aggressive; a too-low average CHO will more often detect "fatty" meals.
- **Adjust**:
  - **Hypos**: Slightly increase `OApsAIMIweight` or `OApsAIMITDD7` towards real values → ISF softens.
  - **Hypers**: Adjust `OApsAIMICHO` towards your real intakes to keep meal models realistic.
  - **Variability**: Harmonize these parameters with your profile (same units as daily reports).

### 🔹 `AimiUamConfidence`
- **Default Value**: `0.5` (medium confidence).【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L144-L146】
- **Purpose**: Weight the "UAM" learning when the detection of unannounced meals is reliable.
- **Effect**: The higher the confidence, the less the dynamic sensitivity algorithm (IsfAdjustmentEngine) deviates from the profile.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/ISF/IsfAdjustmentEngine.kt†L13-L36】
- **Adjust**:
  - **Post-UAM Hypos**: Increase (0.6–0.8) to limit ISF reduction.
  - **Prolonged Unannounced Hypers**: Decrease (0.3–0.4) so the ISF adapts more quickly.
  - **Variability**: Leave at default until the engine accumulates enough Kalman trust.

### 🔹 `OApsAIMIEnableBasal`
- **Default Value**: `false`.【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L123-L136】
- **Purpose**: Activate a specific (legacy) predictive basal. Currently unused (commented): leave off unless specifically requested.

### 🔹 `OApsAIMIautoDrive`
- **Default Value**: `false`.【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L130-L136】
- **Purpose**: Activate autoDrive, meaning the automatic use of mode factors (meals, auto-boluses) and the combined profile (`combinedDelta`).
- **Effect**: Applies the `autodrivePrebolus`, `autodrivesmallPrebolus` factors, limits basal via `autodriveMaxBasal`, and adjusts the `combinedDelta`/`AutodriveDeviation` triggers.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L105-L114】
- **Adjust**: Start OFF, then activate when meal modes are correctly set.

### 🔹 AutoDrive Target Parameters (`OApsAIMIAutodriveBG`, `OApsAIMIAutodriveTarget`)
- **Default Values**: 90 and 70 mg/dL.【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L83-L86】
- **Effect**: Serve as a reference for detecting minimal deviations and activating autoDrive micro-preboluses.
- **Tip**: Keep `AutodriveBG` above the actual target (≈ 90–100) to allow AIMI to absorb small rises without over-correcting.

---

## ⚙️ Basal & SMB Regulation
AIMI simultaneously controls temporary basal (kickers, anti-stall) and the intensity of SMBs through its parameters.

### Global SMB Parameters
| Parameter | Default Value | Role | Hypo Adjustment | Hyper Adjustment | Variability |
|-----------|------------------|------|------------------|-------------------|-------------|
| `OApsAIMIMaxSMB` | 1.0 U | Standard SMB cap | ↓ to 0.7–0.8 if hypos after SMB | ↑ up to 1.2 if high post-prandials | combine with meal factors |【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L64-L66】|
| `OApsAIMIHighBGMaxSMB` | 1.0 U | SMB cap when AIMI detects a high plateau | same | ↑ (1.5) to correct a plateau >180 mg/dL faster | Monitor NGR |【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L64-L66】|
| `autodriveMaxBasal` | 1.0 U/h | autoDrive basal cap | ↓ if night hypos | ↑ (×1.2) if hyper plateau in autoDrive | Linked to anti-stall |【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L105-L114】|
| `meal_modes_MaxBasal` | 1.0 U/h | basal cap during meal modes | same | ↑ (×1.3) if tolerated more during long meals | Keep > profile basal |【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L113-L115】|

**Tip**: SMB/basal caps are applied after all safety checks (`applyMaxLimits`).【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L296-L308】

### SMB / Mode Intervals
The preferences `OApsAIMIHighBGinterval`, `OApsAIMImealinterval`, etc., define the minimum frequency (per 5 min) at which AIMI can re-propose an SMB in the corresponding mode (default 3 × 5 min = 15 min).【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L75-L82】
- **Hypos**: Increase the interval (4–5) to space out SMBs.
- **Prolonged Hypers**: Reduce to 2 (10 min) only for HighBG.

### AIMIAdaptiveBasal (Plateaus, Micro-resumes)
- **High Threshold** `OApsAIMIHighBg` = 180 mg/dL: Activates kicks when a high plateau is identified.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L135-L143】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/AIMIAdaptiveBasal.kt†L62-L112】
- **Plateau Band** `OApsAIMIPlateauBandAbs` = ±2.5 mg/dL/5 min: The wider the band, the more AIMI tolerates variations before kicking.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L135-L143】
- **Max Multiplier** `OApsAIMIMaxMultiplier` = ×1.6: Limits the temporary basal during a plateau.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L135-L143】
- **Kicker step/min** (`OApsAIMIKickerStep`, `OApsAIMIKickerMinUph`, `OApsAIMIKickerStartMin`, `OApsAIMIKickerMaxMin`) control the intensity and duration of the kicker.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L138-L140】【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L93-L98】
- **Micro-Resume** (`OApsAIMIZeroResumeMin`, `OApsAIMIZeroResumeFrac`, `OApsAIMIZeroResumeMax`): Relaunches a low basal after a pause ≥10 min to avoid post-hypoglycemia rises.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L141-L142】【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L96-L97】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/AIMIAdaptiveBasal.kt†L79-L112】
- **Anti-Stall** `OApsAIMIAntiStallBias` (10%) and `OApsAIMIDeltaPosRelease` (Δ+1 mg/dL) define the minimum overdrive during a stable plateau.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L142-L143】

**Practical Decision Tree:**

* **If plateau >180 mg/dL and Δ≈0 → increase `OApsAIMIKickerStep` (+0.05) to correct faster.**
* **If hypos after basal resume → reduce `OApsAIMIZeroResumeFrac` (0.2) or increase `ZeroResumeMin` (15 min).**
* **If slow rise despite kicks → increase `OApsAIMIMaxMultiplier` (1.8 max) and check `KickerMinUph`.**

### Hypoglycemia Safety
AIMI applies a guardrail that blocks SMBs if BG approaches the hypo threshold with a negative slope, taking into account an additional margin based on the rate of descent.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L400-L413】

---

## 🧠 Adaptive Intelligence (ISF, PeakTime, PK/PD)

### Dynamic PK/PD
- **Activation**: `OApsAIMIPkpdEnabled` (OFF by default).【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L130-L136】
- **Initial Parameters** (`OApsAIMIPkpdInitialDiaH`, `OApsAIMIPkpdInitialPeakMin`) define the DIA (20 h) and peak (40 min).【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L71-L80】
- **Limits & Speed** (`OApsAIMIPkpdBoundsDia*`, `OApsAIMIPkpdBoundsPeak*`, `OApsAIMIPkpdMax*`) limit daily learning.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L71-L78】
- **Persistent State** (`OApsAIMIPkpdStateDiaH`, `OApsAIMIPkpdStatePeakMin`) stores the last learned DIA/peak.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L79-L80】
- **Effect**: When active, AIMI fuses the profile/TDD ISF with the PK/PD estimate and applies a *pkpdScale* linked to the tail fraction of IOB.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/pkpd/PkPdIntegration.kt†L27-L82】
- **Adjustments**:
  - **Late Hypos**: Reduce `OApsAIMIPkpdMaxDiaChangePerDayH` to slow down DIA lengthening.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L77-L78】
  - **Post-Meal Hypers**: Lower `OApsAIMIPkpdBoundsPeakMinMax` (e.g., 180) to favor shorter peaks.
  - **Unstable Data**: Temporarily turn off `PkpdEnabled` and revert to initial values (reset via preferences).

### ISF Fusion & Rapid Blending
- **`OApsAIMIIsfFusionMinFactor` / `MaxFactor`**: Min/max factors applied to the profile ISF (0.75–2.0 by default).【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L81-L83】
- **`OApsAIMIIsfFusionMaxChangePerTick`**: Maximum change ±40% per 5 min tick.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L81-L83】
- **Effect**: Fusion mixes TDD/PkPd ISF and rapid Kalman via `IsfBlender`, respecting a smoothing of ±5% per cycle.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/ISF/IsfBlender.kt†L5-L45】

### Adaptive ISF Adjustment
`IsfAdjustmentEngine` uses Kalman BG and an EMA of TDD to recalculate the target ISF (logarithmic law), limiting change to ±5% per cycle and ±20% per hour.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/ISF/IsfAdjustmentEngine.kt†L6-L49】
- **Hypos**: Reduce `AimiUamConfidence` or turn off PK/PD if ISF drops too quickly.
- **Hypers**: Ensure `OApsAIMIIsfFusionMaxFactor` remains ≥1.6.

### Intelligent SMB Damping
The parameters `OApsAIMISmbTailThreshold`, `OApsAIMISmbTailDamping`, `OApsAIMISmbExerciseDamping`, `OApsAIMISmbLateFatDamping` control the reduction of SMBs at the end of action, after exercise, or fatty meals.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L84-L87】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/pkpd/SmbDamping.kt†L4-L77】
- **Advice**:
  - If you stay high at the end of action → increase `SmbTailThreshold` (0.35) or increase `SmbTailDamping` (0.6).
  - If hypos after sport → reduce `SmbExerciseDamping` (0.4) to cut harder.

### Dynamic PeakTime
The `calculateDynamicPeakTime` calculation combines IOB, future activity, steps, HR, and sensor to adjust the Peak Time between 35 and 120 min.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L2533-L2645】
- **Night Hypos**: If the peak is too short, increase `OApsAIMIcombinedDelta` (1.5) to make AIMI more cautious in autoDrive.
- **Post-Prandial Hypers**: Ensure steps/HR are synchronized correctly to allow a shortened peak when active.

---

## 💡 Modes & Meal Detection
AIMI modulates its SMBs based on your temporary modes and dedicated factors.

### Daily Factors
`OApsAIMIMorningFactor`, `OApsAIMIAfternoonFactor`, `OApsAIMIEveningFactor` (default 50%) weight predicted SMBs according to the time slot.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L88-L101】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L236-L245】
- **Morning Hypos**: Reduce MorningFactor (40%).
- **Evening Hypers**: Increase EveningFactor (60–70%).

### Specific Meal Modes
Each mode has a trio *(prebolus1, prebolus2, factor %)* and an interval:
- **Breakfast**: `OApsAIMIBFPrebolus` (2.5 U), `OApsAIMIBFPrebolus2` (2.0 U), `OApsAIMIBFFactor` (50%), interval 15 min.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L95-L101】【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L81-L82】
- **Lunch / Dinner**: Analogous parameters (`Lunch*`, `Dinner*`).【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L98-L101】【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L76-L79】
- **Snack / HighCarb / Generic Meals**: `OApsAIMISnackPrebolus`, `OApsAIMIHighCarbPrebolus`, etc.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L120-L123】
- **Hyper Mode**: `OApsAIMIHyperFactor` (60%) reinforces SMBs if BG>180.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L100-L103】

**Tips**:
- Use `OApsAIMImealinterval` (15 min default) to avoid SMBs too close together during a prolonged meal.【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L75-L82】
- `OApsAIMIMealFactor` weights SMBs even without an explicit mode (useful for sudden meals).【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L91-L101】

### AutoDrive Prebolus
`OApsAIMIautodrivePrebolus` (1 U) and `OApsAIMIautodrivesmallPrebolus` (0.1 U) serve as limits for automatic micro-preboluses when `autoDrive` is active.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L105-L107】

### Note Management & Meal Detection
AIMI scans your notes (sleep, sport, meal…) to activate modes if you forget to click the button, and records them in the SMB logs.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L2656-L2678】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L311-L360】

---

## 💪 Exercise & Safety Rules

### Physiological Switches
- **`OApsAIMIpregnancy`**, **`OApsAIMIhoneymoon`**: Activate specific adjustments in `BasalDecisionEngine` (e.g., increase basal if delta>0 during pregnancy).【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L123-L136】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/basal/BasalDecisionEngine.kt†L53-L63】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/basal/BasalDecisionEngine.kt†L461-L463】
- **`OApsAIMIforcelimits`**: Force basal/SMB limits (used by some profiles). Leave OFF unless clinical indication.

### Sport Detection & SMB Safety
- `isSportSafetyCondition` rules interrupt SMBs when steps/HR indicate intense activity, or when the target is elevated (>140).【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L342-L350】
- `applySpecificAdjustments` halves SMBs if you are in sleep/snack/prolonged low activity.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L353-L360】

**Safety Decision Tree:**

* **If hypos after sport → activate `OApsAIMIEnableStepsFromWatch` + reduce `SmbExerciseDamping`.**
* **If hypos during pregnancy → reduce `OApsAIMIMaxMultiplier` and verify `pregnancy` is ON.**
* **If hypers during honeymoon → activate `OApsAIMIhoneymoon` to allow more aggressiveness.**

---

## 🌙 Night Mode & Night Growth

### Classic Night Mode
- **Switch** `OApsAIMInight` (OFF by default).【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L127-L129】
- **Sleep Factor** `OApsAIMIsleepFactor` (60%) and interval `OApsAIMISleepinterval` (15 min) modulate SMBs during the night.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L102-L103】【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L81-L82】

### Night Growth Resistance (NGR)
This module manages growth hormone peaks in children/adolescents.
- **Activation**: Automatic for <18 years old or via `OApsAIMINightGrowthEnabled` (ON by default).【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L133-L136】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L417-L444】
- **Key Parameters**:
  - `OApsAIMINightGrowthAgeYears` (14 years), windows `OApsAIMINightGrowthStart`/`End` (22:00–06:00).【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L87-L90】【F:core/keys/src/main/kotlin/app/aaps/core/keys/StringKey.kt†L56-L61】
  - `OApsAIMINightGrowthMinRiseSlope` (≥5 mg/dL/5 min), `MinDuration`, `MinEventualOverTarget` define detection.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L128-L132】【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L87-L90】
  - SMB/Basal multipliers and IOB maximums (`NightGrowthSmbMultiplier`, etc.).【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L128-L132】
- **Operation**: NGR monitors the maximum slope, confirms the event, and applies multipliers until a controlled DECAY state.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/NightGrowthResistanceMonitor.kt†L13-L198】

**Tips:**
- If persistent night hypers → increase `NightGrowthSmbMultiplier` (1.3) and `NightGrowthBasalMultiplier` (1.2).
- If hypos at the end of the episode → reduce `NightGrowthMaxSmbClamp` or `MaxIobExtra`.
- For a younger child, reduce `MinRiseSlope` (3–4) to detect changes earlier.

---

## ❤️ Heart Rate & Steps Integration (Wear OS)
- **Activation**: `OApsAIMIEnableStepsFromWatch` (OFF by default).【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L123-L129】
- **Effects**:
  - Steps in the last 5–180 min (`recentSteps*`) and average HR 5/60/180 min are used to adjust PeakTime, modulate SMBs (sport), and decide on basal resumes.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L848-L911】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L2539-L2645】
  - In case of intense activity (>1000 steps and HR>110), AIMI lengthens the peak (×1.2) and limits SMBs.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L2616-L2626】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L342-L350】
  - At rest (steps<200, HR<50), the peak is shortened (×0.75) to avoid delayed action.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L2618-L2626】

**Tips:**
- Verify that the watch transmits correctly every 5 min (otherwise the values will remain null, and AIMI will not make adjustments).
- In case of hypos during activity, reduce `SmbExerciseDamping` or temporarily deactivate the option.

---

## ♀️ WCycle – Menstrual Cycle Monitoring
AIMI can adapt basal and SMB based on the phase of the menstrual cycle.

### Activation & Modes
- **`OApsAIMIwcycle`**: Activates the module (OFF by default).【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L130-L134】
- **Tracking Mode**: `OApsAIMIWCycleTrackingMode` (`FIXED_28`, `CALENDAR_VARIABLE`, etc.).【F:core/keys/src/main/kotlin/app/aaps/core/keys/StringKey.kt†L56-L59】
- **Physiological Parameters**: Contraceptive, thyroid status, Verneuil affect the amplitude of multipliers.【F:core/keys/src/main/kotlin/app/aaps/core/keys/StringKey.kt†L56-L59】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/wcycle/WCycleTypes.kt†L1-L39】
- **Min/Max Clamp** (`OApsAIMIWCycleClampMin` 0.8, `ClampMax` 1.25) limit the applied scale.【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L124-L126】
- **Shadow/Confirm Options**:
  - `OApsAIMIWCycleShadow` maintains calculations without applying them (observation mode).【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L132-L135】
  - `OApsAIMIWCycleRequireConfirm` requires confirmation before applying a change.【F:core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt†L132-L135】

### Operation
- `ensureWCycleInfo()` queries `WCycleFacade` with your preferences and returns the phase, multipliers, and a `reason` text inserted into the logs.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L2493-L2517】
- `updateWCycleLearner` adjusts the learned multipliers while respecting `ClampMin/Max`.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L2521-L2531】
- Base values follow `WCycleDefaults` (e.g., +12% basal in the luteal phase).【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/wcycle/WCycleTypes.kt†L18-L38】

**Tips:**
- Define the average duration (`OApsAIMIWCycleAvgLength`, 28 d) and start day (`OApsAIMIwcycledateday`).【F:core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt†L86-L87】【F:core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt†L124-L126】
- With hormonal contraception, the amplitude is automatically reduced (×0.4–0.5).【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/wcycle/WCycleTypes.kt†L23-L30】

---

## Tips for Quick Adjustments
| Situation | Suggested Adjustment | Related Preference |
|-----------|----------------------|--------------------|
| Hypo post-SMB | ↓ `OApsAIMIMaxSMB`, ↑ `OApsAIMISmbTailDamping` | SMB & PK/PD |
| Night Hypos | ↑ `OApsAIMIZeroResumeMin`, ↓ `NightGrowthBasalMultiplier` | Basal & Night |
| Hyper Post-Meal | ↑ meal factors (60–70%), ↓ `OApsAIMIPkpdBoundsPeakMinMax` | Modes & PK/PD |
| Flat Plateau Hyper | ↑ `OApsAIMIKickerStep`, check `HighBGMaxSMB` | Adaptive Basal |
| High Variability | Stabilize weight/TDD, turn off `PkpdEnabled`, enable `Shadow` WCycle | General & WCycle |

### Daily Mini Decision Tree

* **If you remain >180 mg/dL despite SMB → check HighBG mode: increase `HighBGMaxSMB` and `HyperFactor`.**
* **If descent is too rapid after autoDrive → decrease `autodrivePrebolus` and increase `AutodriveDeviation` (1.5).**
* **If trending high during activity → activate steps/HR monitoring and reduce `SmbExerciseDamping` to retain some SMB.**

---

## Troubleshooting and Log Interpretation
1. **Read `rT.reason`**: Each cycle concatenates the reasons (`plateau kicker`, `WCycle`, `NGR`). Look for `AIMI+` phrases to see adaptive actions.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/AIMIAdaptiveBasal.kt†L79-L112】【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt†L2493-L2531】
2. **AIMI CSVs**: `_records.csv` contains all variables (steps, TDD, ISF). Useful for checking if your modes or steps were correctly accounted for.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L205-L276】
3. **PK/PD is no longer updating**: Verify that `PkpdEnabled` is ON and that you are not in exercise mode (flag cuts off learning).【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/pkpd/AdaptivePkPdEstimator.kt†L20-L38】
4. **Reverting to Defaults**: Each key can be reset from the menu (default values listed above). If you want a complete reset, turn off `PkpdEnabled`, delete the `oapsaimi*_records.csv` files, then reactivate.
5. **No SMB**: Check the `isCriticalSafetyCondition` safeguards (BG<target, negative delta, etc.) and the `maxIob`/`maxSMB` caps.【F:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OapsAIMIsmb.kt†L296-L339】

---

## Educational Summary
AIMI is an adaptive co-pilot:
- It observes your BG, efforts, and modes to adjust ISF, Peak Time, and SMBs.
- Its guardrails (plateau kicker, NGR, SMB damping, sport safety) avoid extremes while allowing learning to evolve.
- Letting AIMI accumulate consistent data (up-to-date profile, meal announcements, reliable steps/HR) maximizes its performance. Every parameter is adjustable to reflect your reality, but only change one setting at a time to observe the impact in the logs.

Continue to partner with AIMI: the more stable data you provide, the more it will refine its predictions and keep your BG on target.