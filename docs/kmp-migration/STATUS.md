# AIMI / OpenApsAIMI KMP status

**Live snapshot:** 2026-09-19 (docs tracker, post-P3.8)  
**This branch tip:** `kmp-aimi-migration-study` @ `c9ff5e2f0ef785422114aa3bd9cf45159506dd75`  
**Last clinical lot on study:** P3.8 [#115](https://github.com/MTR93600/OpenApsAIMI/pull/115) @ `f4ed4e401cb88e8a08907c0c3cec4661cc1094a4`  
**P0 clinical freeze:** `origin/dev_OAPSAIMI` @ `c5db5a033379390bceb7851ff92004b72ef055bf` (2026-09-06)  
**Current AIMI ref tip:** `origin/dev_OAPSAIMI` @ `c653fc4485dd985088d9a30a42c99af4e3b285e4` (2026-09-19)  
**Ahead / behind reference (recomputed):** study is **1057** unique commits ahead, **2797** behind.

Re-verified with `git fetch origin kmp-aimi-migration-study` and `git fetch origin dev_OAPSAIMI`.  
`c9ff5e2f` **is** `origin/kmp-aimi-migration-study`. `c653fc4485` **is** `origin/dev_OAPSAIMI`.  
`c5db5a0333` is an ancestor of today’s AIMI tip (P0 freeze; loop re-grid commit, still out of AIMI lots).

This file is the live snapshot. Agent roles / triple feu: [`_docs/kmp/AGENT_OPS.md`](../../_docs/kmp/AGENT_OPS.md).  
Remaining work: [DELTA-remaining.md](DELTA-remaining.md).  
Older notes under `_docs/kmp/` are history (lots 0–6i diary, per-lot `P*-ANCHOR.md`). Do not use their file counts or “330 tests” as today’s truth.

**Not re-run in this session:** `:app:assembleFullDebug`, `:plugins:aps:compileKotlinIosArm64`, `:plugins:aps:testAndroidHostTest`. Last written claims of those gates being green are lot-PR announcements + 2026-09-03 diary notes. Treat them as last-known, not re-proven today.

---

## 0. At a glance

| Anchor | SHA | What it is |
|---|---|---|
| Study tip (docs batch after P3.8) | `c9ff5e2f0ef785422114aa3bd9cf45159506dd75` | `docs(kmp): ancre P3.8 … (#116)` on `kmp-aimi-migration-study` |
| Study tip after clinical P3.8 | `f4ed4e401cb88e8a08907c0c3cec4661cc1094a4` | merge of [#115](https://github.com/MTR93600/OpenApsAIMI/pull/115) |
| P0 clinical freeze | `c5db5a033379390bceb7851ff92004b72ef055bf` | AIMI ref when P0.1–P0.8 were planned ([P0.8-ANCHOR](../../_docs/kmp/P0.8-ANCHOR.md)) |
| AIMI ref tip now | `c653fc4485dd985088d9a30a42c99af4e3b285e4` | `feat(calibration): add new strings…` on `dev_OAPSAIMI` |

**P3.1–P3.8 clinical gap is closed** (lots [#101](https://github.com/MTR93600/OpenApsAIMI/pull/101) / [#103](https://github.com/MTR93600/OpenApsAIMI/pull/103) / [#105](https://github.com/MTR93600/OpenApsAIMI/pull/105) / [#107](https://github.com/MTR93600/OpenApsAIMI/pull/107) / [#109](https://github.com/MTR93600/OpenApsAIMI/pull/109) / [#111](https://github.com/MTR93600/OpenApsAIMI/pull/111) / [#113](https://github.com/MTR93600/OpenApsAIMI/pull/113) / [#115](https://github.com/MTR93600/OpenApsAIMI/pull/115), all **MERGED**).  
That does **not** mean “AIMI runs on iOS”, and it does **not** invent leftover CGM / driver / Trio work. Named leftovers stay in [DELTA](DELTA-remaining.md). No formulas in this file.

---

## 1. Lot ledger P0 → P3.8 (all MERGED)

Merge SHA = first-parent commit on `kmp-aimi-migration-study` that landed the lot (`git log --first-parent`, `gh` `mergedAt` set).  
Ancre column = docs-only proof PR when one merged (later than the clinical PR in several cases).

### P0 — catch `dev_OAPSAIMI` (types + tick hunks + leftovers)

| Lot | PR | Merge SHA | Title | Ancre | Status |
|---|---|---|---|---|---|
| P0.1 | [#71](https://github.com/MTR93600/OpenApsAIMI/pull/71) | `6f66e63565546a243a569a0b6d205fce39ee334f` | PkPdLearnedState | — | MERGED |
| P0.2 | [#72](https://github.com/MTR93600/OpenApsAIMI/pull/72) | `a613bb3c27263cda00bc4a7c98c6064065709d96` | DynIsfCache | — | MERGED |
| P0.3 | [#75](https://github.com/MTR93600/OpenApsAIMI/pull/75) | `4ccd0d73ced489bd5ab35a691d5697a347eae582` | ObservedSensitivityMeter | — | MERGED |
| P0.4 | [#76](https://github.com/MTR93600/OpenApsAIMI/pull/76) | `2ffbfa32d91dbb4f771f8ccf56b78705e5cf1b52` | CommandedIsf | — | MERGED |
| P0.5 | [#77](https://github.com/MTR93600/OpenApsAIMI/pull/77) | `b30a62ea76208b239abef032a6c6f9e3e5061bcb` | MaxSmbLadder | — | MERGED |
| P0.6 | [#78](https://github.com/MTR93600/OpenApsAIMI/pull/78) | `c666ad3b49afd6abaad4ff8875b95547b3c6a6bc` | HarmoniaCounterfactual + InsulinOriginMeter | — | MERGED |
| P0.7 | [#79](https://github.com/MTR93600/OpenApsAIMI/pull/79) | `91b076ae6d9c5041b787f98054f26217eacc5a7e` | SmbTrainingRowBuffer | — | MERGED |
| P0.8 | [#80](https://github.com/MTR93600/OpenApsAIMI/pull/80) | `bff270393a228d72f03a43c0ae43734cd06a382e` | clinical tick/plugin hunk sync | [#81](https://github.com/MTR93600/OpenApsAIMI/pull/81) `04aa568a67` | MERGED |
| P0.9 | [#82](https://github.com/MTR93600/OpenApsAIMI/pull/82) | `172654f2619c0ee131452062ab4c6154e472233b` | Autodrive observation leftovers | docs #83 **closed superseded** | MERGED |
| P0.10 | [#84](https://github.com/MTR93600/OpenApsAIMI/pull/84) | `3201700fe8fc9b7a2e7d54db813ecc86b9dfbb68` | Autodrive GateKind + meal-first label | docs #85 **closed superseded** | MERGED |
| P0.11 | [#86](https://github.com/MTR93600/OpenApsAIMI/pull/86) | `f9c086992e62bfa18582149b64a02b1534600f92` | IAM barrier floor as sensitivity | docs #87 **closed superseded** | MERGED |
| P0.12 | [#88](https://github.com/MTR93600/OpenApsAIMI/pull/88) | `70823d0d327bf778c872e7b80a5ea6843184b645` | Barrier replay harness | docs #89 **closed superseded** | MERGED |

### P1 — clinical follow-ons (not the old “move DB2” plan)

| Lot | PR | Merge SHA | Title | Ancre | Status |
|---|---|---|---|---|---|
| P1.1 | [#90](https://github.com/MTR93600/OpenApsAIMI/pull/90) | `55f832d2eb3eddc132fd91b8c9a11f8b4cb2ce6f` | SMB train on smbGiven | [#91](https://github.com/MTR93600/OpenApsAIMI/pull/91) `8b36427e15` | MERGED |
| P1.2 | [#92](https://github.com/MTR93600/OpenApsAIMI/pull/92) | `181ba03b3f897a7350073e50c207aa144e447465` | DescentRedoseGuard | [#93](https://github.com/MTR93600/OpenApsAIMI/pull/93) `a722f19b63` | MERGED |

### P2 — product / tests

| Lot | PR | Merge SHA | Title | Ancre | Status |
|---|---|---|---|---|---|
| P2.1 | [#94](https://github.com/MTR93600/OpenApsAIMI/pull/94) | `3029f0779acef5283ec39db504ceb6532b07c197` | AIMI Flutter viewer (`tools/aimi_viewer`) | [#95](https://github.com/MTR93600/OpenApsAIMI/pull/95) `7f8a8bdb63` | MERGED |
| P2.2 | [#96](https://github.com/MTR93600/OpenApsAIMI/pull/96) | `c19eccfb14c691e76c6f2de2162938b0837800a3` | Advisor support ZIP + active profile | [#97](https://github.com/MTR93600/OpenApsAIMI/pull/97) `157537343c` | MERGED |
| P2.5.1 | [#98](https://github.com/MTR93600/OpenApsAIMI/pull/98) | `90a95b7b3ba79d3444a6df4922807af479ee785b` | AIMI pure tests package 1 (≤15) | [#99](https://github.com/MTR93600/OpenApsAIMI/pull/99) `40d13e6359` | MERGED |

### P3 — clinical port series (P3.1–P3.8 gap closed)

| Lot | PR | Merge SHA | Title | Ancre | Status |
|---|---|---|---|---|---|
| P3.1 | [#101](https://github.com/MTR93600/OpenApsAIMI/pull/101) | `fd41bdc4d5ca2ffa57c7a59814bdd773a5de9fee` | HeartRateTrendIsf + COB HR rise-suspend | [#102](https://github.com/MTR93600/OpenApsAIMI/pull/102) `cb1da2246e` | MERGED |
| P3.2 | [#103](https://github.com/MTR93600/OpenApsAIMI/pull/103) | `54e50c28d18b98a69a03060bd27e3fccfc4ddddf` | StressIsfFloor + HeartRateCarryForward | [#104](https://github.com/MTR93600/OpenApsAIMI/pull/104) `c8a2a96d54` | MERGED |
| P3.3 | [#105](https://github.com/MTR93600/OpenApsAIMI/pull/105) | `28010b205838062b2d73a4b38f0e6f1a3728fcb1` | RiseCeilingGuard replaces DescentRedose | [#106](https://github.com/MTR93600/OpenApsAIMI/pull/106) `b5656c86f0` | MERGED |
| P3.4 | [#107](https://github.com/MTR93600/OpenApsAIMI/pull/107) | `6b5aa275f719108a7f6ce5976f0e960d3c86302e` | AnticipationBasalFloor + FCL meal basal | [#108](https://github.com/MTR93600/OpenApsAIMI/pull/108) `866934fd33` | MERGED |
| P3.5 | [#109](https://github.com/MTR93600/OpenApsAIMI/pull/109) | `a5067a028bd3719a9d4e4a4665c7ef253bf94656` | MealConfirmedEarlyReleaseLatch (MCER) | [#110](https://github.com/MTR93600/OpenApsAIMI/pull/110) `c58c0c27ef` | MERGED |
| P3.6 | [#111](https://github.com/MTR93600/OpenApsAIMI/pull/111) | `dde9b5e27ad794e20e166116336ad6313baaa4b1` | TpoRevertPolicy | [#112](https://github.com/MTR93600/OpenApsAIMI/pull/112) `80bd3f01cf` | MERGED |
| P3.7 | [#113](https://github.com/MTR93600/OpenApsAIMI/pull/113) | `6b5c49cb76b31f2930222ab041308d465a88ba85` | STALE_TRAINING_MS | [#114](https://github.com/MTR93600/OpenApsAIMI/pull/114) `7cc638d325` | MERGED |
| P3.8 | [#115](https://github.com/MTR93600/OpenApsAIMI/pull/115) | `f4ed4e401cb88e8a08907c0c3cec4661cc1094a4` | calibration health notifications | [#116](https://github.com/MTR93600/OpenApsAIMI/pull/116) `c9ff5e2f0e` | MERGED |

**Do not reuse P3.x numbers** for a new iOS-engine series. The 2026-09-06 DELTA used P3.1–P3.5 for Native extract; that numbering was consumed by the clinical port lots above.

---

## 2. Two-line summary (still true)

The Android AIMI **plugin path is live in the KMP tree**. A large share of AIMI math is in `commonMain`.  
That is **not** “AIMI runs on iOS”. The tick is still Android-only. `:plugins:aimi-engine` is still a **Hold stub**. iOS is still a **follower** (`APS = false`). P3.1–P3.8 closed the **named clinical port gap**; they did not flip the iOS product.

---

## 3. Corrections to older docs (2026-09-19)

| Old claim | Reality 2026-09-19 |
|---|---|
| STATUS / DELTA tip `f237f2d3d0` / ref `c5db5a0333` as “today” (2026-09-06) | Study tip is `c9ff5e2f`. Freeze `c5db5a0333` is historical P0. Current AIMI tip is `c653fc4485`. |
| DELTA P0.1–P0.8 / P3.1–P3.5 still “next lots” | Those **IDs landed** (P0 as planned; P3.x as the later clinical series). See ledger. |
| “DetermineBasalAIMI2 is 2 files from `commonMain`” (`AIMI_PORT_STATE` §1) | The tick **compiles in `androidMain`**. Not an iOS loop. |
| Freeze tag `aimi-baseline-2026-08-26` = `1ae418e106` | Tag **not** today’s reference. Use the SHA table in §0. |
| Docs ancre PRs #73 / #74 / #83 / #85 / #87 / #89 are open / authoritative | **Closed unmerged** (superseded). Use this file + `P*-ANCHOR.md` that did merge. |

---

## 4. Historical measurement (2026-09-06) — not re-counted

The sections below were measured on 2026-09-06 against study `f237f2d3d0` and ref `c5db5a0333`.  
**File counts, line counts, and “11 AIMI tests” are stale.** P0–P3.8 have landed since. Kept as the last whole-tree census, not as today’s inventory.

### 4.1 Repo-wide KMP spine (2026-09-06)

| Item | Count then | Notes |
|---|---:|---|
| Modules with `kotlin("multiplatform")` | **34** | Includes `:plugins:aps`, `:plugins:aimi-*`, `:ios:shell` |
| `commonMain` Kotlin files (whole repo) | **2203** | |
| `iosMain` Kotlin files (whole repo) | **75** | None of them are `openAPSAIMI` |
| iOS product kind | **follower** | `IosClientConfig.APS = false` |

### 4.2 Dedicated AIMI KMP modules (still scaffolding unless a later lot says otherwise)

| Module | What was there on 2026-09-06 |
|---|---|
| `:plugins:aimi-contracts` | Envelope DTOs + `hello()` |
| `:plugins:aimi-engine` | `HoldAimiEngine` → always `Hold("ENGINE_NOT_EXTRACTED")` |
| `:plugins:aimi-learning` / `:plugins:aimi-io` | `hello()` |
| `:plugins:aimi-testkit` | empty snapshot helpers + a hello test |

**Module graph for an extracted engine exists. The engine does not.** No later P0–P3.8 lot claimed otherwise.

### 4.3 CGM plugins AIMI cares about (2026-09-06 — still the product shape)

| Module | KMP? | iOS |
|---|---|---|
| `:plugins:source` | yes (Activities stay androidMain) | no drivers |
| `:plugins:dexcom_oneplus` | **no** (`com.android.library`) | no |
| `:plugins:libre3` | **no** | no |
| `:plugins:libkeks` | **no** | no |

See [DELTA](DELTA-remaining.md) — these are **post-AIMI** work, not a P3.9 formula lot.

### 4.4 What “done” still means

Android + iOS from **one** `commonMain` engine, no clinical rewrite.

Today:

- Android plugin path: reachable in a KMP app graph (last-known assemble green).
- Shared math: large, real, not extracted into `aimi-engine`.
- Clinical named gap P3.1–P3.8: **ported and anchored**.
- iOS: shared spine + follower UI. No AIMI tick. No pump. No HealthKit. No TFLite adapter claimed here.
- Extracted engine API: **stub**.
