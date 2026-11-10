# AIMI — Contrat de cascade décisionnelle

**Statut :** design produit validé 2026-07-18  
**Intent :** harmoniser les décisions — une seule chaîne de gouvernance clinique par tick.  
**Réalisation / suivi :** [AIMI_DECISION_CASCADE_ROADMAP.md](AIMI_DECISION_CASCADE_ROADMAP.md) (index + phases + prefs natives).

**Documents liés :**
- [AIMI_DECISION_CASCADE_ROADMAP.md](AIMI_DECISION_CASCADE_ROADMAP.md) — roadmap détaillée (complétée en réalisation)
- [aimi-harmonia-implementation.md](aimi-harmonia-implementation.md) — lots H4–H7, état terrain
- [aimi-harmonia-simulation-branch.md](aimi-harmonia-simulation-branch.md) — sandbox vs pompe
- [AIMI_PREDICTION_DIVERGENCE.md](AIMI_PREDICTION_DIVERGENCE.md) — multi-terminaux prédiction
- [AIMI_PRODUCT_HARMONY_CAUSAL_TREE_2026-06-14.md](AIMI_PRODUCT_HARMONY_CAUSAL_TREE_2026-06-14.md) — arbre Lot 1

---

## 1. Phrase produit

```
Arbre        →  spine d'évidence (contexte + croyances structurées)
Harmonia     →  décideur d'intention (action + certitude)
Auditor      →  double-check (CONFIRM / SOFTEN ; rarement une autre stratégie)
```

L’arbre **informe**, Harmonia **décide**, l’Auditor **valide**.  
PKPD, SafetyNet, Prediction Authority, stacking, Tube = **capteurs et garde-fous**, pas pilotes concurrents de l’intention clinique.

---

## 2. Les trois missions (rappel)

| Mission | Question clinique | Qui porte la réponse dans la cascade |
|---------|-------------------|--------------------------------------|
| **1. Hypo amont** | « Va-t-on trop bas ? » | Arbre (`hypoRisk` / trunk) + terminaux safety **unifiés** → Harmonia peut `BLOCK` / `PROTECTIVE` ; Auditor confirme le frein |
| **2. Repas / hyper** | « Est-ce une vraie montée repas ? » | Arbre (`DIGESTION_ACTIVE` / `MEAL_PROBABLE`) → Harmonia `MEAL_SUPPORT` avec certitude ; Auditor confirme ou adoucit |
| **3. Droite** | « Rester stable sans surcorriger ? » | Arbre (fragilité / stabilité) → Harmonia `STABILIZE` ; Auditor SOFTEN si surcorrection |

---

## 3. Rôles (contrat)

### 3.1 Physiological Tree — *spine d’évidence*

| | |
|--|--|
| **Rôle** | Déployer un état patient stable et lisible (trunk, branches, leaves). |
| **Produit** | Croyances + risques + notes (pas une dose). |
| **Autorité dose** | **Aucune** directe. |
| **Doit fournir assez pour** | Que Harmonia puisse justifier `MEAL_SUPPORT` / `PROTECTIVE` / `STABILIZE` / `BLOCK` sans inventer un second diagnostic. |

**Inputs ( minimally requis pour la cascade ) :**
- BG, Δ, shortAvgΔ, IOB, COB / UAM, target
- Phases meal absorption, patient mode / causal meal
- Post-hypo / fragilité / effort (HR/HRV/steps comme **effort**, pas comme meal lead)
- Terminaux prédiction **déjà réconciliés pour le tick** (voir §5) — l’arbre ne recalcule pas les courbes

**Outputs :**
- `trunk.globalState`, `riskLevel`, confidences branches
- Leaves : notes Auditor / Advisor / raisons de non-action
- Export JSON tick (`physiological_tree`)

**Interdit :**
- Contredire silencieusement une intention Harmonia en aval via un autre canal « meal » privé
- Servir de seule source hypo amont sans terminaux safety (l’arbre hypo = surtout mémoire / fragilité ; le radar amont reste les terminaux)

---

### 3.2 Harmonia — *décideur d’intention*

| | |
|--|--|
| **Rôle** | Choisir **une** action clinique par tick et monter en **certitude** grâce à l’arbre (+ env tick). |
| **Produit** | `action` + `targetBasalUph` / simulation + blockers + `confidence` (ou équivalent exportable). |
| **Autorité dose** | Intention basale (production basal-first selon lots H4c) ; modulation SMB selon canal RBT — **pas** un second moteur PKPD. |

**Actions canoniques (Lot 1+):**
- `MEAL_SUPPORT` — mission 2
- `PROTECTIVE_REDUCTION` — frein (hypo / fragilité / effort)
- `STABILIZE` — mission 3
- `BASAL_FIRST` / `BLOCKED` — selon blockers
- Harmonizer sync : `CONFIRM` / `SOFTEN` / `BLOCK` sur la TBR proposée **avant** pompe

**Certitude Harmonia (contrat) :**
- Basse : arbre faible / trunk `UNKNOWN` / blockers sensor → plutôt `STABILIZE` ou pas de production
- Moyenne : digestion ou meal probable sans confirmation croisée → `MEAL_SUPPORT` prudent (caps existants)
- Haute : trunk `DIGESTION_ACTIVE` + rise cohérent + terminaux non contradictoires → `MEAL_SUPPORT` affirmé (H4)
- Protective haute : `HYPO_RISK` / post-hypo lock / effort → pas de meal override

**Interdit :**
- Réinterpréter le repas avec une 4ᵉ définition locale si l’arbre a déjà tranché (sauf veto sécurité explicite §4)
- Ignorer un trunk `CRITICAL` / hypo block pour « rattraper » une hyper

---

### 3.3 Auditor — *double-check*

| | |
|--|--|
| **Rôle** | Vérifier que la décision finale (et l’hypothèse Harmonia) est cohérente ; **confirmer** ou **adoucir**. |
| **Produit** | `CONFIRM` / `SOFTEN` / `SHIFT_TO_TBR` (bornes existantes) + `confidence`. |
| **Autorité dose** | Modulation **bornée** et souvent async (tick suivant via cache pour SafetyNet soft-landing) — **pas** BLOCK hard (réservé Harmonizer / safety). |

**Doit lire :**
- Feuilles arbre + trunk
- `harmonia_simulation` (hypothèse)
- `harmonia_production` (si appliqué) — à câbler proprement dans le payload JSON si manquant
- Décision finale SMB/TBR proposée

**Doit faire :**
- Si Harmonia `MEAL_SUPPORT` + arbre digestion + montée cohérente → **CONFIRM** (renforce la certitude)
- Si stacking / IOB haut / surcorrection narrative → **SOFTEN**
- Si Harmonia déjà `BLOCKED` pour hypo/sensor → préférer CONFIRM protecteur / ne pas « rouvrir » le meal

**Interdit :**
- Inventer une stratégie clinique opposée (ex. forcer meal alors que trunk hypo CRITICAL)
- Traiter `simulated_smb_u` / basal simulé comme commande pompe (`applies_to_pump=false` reste vrai)

---

## 4. Matrice de veto (qui peut arrêter qui)

| Acteur | Peut veto… | Ne peut pas… |
|--------|------------|--------------|
| **Safety hard** (LGS, maxIOB, hypo threshold physique, post-hypo delivery lock, sport lock) | Toute intention Harmonia / Auditor | — (dernier mot physique) |
| **Harmonia Harmonizer** | TBR/SMB sync : `BLOCK` / `SOFTEN` | Réécrire les courbes PKPD |
| **RBT authority** | Canal SMB vs basal-first (SOFT/NONE/…) | Contredire un BLOCK hypo hard |
| **Auditor** | Réduire / adoucir / shift TBR (bornes) | `BLOCK` hard ; ignorer arbre CRITICAL |
| **Prediction Authority / Clamp reconcile** | Ajuster le **terminal** lu par les gates | Choisir l’action Harmonia |
| **Tube / stacking / slew** | Capper amplitude | Changer l’intention (meal vs stabilize) |

**Règle d’or :** un veto aval **explique** (blocker / reason exporté) ; il ne crée pas une intention concurrente silencieuse.

---

## 5. Capteurs & garde-fous (hors cascade, au service de)

Ces modules restent indispensables, mais **sous-tendent** la cascade :

| Module | Rôle dans le contrat |
|--------|----------------------|
| **PKPD / scénario** | Produire des terminaux ; idéalement **un snapshot dose** par tick (C1) pour que arbre/Harmonia/Auditor voient la même chose |
| **ClampPkpdScenarioReconcile** | Pansement gaté anti faux-plancher — ne remplace pas C1 |
| **SafetyNet / PredictiveHypo** | Radar hypo + plafonds SMB ; export `meal_rise` / `predictive_hypo_*` **alignés** sur le contrat meal arbre (pas une 3ᵉ vérité sticky) |
| **DecisionPredictionAuthority** | Uplift/retenue de terminal ; `mealEvidence` **doit** inclure le trunk digestion/meal (aujourd’hui manquant) |
| **InsulinStacking / Tube / slew** | Amplitude & anti-whiplash sur la **décision déjà choisie** |
| **HR / HRV / steps** | Effort / recovery — **corroboration faible** éventuelle du meal, jamais lead primaire |

---

## 6. Contrat « certitude repas » (un seul langage)

Pour que Harmonia puisse « renforcer la certitude », un seul objet logique par tick (nom de travail) :

```
MealCertainty {
  tree_state: DIGESTION_ACTIVE | MEAL_PROBABLE | none
  absorption_phase: ...
  rise_geometry: ok | weak | falling
  terminals_agree: ok | pkpd_floor_conflict | hypo_conflict
  effort_veto: bool          // HR/steps/activity
  soft_corroboration: bool   // optionnel: HR≫RHR + IDLE — jamais suffisant seul
  level: NONE | LOW | MED | HIGH
}
```

- Harmonia mappe `level` → force de `MEAL_SUPPORT` vs `STABILIZE` / protective.  
- Auditor lit le **même** objet (ou son export JSON) pour CONFIRM/SOFTEN.  
- `meal_rise_confirmed` sticky actuel doit **dériver** de ce contrat (ou être remplacé), pas vivre en parallèle.

---

## 7. Écart vs code actuel (pourquoi ça ne « s’harmonise » pas encore)

1. **Multi-terminaux** — SafetyNet / stacking / tube / early safety ne lisent pas le même eventual/minPred.  
2. **Authority `mealEvidence` ignore l’arbre** — deux cerveaux meal.  
3. **`meal_rise_confirmed` trop collant** — fausse certitude côté safety.  
4. **Auditor** voit arbre + sim, mais agit surtout **après** coup / cache ; ce n’est pas encore un double-check synchrone de l’intention Harmonia.  
5. **`harmonia_production`** pas toujours dans le JSON Auditor.  
6. **HR** câblé anti-effort, pas comme corroboration meal du contrat §6.

Lots existants qui **servent** ce contrat : H4 / H4b / H4c (partiels), H5 (stabilize), **H6 (harmoniseur + Auditor 2ᵉ vérif)** — à recentrer sur ce document.

---

## 8. Séquençage recommandé (design → code plus tard)

| Étape | Objectif | Livrable |
|-------|----------|----------|
| **D0** | Ce contrat (ce fichier) | Alignement produit |
| **D1** | Spec `MealCertainty` + mapping actions Harmonia | Doc + tests purs (sans pompe) |
| **D2** | H6 resserré : Harmonizer = veto sync ; Auditor = CONFIRM/SOFTEN sur **intention Harmonia** + feuilles | Code + golden prompts |
| **D3** | Brancher Authority / `meal_rise` sur `MealCertainty` | Fin des définitions parallèles |
| **D4** | C1 snapshot terminal (si encore nécessaire après D3) | Un eventual/minPred dose pour tous les gates |

Ordre volontaire : **d’abord harmoniser la décision (cascade)**, ensuite unifier les courbes si les gates restent empoisonnés.

---

## 9. Non-goals

- Remplacer PKPD par l’arbre  
- Faire de l’Auditor le décideur primaire  
- Utiliser le HR comme détecteur repas anticipé (données terrain : lead faible)  
- Affaiblir les veto physiques hypo / maxIOB / sport  

---

## 10. Critères de validation (device + JSONL)

1. Tick `DIGESTION_ACTIVE` + montée : Harmonia `MEAL_SUPPORT` **et** Auditor `CONFIRM` (ou SOFTEN motivé IOB), **sans** crush stacking sur faux floor seul.  
2. Tick hypo amont réel : Harmonia non-meal + Harmonizer/Auditor protecteurs ; pas de bypass meal silencieux.  
3. Bande droite : `STABILIZE` / slew ; Auditor SOFTEN si overcorrection ; pas de yo-yo meal sticky.  
4. Export tick : `physiological_tree` + `harmonia_*` + `MealCertainty` (ou équivalent) + verdict Auditor **lisibles ensemble**.  
5. Marker log suggérés : `CASCADE_MEAL_CERT`, `CASCADE_AUDITOR_CONFIRM`, `CASCADE_VETO=<acteur>`.

---

## 11. Décision ouverte (à trancher avant code D2)

**Q1.** Auditor double-check : **sync borné** vs **async + cache** ?  
**Décision 2026-07-18 :** certitude sync = Harmonizer + `MealCertainty` ; Auditor LLM async/cache ; ne contredit pas un BLOCK sync.

**Q2.** `MealCertainty.HIGH` peut-il ouvrir H4/H4c sans flags sticky ?  
**Décision 2026-07-18 :** **oui** — dériver les ponts du contrat, pas des flags parallèles collants.

---

*Fin du contrat. Aucun changement runtime tant que D1/D2 ne sont pas explicitement demandés.*
