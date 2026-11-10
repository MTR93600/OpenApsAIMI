# Dexcom ONE+ — user guide (OpenApsAIMI)

**Language:** English  
**Branch / status:** `feature/dexcom-oneplus-native` — native Real BLE exists behind eng pref; **not** production-ready until you confirm on device.  
**Related:** [product fiche](DEXCOM_ONEPLUS_NATIVE_PLUGIN_PRODUCT.md) · [dev onboarding](DEXCOM_ONEPLUS_DEV_ONBOARDING.md) · [changelog](DEXCOM_ONEPLUS_CHANGELOG.md) · [merge constraint](MERGE_CONSTRAINT_DEXCOM_ONEPLUS.md)

Do **not** treat this guide as “fully working” until you have confirmed behaviour on a real phone and sensor.

---

## 1. What this fork aims to provide

| Path | What it is | Official Dexcom app required? |
|------|------------|-------------------------------|
| **Native (target)** | AAPS owns Bluetooth to the ONE+ sensor (`DexcomOnePlusPlugin`, IntKey **446**) | **No** — pair and ~30 min warm-up inside AAPS |
| **Interim — xDrip Direct** | xDrip talks BLE; AAPS uses **xDrip** as BG source | No (xDrip is the collector) |
| **Interim — Notification reader** | AAPS reads BG from Dexcom app notifications | Yes (official / regional One+ app posting notifications) |

**BYODA** (`DexcomPlugin`, IntKey **440**) is for patched G6/G7 apps — it is **not** the One+ native path and does **not** replace One+ BLE in AAPS.

---

## 2. Native path (in AAPS) — when available

### 2.1 Enable the plugin

1. Open **Config Builder** → **BG Source**.
2. Select **Dexcom ONE+** (short name **ONE+**).
3. Disable any other BG source that would also try to own the sensor link (see §5).

Plugin registration: `DexcomOnePlusPlugin` @ `@IntKey(446)`.  
Driver module: `:plugins:dexcom_oneplus` (`app.aaps.plugins.dexcomoneplus`).  
BG tag in DB / AIMI: `SourceSensor.DEXCOM_ONEPLUS_NATIVE` → `"AAPS-DexcomOnePlus"`.

### 2.2 Pairing — when to enter the code

Enter the **4-digit pairing code as soon as you apply the sensor** (code is on the applicator).  
Do **not** wait until after the ~30 minute warm-up — warm-up starts only after AAPS has connected / started the session.

Recommended order (also shown on the Start screen in AAPS):

1. Apply the sensor on your body.
2. Enter the pairing code **now**.
3. Enable engineering **Use Real BLE skeleton** in ONE+ plugin settings.
4. Stop the Dexcom app and xDrip (single Bluetooth master).
5. Allow Bluetooth → Scan → select sensor → enter code → **Connect / follow**.
6. Stay on the warm-up screen (~30 minutes) — no loop glucose yet.
7. When warm-up finishes and the sensor reports ready, glucose appears in AAPS Overview.

No official Dexcom app is required for this path. Keep the official app **stopped** so it does not steal the BLE master role. Returning to the Dexcom app afterward is **not guaranteed** — prefer a spare sensor for testing.

### 2.3 Warm-up UI (~30 minutes)

During warm-up you should see:

- Clear “sensor starting / warming up” state  
- **Countdown** of remaining time (~30 min) and/or estimated end time  
- BLE link state (connected / reconnecting)  
- Explicit message that there is **no loop BG yet** (no fake placeholder glucose)

When warm-up finishes, the UI should transition to “active” and the first real BG should appear.  
Warm-up UI is present; countdown may use a local ~30 min fallback until the sensor reports remaining time.

### 2.4 Current reality (device not yet confirmed)

- Native Start / warm-up UI and Real BLE path exist; default remains **Stub** until you enable **Use Real BLE skeleton**.
- Treat native BG as **experimental** until you confirm `DEXCOM_ONEPLUS_BG: insert complete` on your phone + sensor.
- Prefer a **spare** sensor if you may still need the Dexcom app afterward.

---

## 3. Interim paths (until native BLE is confirmed)

### 3.1 Recommended interim: xDrip Direct → AAPS xDrip source

1. In **xDrip+**, configure **Dexcom ONE+ / G7 Direct** (sensor BLE owned by xDrip).
2. In AAPS **Config Builder** → BG Source → **xDrip+** (or equivalent xDrip follower).
3. Follow xDrip’s own battery / Bluetooth guidance for your phone.
4. Keep AAPS **Dexcom ONE+ (native)** and the official Dexcom app **off** as BG/BLE masters.

### 3.2 Notification reader (official app notifications)

Use when you intentionally run a Dexcom One+ / Dexcom One app that posts glucose notifications:

1. Config Builder → BG Source → **Notification reader**.
2. Grant notification access to AAPS.
3. Packages remapped for One+ tagging (Phase A):

| App package | Sensor tag in AAPS |
|-------------|--------------------|
| `com.dexcom.d1plus` | `AAPS-DexcomOnePlus` |
| `com.dexcom.dexcomone` | `AAPS-DexcomOnePlus` |

This path is fragile (depends on notification text) and still requires the **official app** as BLE master — it is **not** the native AAPS path.

---

## 4. OEM checklist (v1 targets: Pixel / Samsung)

Apply before expecting a stable Direct or native link. Exact OEM menus vary by OS version.

### Pixel (6 / 7 / 8 — Q9)

- [ ] Bluetooth **ON**
- [ ] AAPS (and xDrip if used): **Battery → Unrestricted**
- [ ] Disable battery optimisation for the collector app
- [ ] Keep phone awake long enough for first pair / warm-up
- [ ] Avoid “Adaptive connectivity” experiments that kill BLE if you see drops

### Samsung (Galaxy S22–S24 — Q9)

- [ ] Bluetooth **ON**
- [ ] **Device care → Battery** → AAPS (and xDrip if used) → **Unrestricted**
- [ ] Put AAPS / xDrip in **Never sleeping apps** (or equivalent)
- [ ] Turn off aggressive **put unused apps to sleep** for the collector
- [ ] After OS updates, re-check battery restrictions (Samsung often resets them)

Generic phones: same ideas — unrestricted battery, no OEM sleep kill, Bluetooth always available. v1 support focus remains Pixel + Samsung S22–S24.

---

## 5. Exclusivity (native path)

Product decisions (fiche §0): native plugin is the **sole BLE master**; xDrip Direct is mutually exclusive while native is on.

| Other app / source | While **native Dexcom ONE+** is ON |
|--------------------|-------------------------------------|
| Official Dexcom One+ / One app | **Do not** run as BLE master — expect connection failures |
| xDrip Direct / Juggluco Direct | **Do not** use in parallel — one master only |
| BYODA G6/G7 | Unrelated packs; do not use as One+ source |
| Notification reader + official app | Fallback only when native is **OFF** |
| xDrip as AAPS BG source | Fallback only when native is **OFF** |

If native fails to connect: disable the One+ native plugin, confirm GATT is down, then switch to an interim path (§3).

---

## 6. Troubleshooting

| Symptom | What to check |
|---------|----------------|
| Plugin missing in Config Builder | Branch includes `:plugins:dexcom_oneplus`; DI `@IntKey(446)`; rebuild app |
| Plugin ON but no BG | Enable eng **Use Real BLE skeleton**; stop Dexcom/xDrip; complete warm-up; check logcat `DEXCOM_ONEPLUS_BG: insert complete`. Or use interim xDrip / notification reader. |
| Pair fails / immediate disconnect | Official Dexcom or xDrip still holding BLE? Uninstall/stop them; retry |
| Warm-up stuck past ~30 min | BLE still connected? Sensor already started elsewhere? Capture logcat `DEXCOM_ONEPLUS_*` |
| BG stops after hours (OEM) | Re-run §4 battery checklist; Samsung “sleeping apps” |
| Wrong sensor tag / AIMI treats as unknown | Notification packages should map to `AAPS-DexcomOnePlus`; native inserts `DEXCOM_ONEPLUS_NATIVE` |
| BYODA or Eversense broken after merge | Restore IntKeys 440 / 445; follow [MERGE_CONSTRAINT_DEXCOM_ONEPLUS.md](MERGE_CONSTRAINT_DEXCOM_ONEPLUS.md) and [MERGE_CONSTRAINT_EVERSENSE.md](MERGE_CONSTRAINT_EVERSENSE.md) |

Log markers (when implemented): `DEXCOM_ONEPLUS_SCAN`, `_PAIR`, `_SESSION`, `_BG`, `_RECONNECT`, `_WARMUP_DONE`, `_OEM_PROFILE`.

---

## 7. Smoke checks after merge / build

Do not check these as “working” without your confirmation:

1. **Dexcom ONE+** listed under BG Source.  
2. **BYODA** (`Dexcom` / patched app source) still listed and usable for G6/G7 if you use it.  
3. **Eversense** still listed and usable if you use it.  
4. Native pair → ~30 min warm-up UI → first BG — only after A6/A8 and your device sign-off.

---

## 8. Doc map

| Doc | Role |
|-----|------|
| [DEXCOM_ONEPLUS_NATIVE_PLUGIN_PRODUCT.md](DEXCOM_ONEPLUS_NATIVE_PLUGIN_PRODUCT.md) | Product decisions & DoD |
| [DEXCOM_ONEPLUS_NATIVE_AGENT_PLAN.md](DEXCOM_ONEPLUS_NATIVE_AGENT_PLAN.md) | Multi-agent delivery |
| [MERGE_CONSTRAINT_DEXCOM_ONEPLUS.md](MERGE_CONSTRAINT_DEXCOM_ONEPLUS.md) | Merge preservation |
| [NON_REGRESSION_CHECKLIST.md](NON_REGRESSION_CHECKLIST.md) | Fork smoke / PR gate |
