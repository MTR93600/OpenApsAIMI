# AIMI — Roadmap Intelligence Snapshot (architecture unifiée)

**Statut :** roadmap d'implémentation + base livrée en code  
**Date :** 2026-07-12  
**Portée :** snapshot autoritaire par tick, PKPD/kinetics, prédiction C1, propagation intelligence produit

**Documents liés :**
- [PKPD_KINETICS_ARCHITECTURE.md](PKPD_KINETICS_ARCHITECTURE.md)
- [AIMI_PREDICTION_DIVERGENCE.md](AIMI_PREDICTION_DIVERGENCE.md)
- [AIMI_PREDICTION_PHYSIO_PKPD_HARMONY_ROADMAP_2026-06-10.md](AIMI_PREDICTION_PHYSIO_PKPD_HARMONY_ROADMAP_2026-06-10.md)

---

## 1. Objectif produit

Une **intelligence produit** = un `AimiIntelligenceSnapshot` **immuable par tick**, lu par tous les consommateurs (PKPD, prédiction, SMB, safety, ML, JSONL, UI).

**Problème résolu :** dispersion des autorités (3 courbes insuline, 3–4× `computeRuntime`, terminaux prédiction divergents).

---

## 2. Architecture cible (4 couches)

```
COUCHE 0 — Bootstrap tick
  PatientStateSnapshot + CausalStatePosterior + TrajectoryAnalysis

COUCHE 1 — AimiIntelligenceSnapshot (immutable)
  causal │ kinetics (InsulinKineticsAuthority) │ isf │ predictions │ smbPolicy │ mlFeatures

COUCHE 2 — Domain services
  PkPdIntegration │ TapPeakGovernor │ DiaGovernor │ CausalKineticsModulator
  DecisionPredictionAuthorityResolver │ ScenarioProjectionEngine

COUCHE 3 — Consommateurs (lecture seule)
  SMB │ SafetyNet │ tube-line │ profiler │ DynISF │ Harmonia │ JSONL │ UI
```

---

## 3. Phases et statut

| Phase | Contenu | Statut |
|-------|---------|--------|
| **0** | Types snapshot + builder + export JSONL | ✅ Livré |
| **1** | `InsulinKineticsAuthority`, `DiaGovernor`, `CausalKineticsModulator`, learn unique | ✅ Livré |
| **2** | `PredictionAuthorityApplier` + C1 consommateurs (flag) | ✅ Livré (shadow ON, prod OFF) |
| **3** | Profiler / observer / DynISF via snapshot | ✅ Livré |
| **4** | Arbre Lot 2 `insulin_kinetics_context` | ✅ Modulateur causal livré |
| **5** | Accounting IOB policy (shadow → prod) | 🔲 Non démarré |

---

## 4. Flags produit

| Clé | Défaut | Rôle |
|-----|--------|------|
| `key_aimi_intelligence_snapshot_export` | true | Export `intelligence_snapshot_v1` JSONL |
| `key_aimi_intelligence_single_learn_path` | true | Un seul `computeRuntime` apprenant / tick |
| `key_aimi_dia_governor_enabled` | true | TAP-D blend profil + learned DIA |
| `key_aimi_prediction_authority_shadow` | true | Log Δ eventual/predT sans impact dose |
| `key_aimi_prediction_authority_enabled` | **false** | C1 prod : applicator + stacking + SafetyNet |

---

## 5. Règles de propagation

| Rôle cinétique | Source | Consommateurs |
|----------------|--------|---------------|
| **Accounting** | Profil `iCfg` | IOB loop, MaxIOB (inchangé) |
| **Structural** | `PkPdParams` persistés | Learn, logs, UI |
| **Effective** | TAP-G + TAP-D + causal | Prédictions, tube-line, profiler, cosine |
| **Prediction IOB** | `InsulinKineticsAuthority` | `AdvancedPredictionEngine`, profiler |

---

## 6. KPI de validation

| KPI | Avant | Cible |
|-----|-------|-------|
| `computeRuntime` learn / tick | 2–3× | **1×** |
| JSONL `learning_gate_pass` | absent | 100 % ticks |
| \|eventual − composite_min\| med | ~48 mg/dL | < 15 mg/dL (Phase 2) |
| Consommateurs sur kinetics authority | ~40 % | 100 % (Phase 3) |

---

## 7. Fichiers livrés (Phase 0–1)

```
orchestration/
  AimiIntelligenceSnapshot.kt
  AimiIntelligenceSnapshotBuilder.kt
  IntelligenceSnapshotJson.kt

pkpd/
  InsulinKineticsAuthority.kt
  DiaGovernor.kt
  CausalKineticsModulator.kt
  PkpdLearningDiagnostics.kt
```

Modifications :
- `PkPdIntegration.kt` — `allowLearning` + diagnostics
- `DetermineBasalAIMI2.kt` — snapshot, cache PKPD unifié, export
- `OpenAPSAIMIPlugin.kt` — invoke read-only PKPD
- `BooleanKey.kt` / `DoubleKey.kt` — flags
- Tests unitaires clés

---

*Implémentation incrémentale — comportement dose préservé ; accounting IOB profil inchangé.*
