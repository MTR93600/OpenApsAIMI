# 🏥 Health Connect Diagnostic Guide

## Current Issue: Physio shows "UNKNOWN (Conf: 0%)"

The JSON shows all zeros because Health Connect is **not returning data**:
```json
{
  "baseline": {
    "sleepDuration": { "count": 0, "mean": 0 },
    "hrvRMSSD": { "count": 0, "mean": 0 },
    "morningRHR": { "count": 0, "mean": 0 }
  }
}
```

---

## 🔍 Diagnostic Steps

### 1. Check Logcat for Diagnostic Messages

After installing the new APK, look for these logs:

```
PhysioRepository: Health Connect SDK Status: SDK_AVAILABLE
PhysioRepository: ✅ All required Health Connect permissions granted
  -- OR --
PhysioRepository: ⚠️ Missing Health Connect permissions: READ_SLEEP, READ_HEART_RATE_VARIABILITY

PhysioRepository: 📊 FETCH RESULT - Sleep: NULL (no data)
PhysioRepository: 📊 FETCH RESULT - HRV: 0 samples (empty - no HRV data in HC)
PhysioRepository: 📊 FETCH RESULT - RHR: 0 samples (empty - no morning HR data)
PhysioRepository: 📊 FETCH RESULT - Steps: 0 avg/day (no steps data)

PhysioRepository: ⚠️ FETCH SUMMARY: NO DATA from Health Connect!
```

---

### 2. Possible Causes & Solutions

| Symptom | Cause | Solution |
|---------|-------|----------|
| `Missing permissions: READ_SLEEP, READ_HRV` | Permissions not granted | Go to **Settings > Apps > AAPS > Health Connect** and grant all permissions |
| `SDK not available` | Health Connect not installed | Install **Health Connect** from Play Store |
| `Sleep: NULL, HRV: 0` even with permissions | No data source syncing to HC | Configure Samsung Health/Oura to sync to Health Connect (see below) |
| `SECURITY ERROR` | Permissions revoked | Re-request permissions from Health Connect |

---

### 3. Configure Data Sources

#### Samsung Health → Health Connect
1. Open **Samsung Health**
2. Go to **Settings** (hamburger menu)
3. Select **Health Connect** (formerly Connected Services)
4. Enable sync for: **Sleep, Heart rate, Steps**

#### Oura Ring → Health Connect
1. Open **Oura** app
2. Go to **Profile** (bottom right)
3. Select **3rd Party Apps**
4. Enable **Health Connect** integration
5. Grant all data types

#### Garmin Connect → Health Connect
1. Open **Garmin Connect**
2. Go to **Settings > Health & Wellness**
3. Check if Health Connect sync is available (varies by device)

---

### 4. Verify Data in Health Connect App

1. Open the **Health Connect** app directly
2. Go to **Data** tab
3. Check if recent entries exist for:
   - Sleep sessions
   - Heart rate
   - Steps

If data exists here but AAPS sees nothing → **Permission issue**
If no data here → **Source sync issue** (Samsung Health, Oura, etc.)

---

## 🆕 New Diagnostic Logs (v150126)

The new build adds these logs to help diagnose:

1. **SDK Status**: `Health Connect SDK Status: SDK_AVAILABLE`
2. **Permission Check**: Lists missing permissions explicitly
3. **Fetch Results**: Shows each data type separately
4. **Summary**: Clear message when no data retrieved

---

## 📋 Checklist

- [ ] Health Connect app installed
- [ ] AAPS has Health Connect permissions (Settings > Apps > AAPS > Health Connect)
- [ ] Samsung Health / Oura exports to Health Connect
- [ ] Recent sleep data exists (<48h)
- [ ] Data visible in Health Connect app
- [ ] Logcat shows `FETCH RESULT - Sleep: Xh` (not NULL)

---

## Expected Log Output When Working

```
PhysioManager: 🚀 Starting AIMI Physiological Manager (Enabled: true)
PhysioManager: 🔄 Triggering bootstrap update: Low confidence/No data  <-- NEW! Forces update if empty
PhysioRepository: Health Connect SDK Status: SDK_AVAILABLE  
PhysioRepository: 🔍 PERMISSIONS DIAGNOSTIC:
PhysioRepository:    Required (Central): [READ_SLEEP, READ_HEART_RATE_VARIABILITY, READ_HEART_RATE]
PhysioRepository:    Granted (System):   [READ_SLEEP, READ_HEART_RATE_VARIABILITY, READ_HEART_RATE]  <-- Verify these match!
PhysioRepository: ✅ All required Health Connect permissions granted for Physio
PhysioRepository: 🔄 Fetching physiological data (7d window)...
PhysioRepository: 📊 FETCH RESULT - Sleep: 7.2h
PhysioRepository: 📊 FETCH RESULT - HRV: 42 samples
PhysioRepository: 📊 FETCH RESULT - RHR: 7 samples  
PhysioRepository: 📊 FETCH RESULT - Steps: 8542 avg/day
PhysioRepository: ✅ Fetch completed in 245ms - Sleep=true, HRV=42, RHR=7, Steps=8542
```

When this works, Physio will show: `🏥 Physio: OPTIMAL (Conf: 85%)`
