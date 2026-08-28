# ADR G0-D2b — iOS pump is Medtrum

> **Status:** accepted 2026-08-28.  
> **Parent:** [`adr-g0-defaults.md`](adr-g0-defaults.md) (D2 pump was open: Dana-i or Medtrum).  
> **Branch:** `kmp-aimi-migration-study`.  
> **Change rule:** same as G0. Therapy behaviour is unchanged. This only names the iOS pump.

## Context

G0 left the week-8 iOS pump as Dana-i **or** Medtrum. Product lock: **Medtrum**.

AIMI still must not command a pump inside `evaluate`. Android already has `:pump:medtrum`. iOS already has Trio `MedtrumKit`. Those are two BLE stacks, two repos. Do not copy either into the other.

## Decision

| ID | Default |
|---|---|
| D2 pump | **VirtualPump** until week 8. After W8: **Medtrum only** (Android `:pump:medtrum`, iOS Trio `MedtrumKit`). No Dana-i as first iOS pump. No Bluetooth Classic pump. |
| D2 CGM | Unchanged. First CGM is **Dexcom ONE+ / G7** (`GlucosePort`). Libre 3 is wave 2. Medtrum is the **pump**, not the CGM. |
| D1 host | Unchanged. iOS host is **Trio**. AIMI is `AimiKit`. There is **no** `OpenAPSAIMIPlugin` on iOS. |

## Consequences

- One KMP **engine** in this repo (`evaluate` later). Two **shells**: AAPS plugin on Android, Trio on iOS.
- Do not put `:pump:medtrum` in `iosMain`. Do not vendor `MedtrumKit` into this tree.
- Re-pair Medtrum on the iPhone. Do not copy Android bonds (D12).
- Tick last. This ADR does not ship the plugin or the iOS loop.

## Still open

- Week-8 go/no-go for dropping VirtualPump.
- Exact Trio version / MedtrumKit pin.
- CGM wave 2 (Libre 3).
