# 🧪 Quick Test Guide - Meal Advisor SMB Fix

**Version**: 1.0  
**Date**: 2025-12-19  
**Tester**: MTR

---

## 📸 Test Scenario

### Prerequisites
✅ APK installed with fix  
✅ Meal Advisor configured  
✅ No recent bolus (<45 min)  
✅ BG ≥ 60 mg/dL

---

## 🎯 Test Steps

### Step 1: Take Photo
1. Open Meal Advisor
2. Take photo of meal
3. Wait for carbs estimation
4. **Confirm** the estimation

### Step 2: Wait for Execution
1. Wait for next APS cycle (~5 min)
2. Check AIMI status screen

### Step 3: Verify Results

**Expected Outcome**:

| Field | Before Fix | After Fix |
|-------|-----------|-----------|
| **Mode** | Meal Advisor | Meal Advisor ✅ |
| **TBR demandé** | 10 U/h (714%) | 10 U/h (714%) ✅ |
| **SMB demandé** | ❌ EMPTY | ✅ **VALUE SHOWN** |
| **SMB injecté** | ❌ EMPTY | ✅ **VALUE SHOWN** |

### Step 4: Check Logs

Navigate to AIMI Debug Logs and search for:

```
ADVISOR_CALC carbs=XX net=Y.Y delta=Z.Z modesOK=true
```

**Key Points**:
- `carbs`: Should match your estimation
- `net`: Calculated bolus (after IOB/basal coverage)
- `delta`: Current BG trend (can be negative!)
- `modesOK`: Should be `true`

---

## ✅ Success Criteria

The fix is working if:

1. ✅ **SMB is sent** even when:
   - BG stable (delta ≈ 0)
   - BG falling slowly (delta < 0)
   
2. ✅ **Both TBR and SMB** are active simultaneously

3. ✅ **Log shows** `ADVISOR_CALC` with delta value

4. ✅ **UI shows** SMB request and delivery

---

## ❌ Failure Scenarios

The fix is NOT working if:

1. ❌ SMB still empty when delta ≤ 0
2. ❌ Only TBR shown, no SMB
3. ❌ Log shows `ADVISOR_SKIP reason=modesCondition_false`

**If failure**: Check that no legacy meal mode is active (<30 min).

---

## 📊 Test Matrix

| Scenario | Delta | Expected SMB | Expected TBR |
|----------|-------|--------------|--------------|
| Rising BG | +6 mg/dL/5min | ✅ YES | ✅ YES |
| Stable BG | ±0 mg/dL/5min | ✅ YES (NEW) | ✅ YES |
| Falling BG | -2 mg/dL/5min | ✅ YES (NEW) | ✅ YES |
| Refractory | Any | ❌ NO | ❌ NO |
| BG < 60 | Any | ❌ NO | ❌ NO |

---

## 📝 Report Template

After testing, fill this:

```
Test Date: ___________
BG at test: _____ mg/dL
Delta: _____ mg/dL/5min
Carbs estimated: _____ g
IOB at test: _____ U

Results:
□ SMB requested: _____U
□ SMB delivered: _____U
□ TBR requested: _____U/h for 30min
□ Log shows ADVISOR_CALC: YES / NO

Status: ✅ PASS / ❌ FAIL

Notes:
_________________________________
_________________________________
```

---

## 🎯 Next Steps

**If PASS**: 
- ✅ Mark as validated
- ✅ Update `MEAL_ADVISOR_BUG_FIX_DELTA.md`
- ✅ Share results with team

**If FAIL**:
- ❌ Capture screenshot
- ❌ Export logs
- ❌ Report to developer

---

**Happy Testing!** 🚀
