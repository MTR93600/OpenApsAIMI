# AIMI KMP — remaining delta (post-P3.8)

**Verified:** 2026-09-19  
**Study tip:** `kmp-aimi-migration-study` @ `c9ff5e2f0ef785422114aa3bd9cf45159506dd75`  
**Last clinical lot:** P3.8 [#115](https://github.com/MTR93600/OpenApsAIMI/pull/115) @ `f4ed4e401c`  
**P0 freeze:** `c5db5a033379390bceb7851ff92004b72ef055bf`  
**AIMI ref tip now:** `origin/dev_OAPSAIMI` @ `c653fc4485dd985088d9a30a42c99af4e3b285e4`  
**Companion:** [STATUS.md](STATUS.md) · [`AGENT_OPS.md`](../../_docs/kmp/AGENT_OPS.md)

Rule: one lot is small enough to compile and (where it touches numbers) to check against a baseline. **No clinical “improvements”.** Medical closed-loop: move or copy behaviour, do not invent it. Do not invent formulas in this file.

Do **not** start by copying `DetermineBasalAIMI2` into `:plugins:aimi-engine`. The live path is still `:plugins:aps`. The Hold stub stays Hold until a later extract lot has a real `evaluate()` with replay.

**P0.1 → P3.8 are MERGED.** See the [STATUS ledger](STATUS.md#1-lot-ledger-p0--p38-all-merged). Do not reopen those IDs. Do not reuse **P3.x** for a new iOS-engine series.

---

## Closed on this tip (do not restart)

| Series | What landed | PRs |
|---|---|---|
| P0.1–P0.8 | Types + clinical tick/plugin hunks vs freeze `c5db5a0333` | #71–#80 |
| P0.9–P0.12 | Autodrive leftovers, GateKind, IAM floor, barrier replay | #82 #84 #86 #88 |
| P1.1–P1.2 | smbGiven training; DescentRedoseGuard | #90 #92 |
| P2.1 / P2.2 / P2.5.1 | Flutter viewer; support ZIP; first pure-test package | #94 #96 #98 |
| P3.1–P3.8 | Clinical port gap closed | #101 #103 #105 #107 #109 #111 #113 #115 |

Docs ancre PRs [#73](https://github.com/MTR93600/OpenApsAIMI/pull/73) / [#74](https://github.com/MTR93600/OpenApsAIMI/pull/74) / [#83](https://github.com/MTR93600/OpenApsAIMI/pull/83) / [#85](https://github.com/MTR93600/OpenApsAIMI/pull/85) / [#87](https://github.com/MTR93600/OpenApsAIMI/pull/87) / [#89](https://github.com/MTR93600/OpenApsAIMI/pull/89) were **closed unmerged** (superseded). Merged anchors are listed in STATUS.

---

## Remaining — post-AIMI (drivers, Trio, product)

These are **not** a hidden P3.9 clinical formula lot. Open a **new** ID when the orchestrator asks for GO.

### R1 — Dexcom ONE+ / Libre 3 (Android drivers, later KMP host)

| Item | State | Do not |
|---|---|---|
| Host plugins | Android Metro `@IntKey(446)` / `@IntKey(447)` already landed | Re-port inside an AIMI math lot |
| Driver modules | `:plugins:dexcom_oneplus`, `:plugins:libre3`, `:plugins:libkeks` still `com.android.library` | Flip with `android-module-dependencies` (kmp-module-flip forbids it) |
| GATT / NFC | Stay platform | Pretend they are `commonMain` |
| iOS drivers | None | Claim One+/Libre3 on iPhone from this study branch |
| `:plugins:source` | KMP tree + leftover `includeDagger()` / `javax.inject` (7 files, 2026-09-06 count) | Copy AIMI-parent `com.android.library` + Hilt gradle |
| CI `:ios:shell` | Repeated `ApiElements` fails vs these three modules (same on P0.9–P3.8) | Treat as a regression of a clinical lot |

**CGM follow-ons named by [P3.8-ANCHOR](../../_docs/kmp/P3.8-ANCHOR.md) (no math here):** later lots may copy from the AIMI tip — `entriesForFit`, tip commit `1b81e356` applicability policy, ONE+/Libre3 calibration handling. **Copy the tip when a lot is opened. Do not invent thresholds or predicates in docs.**

ADR G0: first CGM is **Dexcom ONE+ / G7**. Libre 3 is **wave 2**.

### R2 — iOS pumps via Trio

| Item | Decision / leftover | Source |
|---|---|---|
| iOS host | **Trio**. AIMI is `AimiKit`. No `OpenAPSAIMIPlugin` on iOS. | [`adr-g0-defaults.md`](../../_docs/kmp/adr-g0-defaults.md) |
| First iOS pump | **Medtrum** via Trio `MedtrumKit` after W8. Until then **VirtualPump**. No Dana-i first. No Bluetooth Classic. | [`adr-g0-d2-ios-pump-medtrum.md`](../../_docs/kmp/adr-g0-d2-ios-pump-medtrum.md) |
| Android pump | `:pump:medtrum` stays Android | Do **not** put it in `iosMain` |
| Trio kit | Two BLE stacks, two repos | Do **not** vendor `MedtrumKit` into this tree |
| Still open | W8 go/no-go to drop VirtualPump; exact Trio / MedtrumKit pin | ADR G0-D2 “Still open” |
| Follower flag | `IosClientConfig.APS = false` | Do **not** flip to iOS master in a driver lot |

### R3 — open product choices (do not guess)

From [`_docs/kmp/README.md`](../../_docs/kmp/README.md) + ADR G0. **Ask the user.**

- UAM: embedded-only vs versioned user import
- Persistence / rebuild of memories after restart
- Private corpus and shadow criteria
- v1 extras: learners, HealthKit, Hormonitor viewer, Advisor/TPO
- Apple distribution + Critical Alerts entitlement
- Parity threshold after pump quantification
- `AimiLoopRuntimeGuard` still staged (hold until a feature needs it — ask)
- Loop glucose re-grid `c5db5a0333` (`LoopHubImpl`) — **loop**, not AIMI, unless the orchestrator wants loop parity
- `:plugins:aimi-engine.evaluate()` remains `Hold("ENGINE_NOT_EXTRACTED")` until a dedicated extract + replay lot

### R4 — KMP tick / Native engine (still true, unnumbered)

Not a clinical formula catch-up. Same standing work as 2026-09-06:

1. Inventory / seam platform types still inside `DetermineBasalAIMI2` (`Context`, `File`, `java.time`, `Atomic*`) before any “tick in commonMain” claim.
2. Port **pure** AIMI tests whose subject is already `commonMain` (P2.5.1 was package 1 only).
3. iOS `actual`s (storage, UAM adapter, HealthKit) only behind existing ports. Missing/Denied/Stale, never silent zero.
4. Move `evaluate()` body only after replay exists. Android plugin stays the only dose path until then.

`:app:assembleFullDebug` remains the Metro-binding gate.

---

## How to open the next lot

1. Orchestrator writes a **new** lot id (do not recycle P0–P3.8).  
2. One cloud agent, one GitHub PR, base `kmp-aimi-migration-study`.  
3. Triple feu (Ancre + Qualité + Delta + Portage) = GO/MERGEABLE — see [`AGENT_OPS.md`](../../_docs/kmp/AGENT_OPS.md).  
4. User GO, then merge.

---

## Recurring failure shapes (still true)

1. A class that implements a port but lacks `@ContributesBinding(AppScope::class)` compiles in `:plugins:aps` and fails only in `:app`.  
2. `*ResId: Int` almost always became a `TextRef`.  
3. `Atomic*` / `synchronized` → `AapsLock`; `System.currentTimeMillis()` → `aimiWallClockMs()`; `String.format` → `aimiFmtN`; `java.time` → `kotlinx.datetime`.  
4. Duplicate top-level types (by **declaration**, not filename).  
5. Capabilities dropped in the KMP rewrite (restore from `dev_OAPSAIMI` only after confirming they still exist there).  
6. Do not rebase `dev_OAPSAIMI` onto this branch. Cherry-pick or copy files. Strategy S2 still stands.  
7. No Hilt in `commonMain`.

---

## What this backlog is not

- Not a new 12-milestone plan. M0–M12 in `_docs/kmp/AIMI_KMP_IMPLEMENTATION_BACKLOG.md` is still the long architecture.
- Not permission to change SMB / basal / ISF / calibration formulae while “cleaning”.
- Not a claim that iOS can close the loop.
- Not a claim that P3.8 brought ONE+ / Libre3 / `entriesForFit` / tip `1b81e356` policy onto study.
