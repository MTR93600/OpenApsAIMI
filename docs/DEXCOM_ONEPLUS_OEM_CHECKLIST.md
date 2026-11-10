# Dexcom ONE+ native — OEM first-launch checklist

**Audience:** testers / users on Q9 phones (Pixel 6/7/8, Samsung S22–S24)  
**Related:** [DEXCOM_ONEPLUS_NATIVE_PLUGIN_PRODUCT.md](DEXCOM_ONEPLUS_NATIVE_PLUGIN_PRODUCT.md) §5.4  
**Code:** `DeviceProfileRegistry` (`DEXCOM_ONEPLUS_OEM_PROFILE` in logcat)

Plugin profiles only tune BLE timeouts / MTU / FGS. OS battery and Bluetooth settings stay **user responsibility**.

## Before first pair (all OEMs)

1. **Bluetooth ON** (system settings).
2. **AAPS battery unrestricted** — Settings → Apps → AAPS → Battery → Unrestricted (wording varies by OEM).
3. Disable or pause the stock **Dexcom** app if it holds the sensor as BLE master (v1: one master only).
4. Do **not** run xDrip Direct against the same One+ while the native plugin is ON (unless product Q3 says otherwise).
5. Confirm logcat shows `DEXCOM_ONEPLUS_OEM_PROFILE` with the expected `id=` (`PIXEL`, `SAMSUNG`, or `GENERIC_FALLBACK`).

## Pixel 6 / 7 / 8

- Battery: App battery usage → **Unrestricted**.
- Adaptive Battery / App Standby: leave AAPS unrestricted; avoid “Restricted”.
- Nearby devices / Bluetooth permission granted to AAPS.
- No Samsung-style “sleeping apps” list — if using a 3rd-party battery saver, exclude AAPS.

## Samsung S22–S24 (One UI)

- Battery → Background usage limits → remove AAPS from **Sleeping** / **Deep sleeping**.
- Apps → AAPS → Battery → **Unrestricted**.
- Optional: Device care → Battery → put AAPS in **Never sleeping apps**.
- Disable aggressive “put unused apps to sleep” for AAPS during lab runs.

## Generic / other OEM (`GENERIC_FALLBACK`)

- Same unrestricted battery + Bluetooth steps as above.
- Also exclude AAPS from OEM “auto-start managers”, “battery savers”, and Bluetooth power-saving if present (Xiaomi, Oppo, Huawei, etc.).
- Expect more conservative MTU / longer connect timeouts; 24 h lab still required before claiming support.

## Quick verification

| Check | Pass |
|-------|------|
| Marker `DEXCOM_ONEPLUS_OEM_PROFILE` once at resolve | ☐ |
| Profile `id` matches phone family | ☐ |
| AAPS battery Unrestricted | ☐ |
| Session survives screen-off ≥ 1 h smoke | ☐ |
| 24 h lab without exotic manual toggles (A9 DoD) | ☐ |
