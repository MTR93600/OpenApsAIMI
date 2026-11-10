# Licence & provenance memo — Dexcom ONE+ native BLE (Agent A1)

**Date:** 2026-07-18  
**Branch:** `feature/dexcom-oneplus-native`  
**Product decisions:** Q5 = **xDrip Direct port**; Q6 = **NOTICE obligatoire**  
**Scope:** docs + NOTICE draft only (no BLE code port in this lot)  
**Disclaimer:** Not legal advice. A0 signs Q6; escalate to a lawyer if redistributing outside this AGPL fork’s normal source offer.

---

## 1. Verdict (A1.5)

| Option | Recommendation |
|--------|----------------|
| **Port OK** | **YES — conditional** (preferred for Q5=A) |
| Wrapper only (AAPS ↔ xDrip Intent) | Keep as **interim / fallback** (Phase A / user guide); does **not** meet native product goal |
| Abandon native | Only if A3 device spike fails **or** A6 cannot ship without non-GPL binary-only auth |

**A0 may sign Q6** for an in-tree port of GPL-3 xDrip Direct / `libkeks` into this AGPL-3 AAPS fork, provided:

1. Upstream **repo + commit are pinned** before A6 merges production protocol code.  
2. `plugins/dexcom_oneplus/NOTICE` (draft below) ships with the first ported sources.  
3. Port stays **subset + rewrite of AAPS glue** — not a wholesale xDrip UI/service dump.  
4. Prefer **in-tree `libkeks`** (`Loader.getLocalInstance` path). Treat **remote** plugin download hosts (`rgate1.local`, `plugin1.beonlabs.net` in xDrip `Registry`) as **out of scope / separate review** — do not reintroduce opaque download-at-runtime for AAPS v1.

---

## 2. Source repository & commit candidates (A1.1)

### 2.1 Primary (Q5 = A) — xDrip+ Direct / G7 / ONE+ / Stelo family

| Field | Value |
|-------|--------|
| Canonical repo | https://github.com/NightscoutFoundation/xDrip |
| SPDX / LICENSE file | **GPL-3.0** (`LICENSE` at repo root = GNU GPL v3) |
| Homepage / APK info | https://jamorham.github.io/#xdrip-plus |
| Related historical mirror | https://github.com/jamorham/xDrip-plus (same lineage; prefer NightscoutFoundation for pins) |
| User-facing Direct docs | https://navid200.github.io/xDrip/docs/Dexcom/G7.html (G7, One+, Stelo) |

**Pinned candidate for A6 (audit snapshot 2026-07-18):**

| Kind | Identifier |
|------|------------|
| Tag | `2026.07.15` |
| Commit | `1e86d9a2a52577ed2c30fbf7b69d75fd56e6918f` |
| Commit URL | https://github.com/NightscoutFoundation/xDrip/commit/1e86d9a2a52577ed2c30fbf7b69d75fd56e6918f |
| Commit date (author) | 2026-07-15 |

**Alternate pin:** any later **release tag** on the same repo after A6 starts — update NOTICE + this memo when the pin moves. Do not “float” on `master` without recording the hash used for the port.

**How ONE+/G7 Direct maps in xDrip (observed):**

- Collection path is **OB1 / “native” G5-family stack**, not a separate “ONE+” tree.  
- G7 / short transmitter ID (`shortTxId()`) selects G7-style EGV / session behaviour.  
- ONE+ / Stelo are treated as the **same Direct family** in xDrip docs; firmware tables live in `FirmwareCapability`.  
- Auth for short-ID / ExtraData path uses **`libkeks`** (J-PAKE-related), exposed as `jamorham.keks.Plugin` via `plugin.Loader.getLocalInstance`.

### 2.2 Secondary (Q5 ≠ B — reference only)

| Field | Value |
|-------|--------|
| Repo | https://github.com/j-kaltes/Juggluco |
| License (GitHub API) | **GPL-3.0** |
| HEAD at audit | `5297da7c23a312d1065c5e828a7b61a161cc1200` (2026-07-18 snapshot; re-resolve before any mix) |

**Do not mix Juggluco + xDrip sources in v1** unless Q5 is reopened to D and a separate licence pass is done. Juggluco remains a **fallback comparison** if `libkeks` port stalls.

---

## 3. This fork’s licence vs xDrip (A1.2)

| Work | Licence |
|------|---------|
| OpenApsAIMI / AndroidAPS tree (`LICENSE.txt`) | **GNU Affero GPL v3** (AGPL-3.0) |
| NightscoutFoundation/xDrip | **GNU GPL v3** (GPL-3.0) |
| BouncyCastle (used by xDrip `libkeks`) | MIT-style (external dep; attribute if/when pulled into Gradle) |

### Implications for porting into this fork

1. **Compatibility:** GPL-3 and AGPL-3 are designed to be combined. A combined work that includes GPL-3 code may be distributed under **AGPL-3** (see GPL-3 §13 / AGPL-3 §13 interplay). This fork is **already** AGPL — a GPL-3 port does **not** force a licence downgrade of the whole APK to “GPL-only,” nor does it allow closing the source.  
2. **Copyleft:** Ported xDrip/`libkeks` code (and modifications) remain free software. Recipients of the APK must be able to obtain **corresponding source** (already an AGPL obligation for network-offered modified versions; still required for distributed APKs).  
3. **Q10 = fork-only forever:** Compatible with AGPL. “No Nightscout upstream PR” does **not** remove source/attribution duties for this fork’s users.  
4. **NOTICE / headers:** Keep upstream attribution; add file headers on ported units pointing at the pin commit; ship module `NOTICE`.  
5. **Isolation myth:** Putting code in `:plugins:dexcom_oneplus` does **not** create a GPL “sandbox” that leaves the rest of AAPS untouched in practice — the APK is one combined work. Isolation still helps **maintenance and attribution clarity**, not licence quarantine.  
6. **Remote plugin hosts:** xDrip also registers downloadable plugins. Even if those blobs were historically used, **v1 must not depend on unverified remote DEX** without a separate provenance/licence review. Prefer the **in-repo `libkeks` module** (source present under the same GPL-3 tree).  
7. **Non-licence product risk (unchanged):** Unofficial Dexcom protocol; user responsibility (product S3). Licence OK ≠ medical/regulatory clearance.

---

## 4. Files likely to port vs rewrite (A1.3)

Paths relative to xDrip repo root at pin `1e86d9a2a525…`. Target names follow agent plan §A6 (`Scanner`, `GattCallback`, `SessionAuth`, …) under `plugins/dexcom_oneplus/`.

### 4.1 Likely **port / adapt** (protocol & session)

| Upstream path | Role | A6 target (approx.) |
|---------------|------|---------------------|
| `libkeks/src/main/java/jamorham/keks/**` | J-PAKE / cert / challenge auth (“KEKS”) | `SessionAuth` |
| `libkeks/src/main/java/jamorham/libkeks/**` | Digests / SHA helpers | `SessionAuth` support |
| `ipluginda/.../IPluginDA.java` | Plugin interface only if keeping keks API shape | thin adapter or inline |
| `app/.../services/Ob1G5CollectionService.java` | BLE scan/connect/bond/state machine (large) | `Scanner`, `GattCallback`, `ReconnectPolicy` (**extract**, do not copy whole Service) |
| `app/.../g5model/Ob1G5StateMachine.java` | Control channel, EGV request, backfill, G7 branches | `GlucoseParser`, `Backfill`, `SessionStart` |
| `app/.../g5model/BluetoothServices.java` | GATT UUIDs | constants |
| `app/.../g5model/EGlucoseRxMessage.java`, `EGlucoseRxMessage2.java`, `EGlucoseTxMessage.java` | G7-style glucose | `GlucoseParser` |
| `app/.../cgm/dex/g7/EGlucoseRxMessage.java`, `BaseMessage.java`, `BackfillControlRx.java` | G7 packet helpers | `GlucoseParser` / `Backfill` |
| `app/.../g5model/SessionStartTxMessage.java` (+ Rx/Stop siblings as needed) | Session start from code | `SessionStart` |
| `app/.../g5model/Auth*Message.java`, `BaseAuthChallengeTxMessage.java` | Classic auth messages (G5/G6 path) | only if needed beside keks |
| `app/.../g5model/BackFill*.java` | Short backfill | `Backfill` |
| `app/.../g5model/FirmwareCapability.java` | Firmware / ONE / alt tables | capability helper |
| `app/.../g5model/CalibrationState.java` | Warm-up / OK states | `WarmupState` mapping |
| `app/.../cgm/dex/TxIdHelper.java` | Pairing / TxId validation | pair UX + driver |
| `app/.../cgm/dex/BlueTails.java` | ADV / classifier support (review before port) | `Scanner` |
| `app/.../utils/DexCollectionType.java` (G7 helpers only) | Collector naming / class routing | reference, not full enum dump |

### 4.2 Likely **rewrite** (do not port as-is)

| Area | Why |
|------|-----|
| Entire xDrip `Home` / UI / alarms / treatments | Out of product scope |
| `Pref` / `PersistentStore` / `Inevitable` / `JoH` utilities | Replace with AAPS prefs + small local helpers |
| RxAndroidBle usage inside Ob1 | May keep pattern or move to platform GATT; **rewrite** to AAPS lifecycle / Eversense-like watcher |
| xDrip `BroadcastGlucose` / NS upload | AAPS `PersistenceLayer` via `BgSource` instead |
| Compose / pair / warm-up countdown UI | Owned by A8 + existing scaffold |
| OEM battery profiles | Owned by A9 (`DeviceProfileRegistry`) |
| AIMI / `SourceSensor` | Owned by A2/A10 — **no** xDrip port |
| BYODA `DexcomPlugin` | Explicitly out of scope |
| Remote `plugin.Download` / `Cache` / host `Registry` entries | Avoid for v1 |

### 4.3 Attribution notes found in upstream (incomplete © lines)

Many Java files **lack** a formal `Copyright (C) YEAR Name` header. Comments observed:

| Marker in source | Interpretation for NOTICE |
|------------------|---------------------------|
| `JamOrHam` / `jamorham` | Primary author tag on Ob1 / keks / many g5model files |
| `created by jamorham` | Same |
| `Created by joeginley` (`BluetoothServices`) | Earlier G5 UUID work — **full legal name / years UNKNOWN** |
| “Published by the Nightscout Foundation” (README) | Publisher / community umbrella — **not** a substitute for per-file © |

**Do not invent** corporate copyright holders. When porting, copy any existing header comments; if none, state in NOTICE: *copyright holders not stated in file headers; see git history of pin commit*.

---

## 5. NOTICE draft location (A1.4)

Draft ready for A6:

`/Users/mtr/StudioProjects/OpenApsAIMI/plugins/dexcom_oneplus/NOTICE`

Update the **pin commit / tag** line when A6 lands real ported sources. Add BouncyCastle (or other) third-party blocks only when Gradle deps are added.

---

## 6. Risks & open questions for A0 / A6

| ID | Risk | Mitigation |
|----|------|------------|
| R1 | Maintenance tracks xDrip churn | Pin commit; document rebase cadence; keep port surface small |
| R2 | `libkeks` + BC crypto size / review | Port minimal packages; discuss BC Gradle dep before add |
| R3 | Temptation to ship remote plugin DEX | **Forbidden for v1** without new A1 pass |
| R4 | Mixing Juggluco without reopening Q5 | Stay on xDrip pin unless A0 changes Q5 |
| R5 | Incomplete © lines | Honest NOTICE + git provenance; no fake names |
| R6 | A3 spike may still NO-GO on devices | Licence GO ≠ product GO |

---

## 7. Recommendation summary

**Port OK** from **NightscoutFoundation/xDrip @ `1e86d9a2a52577ed2c30fbf7b69d75fd56e6918f` (tag `2026.07.15`)**, focusing on **`libkeks` + Ob1/g5model/G7 message subset**, rewritten into `:plugins:dexcom_oneplus` under this repo’s **AGPL-3**, with **NOTICE** shipped.

**Wrapper** remains the supported interim (xDrip Direct → AAPS xDrip BG source).  
**Abandon** native only on technical/product NO-GO — not on GPL/AGPL incompatibility.
