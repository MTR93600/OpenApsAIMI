# OpenAPS AI MI: Use Cases & Scenarios

This guide documents common real-world scenarios handled by the AI MI module and how the system responds to each.

## 1. Acute Hyperglycemia (Rapid Rise)
**Scenario**: Patient consumes high-glycemic index food without a pre-bolus.
- **Engine**: AutoDrive V3 (MPC).
- **Behavior**: MPC detects 1.5 mg/dL/min velocity and 0.5 Ra. It ignores profile Basal and delivers a series of SMBs (e.g., 0.8U every 5 min) while increasing TBR.
- **Goal**: Bring BG back to target within 90 minutes.

## 2. Nocturnal Stability (Dawn Guard)
**Scenario**: Patient experiences a rise in BG at 4 AM due to hormonal activity.
- **Engine**: Dynamic Basal V2 + Dawn Guard.
- **Behavior**: System cross-references `hour == 4` and `bgVelocity > 0`. It proactively increases basal by 30% even if current BG is still 110 mg/dL.
- **Goal**: Prevent the "Dawn Phenomenon" spike before it happens.

## 3. Exercise Safety (Hypo Prevention)
**Scenario**: Patient starts intense aerobic exercise.
- **Engine**: Context Guard (Steps/HR).
- **Behavior**: `steps > 500` and `hr > 120` triggers "Exercise Mode". 
- **Action**: Safety Shield reduces `maxSMB` to 0.0 and caps `maxBasal` to 80% of profile.
- **Goal**: Avoid exercise-induced hypoglycemia by cutting insulin delivery proactively.

## 4. Hormonal Cycle (WCycle)
**Scenario**: Patient is in the Luteal phase (pre-menstrual).
- **Engine**: WCycle Learner.
- **Behavior**: WCycle identifies a historical 20% resistance in this phase.
- **Action**: Applies a `1.2x` multiplier to all scheduled insulin (Basal and SMB).
- **Goal**: Maintain TIR despite fluctuating insulin sensitivity.

## 💡 Validation Tests
Every use case is verified via the `AutoDrivePipelineTest.kt` suite using parameterized tests to simulate these physiological states.
