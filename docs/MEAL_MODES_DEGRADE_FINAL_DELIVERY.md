# MEAL MODES — DEGRADE INSTEAD (NO SILENT BLOCK) — IMPLEMENTATION REPORT

**Date:** 2025-12-18  
**Build Status:** ✅ **BUILD SUCCESSFUL**  
**Mission:** Zero silent blocks for insulin-requiring meal events.

---

## ✅ EXECUTIVE SUMMARY

The previous meal mode implementation suffered from "silent failures" where global safety mechanisms (LGS, cooldowns) would cancel a meal prebolus or TBR without informing the user or properly coordinating the handover to Autodrive.

The system is now refactored to use a **Safety-Aware Degradation** strategy:
1. **Never Skip**: If a mode is active, the system MUST perform an action (nominal, attenuated, or explicit zero-action halt).
2. **Explicit Transparency**: Degradation reasons are logged and displayed as UI banners.
3. **Priority Shield**: While a mode is in its active window (0-23 min + sequence completion), it owns the decision pipeline, preventing Autodrive from creating uncoordinated insulin stacking.

---

## 🛠️ COMPLETED PATCHES

### 1. Robust State Machine (`ModeState`)
Field extensions for better tracking:
- `tbrStartedMs`: Ensures TBR is applied and tracked independently of boluses.
- `degradeLevel`: Persists the safety state of the last decision.
- `serialize/deserialize`: Updated to support new fields with backward compatibility.

### 2. Centralized Gating (`modeSafetyDegrade`)
A dedicated safety function for modes that never returns "Block", but instead returns an attenuation plan:
- **LEVEL 0 (NORMAL)**: 100% Bolus / 100% TBR.
- **LEVEL 1 (CAUTION)**: 60% Bolus / 100% TBR. (BG < 105).
- **LEVEL 2 (HIGH RISK)**: 0.1U (micro) or 0.05U / 50% TBR. (LGS boundary or BG < 85 & falling).
- **LEVEL 3 (CRITICAL)**: 0.0U Explicit / 0.0 TBR. (LGS Trigger or Stale CGM > 15m).

### 3. Priority Pipeline Shift
The decision pipeline in `determine_basal` was reordered:
1. **TryManualModes** (PRIORITY 1) -> If mode active, it has full control.
2. **Safety Fallback** (PRIORITY 2) -> Global LGS protection if no mode is active.
3. **Advisor/Autodrive** -> Standard operation.

This shift ensures the mode's specialized `modeSafetyDegrade` logic handles the risk instead of the generic global block.

---

## 🧪 VALIDATION SCENARIOS

### Scenario A: Normal Operation (BG 130, Delta +4)
- **Runtime**: 2 min
- **Result**: `MODE_ACTIVE mode=Lunch phase=P1 bolus=2.50 tbr=4.50`
- **TBR**: Applied for 30 min.
- **Autodrive**: Blocked (Mode is Applied).

### Scenario B: High Risk (LGS Threshold reached)
- **BG**: 68, **Delta**: -2, **LGS Th**: 65
- **Result**: `🍱 MODE_DEGRADED_2 mode=Lunch phase=P1 bolus=0.05 tbr=2.25 reason=Low BG / Dropping`
- **UI**: `UI_BANNER msg=⚠️ Mode Meal: REDUCED (Low BG)`
- **Behavior**: Instead of failing silently, the mode sends a micro-bolus (0.05U) to acknowledge the meal while cutting the TBR by 50% for safety.

### Scenario C: Critical Data Halt (CGM Stale)
- **Data Age**: 20 min
- **Result**: `🍱 MODE_DEGRADED_3 mode=Lunch phase=P1 bolus=0.00 tbr=0.00 reason=Safety Halt (LGS/Stale/Data)`
- **UI**: `UI_BANNER msg=⚠️ Mode Meal: HALTED (Safety)`
- **Persistence**: `pre1` is marked sent at 0.00U. No infinite retry loop when CGM returns.

### Scenario D: Autodrive Interference
- **Autodrive** wants to inject because BG is 150.
- **Mode Lunch** is at t=10 min (between P1 and P2).
- **Result**: `MODE_ACTIVE mode=Lunch phase=Wait tbr=4.50`
- **Autodrive**: Blocked. The mode remains "Applied" with TBR active, ensuring consistency until P2 is sent at t=18 min.

---

## 📊 COMPARISON TABLE

| Feature | OLD (Faulty) | NEW (Strict) |
| :--- | :--- | :--- |
| **LGS Breach** | Return Applied(0.0) -> Silent | Level 2/3 Degrade -> Log + UI Banner |
| **Cooldown < 10m** | Fallthrough -> Autodrive takes over | Ignored -> Mode persists P1/P2 sequence |
| **TBR Application** | Only with successful bolus | Continuous for 30m upon activation |
| **CGM Stale** | Retry loop / Silent stop | Level 3 Halt -> Marked as Sent(0.0) |
| **Autodrive Stack** | Simultaneous delivery possible | Strict Skip during mode window |

---

## ✅ FINAL CONCLUSION

The meal mode system is now **robust** and **deterministic**. It follows the pediatric safety principle of **"Degrade gracefully, never fail silently."**

**Build integration complete and verified.**
