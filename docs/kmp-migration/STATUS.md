# AIMI / OpenApsAIMI KMP — snapshot

**Vérifié :** 2026-09-06  
**Étude :** `kmp-aimi-migration-study` @ `6f66e63565546a243a569a0b6d205fce39ee334f`  
**Référence :** `origin/dev_OAPSAIMI` @ `c5db5a033379390bceb7851ff92004b72ef055bf`  
**Merge-base :** `283a184f60`  
**Ahead / behind :** **984** / **2746**

**Méthode de portage (lire avant tout lot) :** [PORTING-against-milos-kmp.md](PORTING-against-milos-kmp.md).  
Lots restants : [DELTA-remaining.md](DELTA-remaining.md).

Les docs `_docs/kmp/*` sont de l’histoire. Plusieurs claims y sont faux (plugin « non enregistré », freeze `1ae418e106`, « 2 files from commonMain »).

**Gates non relancées dans cette session.** Dernière mention écrite verte assemble / iOS klib / host tests : 2026-09-03, *avant* le merge Milos et P0.1.

---

## Deux lignes

Le plugin Android AIMI est **vivant dans l’arbre KMP** : `OpenAPSAIMIPlugin` en `androidMain`, `@MetroIntKey(250)`. Une grosse part des maths est en `commonMain` (340 fichiers).

Ce n’est **pas** « AIMI tourne sur iOS ». Le tick (18 888 lignes) reste Android. `:plugins:aimi-engine.evaluate()` est un Hold. `APS = false` sur iOS.

---

## Ce qui a bougé depuis le snapshot `f237f2d3d0` (PR #69)

| SHA | Quoi | Impact AIMI clinique |
|---|---|---|
| `196179309b` | Merge branche Milos `kmp` | **Aucun** fichier sous `openAPSAIMI/**` (diff vide). Change le *monde* (prefs, cloud, UI shells). |
| `9e2b5bd40e` | Fallout | `StringKey.exportable` retiré ; `LocalImportExportPrefs.uploadFileToCloud` pour iOS/desktop. |
| `6f66e635` | **P0.1 #71** | `PkPdLearnedState` partagé (Metro + `AapsLock`). |

---

## Comptages AIMI (`:plugins:aps`, package `openAPSAIMI`)

| Source set | `.kt` | Rôle |
|---|---:|---|
| `commonMain` | **340** | Maths, PK/PD, physio types, ports, `PkPdLearnedState` |
| `androidMain` | **109** | Tick, plugin, HC, TFLite/ONNX, Compose, SOS |
| `iosMain` | **0** | — |
| `androidHostTest` | **13** | +2 vs le snapshot PR #69 (tests P0.1) |
| `commonTest` | **0** | — |

Tick : étude 18 888 · ref 19 312. Plugin : 2 339 · 2 333.

Tests AIMI ref (`src/test/.../openAPSAIMI/*Test.kt`) : **252**. Le corpus n’est presque pas sur l’étude.

---

## Déjà câblé (ne pas refaire)

- Enregistrement Metro **250**, pas Hilt **225**.
- Ports collaborateurs + `@ContributesBinding` Android (storage, auditor, TPO, SOS, comparator, physio, LLM, behavior profile).
- Cloud AIMI via `ImportExportPrefs.uploadFileToCloud` + `CLOUD_PATH_AIMI`.
- Horloge `aimiWallClockMs()` ; IO `AimiStorage` ; pas d’`expect` AIMI dans `:plugins:aps`.
- **P0.1** `PkPdLearnedState` — modèle d’adaptation dans le guide de portage.

## Pas encore (clinique ref récente)

`DynIsfCache`, `ObservedSensitivityMeter`, `CommandedIsf`, `HarmoniaCounterfactual`, `InsulinOriginMeter`, `SmbTrainingRowBuffer`. `MaxSmbLadder` peut déjà être inline dans le tick — comparer. Le plugin étude cache encore DynISF dans un `LongSparseArray` à clé time+glucose.
