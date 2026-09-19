# AIMI KMP — lots restants

**Vérifié :** 2026-09-06 · étude `6f66e635` · ref `c5db5a0333`  
**Méthode (obligatoire) :** [PORTING-against-milos-kmp.md](PORTING-against-milos-kmp.md)  
**Snapshot :** [STATUS.md](STATUS.md)

Règle : un lot compile, et s’il touche des nombres il a un test. Pas d’« amélioration » clinique.  
**Comportement = `dev_OAPSAIMI`. Câblage = étude / Milos.** Une copie naïve de la référence ne compile pas et peut mal doser (voir la matrice du guide).

Ne pas extraire le tick vers `:plugins:aimi-engine` maintenant. Le Hold reste Hold.

---

## P0 — rattraper la référence clinique

| Lot | État @ `6f66e635` | Fichiers ref | Gate |
|---|---|---|---|
| **P0.1** `PkPdLearnedState` | **Fait** `#71` / `6f66e635` | `pkpd/PkPdLearnedState.kt` + call sites | déjà merge |
| **P0.2** `DynIsfCache` | À faire (cache étude = `LongSparseArray` clé time+glucose) | `ISF/DynIsfCache.kt` + test | compile + test ; **ne pas** remplacer le plugin |
| **P0.3** `ObservedSensitivityMeter` | Absent | + test | mêmes call sites que la ref |
| **P0.4** `CommandedIsf` | Absent | + `CommandedIsfOrderTest` | « instrument before brake » |
| **P0.5** `MaxSmbLadder` | Peut-être déjà inline dans le tick | + 2 tests | **diff d’abord** ; extraire ou prendre la règle `f03fa321a6`, pas les deux |
| **P0.6** Harmonia + origin meter | Absents sous ces noms | `HarmoniaCounterfactual` + `InsulinOriginMeter` + tests | ne pas jeter `IobSurveillanceExport` sans lire la ref |
| **P0.7** `SmbTrainingRowBuffer` | Absent | + test | maths commonMain ; fichiers androidMain / `AimiStorage` |
| **P0.8** sync tick | −424 lignes vs ref | hunks des 7 commits AIMI (pas `c5db5a0333` loop) | **ne pas** remplacer `DetermineBasalAIMI2` |

P0.2–P0.7 : un agent par fichier ; **pas** d’édition partagée du tick avant P0.8.  
P0.8 a besoin des types. Détail d’adaptation : guide §7.

Hors P0 : re-grid glucose `c5db5a0333` = loop, pas AIMI, sauf décision explicite.

---

## P1 — tick honnête / déplaçable

| Lot | Travail |
|---|---|
| **P1.1** | Corriger l’en-tête périmé de `AimiCollaboratorPorts.kt` (« no implementation yet ») |
| **P1.2** | Inventaire `android.*` / `java.time` / `File` / `Atomic*` dans le tick |
| **P1.3** | `java.time` → `kotlinx.datetime` (même aliases que les callees) |
| **P1.4** | `File` / `Environment` → `AimiStorage` |
| **P1.5** | `Context` hors du tick (comme SOS) |
| **P1.6** | Porter les tests **purs** ref par paquets ≤15 (`commonTest` si pas Mockito) |
| **P1.7** | Réessayer le tick en `commonMain` seulement après P0 + P1.3–P1.5 |

`:app:assembleFullDebug` = gate Metro. `:plugins:aps:compileAndroidMain` seul cache un binding manquant.

---

## P2 / P3 / P4 (inchangé, plus tard)

- **P2** : Activities View staging, camera, `includeDagger()` Dexcom/Libre. Demander avant de porter une Activity.
- **P3** : capture `AimiInputSnapshot` read-only ; iOS storage / UAM / HealthKit. **Pas** `IosClientConfig.APS = true`.
- **P4** : CGM KMP, master iOS, remplir `aimi-learning` / `aimi-io`.

---

## Formes d’échec (toujours vraies)

1. Impl sans `@ContributesBinding` : compile `aps`, casse `:app`.
2. `*ResId: Int` → `TextRef`. `exportable` **interdit** sur le ctor `StringKey`.
3. `Atomic*` / `synchronized` (commonMain) → `AapsLock` ; `System.currentTimeMillis()` → `aimiWallClockMs()` ; `String.format` → `aimiFmtN` ; `java.time` → `kotlinx.datetime`.
4. Doublon de type (déclaration, pas le nom de fichier).
5. Ne pas rebaser `dev_OAPSAIMI` sur cette branche.
6. Ne pas utiliser `@IntKey(225)` — AIMI étude = **250**.
