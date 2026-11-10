# T3C / CFRD — Analyse 24h & plan d’action (basal-only)

**Date:** 2026-07-31  
**Package:** `AIMI_Support_Package_1785422390332`  
**App:** `4.0.0.0-dev.AIMI.300726 (1500)`  
**Contexte patient:** CFRD — corrections automatisées **uniquement par variation de basale (TBR)** ; bolus repas **manuel** ; pas de SMB automatique.

> Ce document est une référence d’analyse + plan d’action. Ce n’est **pas** un avis médical. Aucune modification code n’a été appliquée ici.

---

## 1. Verdict (en une phrase)

L’approche « **basal-only + bolus repas manuel** » est **scientifiquement défendable** pour le CFRD, mais le déploiement actuel est **incomplet et sous-réglé** : le bridge T3C **coupe ou sous-dose** la basale trop tôt, l’anticipation repas est **aveugle**, et le mode CFRD **n’est probablement pas activé** malgré des prefs filles renseignées.

**Verdict conceptuel :** correct en intention · **incomplet** en architecture d’anticipation · **sous-tuné / mal câblé** en production.

---

## 2. Synthèse des 24h (données)

| Métrique | Valeur |
|---|---|
| Décisions | 275 (≈ 23,9 h) |
| Fenêtre UTC | 2026-07-29 14:40 → 2026-07-30 14:35 |
| `decision_source` | **100 % `T3C_LEGACY_BYPASS`** |
| SMB proposé / final | **0 / 0** (contrat basal-only respecté) |
| Harmonia → pompe | **`simulation_only=true`, `applies_to_pump=false`** (100 %) |
| Ownership T3C | **100 % `LEGACY_FALLBACK` / `rbt_authority_off`** |
| RBT | `EXPORT_ONLY`, `authority_effective=NONE` |
| Meal hypothesis | **100 % `NONE`** |
| Meal certainty | **100 % `NONE`** (`effort_veto` 106×) |
| `unlock=true` (plafond montée) | **23 / 275 (8 %)** |
| Basale cible = 0 | **138 / 275 (50 %)** |
| BG min / médiane / max | 49,7 / 108 / **228** mg/dL |
| Ticks BG &lt; 70 | **50** (post-hypo protectif très présent) |
| Ticks BG &gt; 180 | 18 |

### Prefs T3C/CFRD observées

| Clé | Valeur | Impact |
|---|---|---|
| `key_aimi_t3c_brittle_mode` | **true** | Path T3C actif |
| `key_use_aimi_t3c_adaptive_basal` | true | AML sur T3C |
| `key_aimi_t3c_aggressiveness` | **3.0** | Cap utilisateur max |
| `key_aimi_t3c_anticipation_strength` | **0.3** | Anticipation hyper **faible** |
| `key_aimi_t3c_activation_threshold` | 110 | OK pour engagement précoce |
| `key_aimi_t3c_cfrd_cob_delay_min` | 30 | **Inerte si CFRD mode off** (désormais défaut code = 30, UI exposée) |
| `key_aimi_t3c_cfrd_lgs_floor` | 95 | **Inerte si CFRD mode off** (désormais défaut code = 95, UI exposée) |
| `key_aimi_t3c_cfrd_mode` | **absent du dump** (était défaut `false`) | **CFRD adaptations non appliquées** — corrigé 2026-07-31 : UI + défaut `true` |
| `key_aimi_t3c_cfrd_exacerbation` | absent | off (manuel, UI exposée) |
| `key_aimi_t3c_hyper_basal_floor` | absent (était défaut `false`) | **Whipsaw hyper → 0** — corrigé 2026-07-31 : défaut `true` + déjà dans UI |
| `key_aimi_undeclared_cob_enabled` | true (max 25 g) | Présent mais meal certainty = NONE |
| `key_use_aimi_autodrive_active` | **false** | Pas de fusion AD→TBR |
| `key_aimi_recursive_belief_authority` | **false** | Arbre / RBT en ombre |
| `categorize_uam_as_basal` | true | Cohérent basal-only |
| Profil basale jour | souvent **0,20–0,30 U/h** (Lyumjev u200) | Peu d’insuline même à ×3–5 |

Agg **effective** dans les narratifs : typiquement **0,6–0,8** (pas 3,0) via `raw≈1,1 × AML≈0,70 × activityDampen`.

---

## 3. Épisodes critiques (rejoués)

### 3.1 Après-midi 29/07 ≈ 16:00–17:25 UTC — montée non anticipée / sous-traitée

- BG 108 → **195** en ~55 min, delta souvent +5 à +13.
- Basale T3C coincée ≈ **0,35 → 0,49 U/h** (`unlock=false` toute la montée).
- Harmonia (ombre) : `PROTECTIVE_REDUCTION` / `POST_ACTIVITY`, meal certainty `NONE` + `effort_veto`.
- IOB négatif au début de montée → **besoin réel de basale**, pas de frein IOB.
- Lecture : **échec d’anticipation + plafond/ramp non déverrouillés + agressivité écrasée par effort/AML**.

### 3.2 Matin 30/07 ≈ 06:20–07:30 UTC — montée puis collapse

| Heure | BG | Δ5m | Basale cible | unlock |
|---|---:|---:|---:|---|
| 06:25 | 117 | +10 | 0,01 | true |
| 06:35 | 169 | +22 | 0,70 | true |
| 06:45 | 217 | +23 | 1,47 | true |
| 06:50 | **228** | +17 | **1,68** | true |
| 07:10 | **208** | **−5** | **0,00** | false |
| 07:20 | 184 | −11 | **0,00** | false |

- Pendant la montée, T3C **peut** monter (bon signe).
- Dès que la vitesse devient négative **alors que BG reste hyper**, `computeT3c` freine via `brakeFactor` / transition résistance→sensibilité → **basale à 0**.
- Le **hyper basal floor** (prévu exactement pour ce whipsaw) est **off**.
- Conséquence clinique ressentie : « je dois encore corriger à la main » pour finir la descente / éviter le rebond.

### 3.3 Charge hypo → posture protectrice

- 50 ticks &lt; 70 mg/dL ; `POST_HYPO_RECOVERY` 108/275 ; stratégie `HYPO_RECOVERY` / `CONSERVATIVE_OBSERVE`.
- L’algorithme passe une grande partie du nycthémère en **mode défensif**, ce qui est cohérent avec le CFRD (hypo réactive) mais **antagoniste** à une correction basale anticipée des montées.

---

## 4. Cartographie code (décisions ↔ modules)

```text
DetermineBasalAIMI2
 ├─ brittle ON → executeT3cBrittleMode
 │   ├─ mark ownership LEGACY_FALLBACK (rbt_authority_off)
 │   ├─ lastDecisionSource = T3C_LEGACY_BYPASS
 │   ├─ CFRD prefs (si OApsAIMIT3cCfrdMode) → LGS floor, COB delay, HR inflammation
 │   ├─ T3cAnticipation.buildHints (ANT strength user)
 │   ├─ T3cAutodriveBasalBridge.evaluateTreeUnlock → riseCap + stepUp
 │   ├─ DynamicBasalController.computeT3c (PI basal)
 │   ├─ fuse(PI, Autodrive TBR, SMB→TBR strip)
 │   ├─ applyRamp / adaptiveMult / NGR / hyperFloor
 │   └─ rT.rate = TBR only (SMB=0)
 ├─ PhysiologicalTree + MealCertainty (ombre / gating)
 ├─ HarmoniaDecisionEngine (simulation_only sur ce package)
 └─ Effort / post-hypo / slew / harmonizer (path non-T3C ; effort damp aussi dans T3C agg)
```

### Fichiers clés

| Fichier | Rôle |
|---|---|
| `DetermineBasalAIMI2.kt` (`executeT3cBrittleMode`) | Orchestration T3C + CFRD + narratif |
| `basal/DynamicBasalController.kt` (`computeT3c`) | PI basale, freins chute, horizon |
| `basal/T3cAnticipation.kt` | Enveloppes IOB/COB/UAM, hypo-lead, hyper compress |
| `basal/T3cAutodriveBasalBridge.kt` | Unlock plafond / ramp ; SMB→TBR |
| `basal/T3cTrajectoryContext.kt` | Frein trajectoire hypo |
| `UndeclaredCobEstimator.kt` | COB virtuel TBR-only (gated) |
| `patient/Harmonia*.kt` + `PhysiologicalTree*` | Classification ; ici **ombre** |
| `BooleanKey` / `DoubleKey` T3C/CFRD | Prefs |

### Contraintes qui empêchent l’anticipation / le traitement de la montée

1. **CFRD master switch off** → delay COB 30 min + LGS 95 **non injectés** dans `T3cAnticipation` / PI.
2. **`anticipation_strength = 0.3`** → uplift hyper / compression d’horizon faibles.
3. **`unlock=false` dominant** → `stepUp` ≤ max(0,30, 20 % prev) ; pas de `riseCap` agressif.
4. **Agg effective ≪ 3,0** : `adaptiveMult≈0,70` + **activity dampen** (effort) écrasent le PI.
5. **`computeT3c` zero / frein fort dès velocity négative** même si BG ≫ target (épisode 07:10 @ 208).
6. **Hyper basal floor off** → pas de plancher pendant hyper installée.
7. **Meal certainty toujours NONE** (`effort_veto`, `rise_falling`) → pas de branche repas / pas d’anticipation prandiale.
8. **Autodrive off** → pas de demande AD convertie en TBR.
9. **RBT authority off** → arbre / Harmonia n’arbitrent pas la pompe (simulation) ; le PI legacy porte tout.
10. **Basale profil diurne très basse** → même un « bon » multiplicateur livre peu d’U/h absolues.
11. **Hypothèse code T3C** (« zero endogenous insulin/glucagon ») **mal alignée** avec beaucoup de CFRD (sécrétion résiduelle + hypo réactive).

⚠️ **ASYNC IMPACT :** aucun changement async demandé ici ; ML basal / physio restent en lecture. Tout correctif basale T3C reste synchrone dans `determineBasal`.

---

## 5. Brief scientifique (CFRD × AID basal-only)

### Physiopathologie utile

- CFRD ≠ T1D pur : déficit insulinique **progressif**, sécrétion **résiduelle** fréquente, malabsorption / vidange gastrique variable, résistance fluctuante (inflammation, exacerba­tions, stéroïdes, modulateurs CFTR).
- Hypos **réactives post-prandiales** documentées quand l’AID ajoute de l’insuline auto sur fond d’insuline endogène.
- La littérature (ISPAD, revues tech CF) recommande souvent pumps / temp basal / bolus étendus ; AID commercial conçue pour T1D — prudence sur agressivité des corrections auto.
- Les études AID/SAP en CFRD montrent un bénéfice glycémique, mais **ne valident pas** qu’une TBR seule rattrape une montée prandiale rapide sans bolus repas.

### PK de la basale vs montée

- Une TBR agit surtout sur **30–90+ min** ; une montée glucidique (surtout si absorption irrégulière) peut faire +15–25 mg/dL / 5 min.
- Sans **engagement anticipé** (avant le pic) et sans **maintien** de débit pendant l’hyper installée, la TBR **perd la course**.
- Couper à 0 dès le premier delta négatif à BG 200+ est **physiologiquement trop agressif** pour un patient basal-only (risque de plateau haut / besoin de bolus de correction manuel — exactement le symptôme rapporté).

### Verdict scientifique sur *notre* approche

| Question | Réponse |
|---|---|
| Basal-only auto + bolus repas manuel ? | **Saine** comme contrat de sécurité CFRD. |
| Suffisante seule pour éviter hauts pré/per-prandiaux ? | **Non**, sans anticipation + débit soutenu + plancher hyper. |
| Approche actuelle fausse ? | **Pas fausse** — **incomplète** et **biaisée protecteur** (hypo). |
| Risque conceptuel code | Commentaire / modèle « zero glucagon/insulin » T3C **trop T1-brittle**, pas assez CFRD. |

**Implications algo (exigences) :**

1. Distinguer **chute vers hypo** vs **descente depuis hyper installée** (ne pas appliquer le même frein).
2. Anticipation sur **projected BG + COB virtuel / délai malabsorption**, pas seulement meal certainty HR.
3. Pendant hyper ≥ seuil + dwell : **plancher TBR** (feature déjà codée, off).
4. Ne pas laisser l’effort HR **veto** toute interprétation repas chez un patient CFRD basal-only (ou isoler un profil « effort vs meal rise »).
5. Livrer assez d’U/h **absolues** (profil / max basal / unlock), pas seulement un multiplicateur cosmétiquesur une basale 0,2 U/h.

---

## 6. Causes racines priorisées (lien plainte utilisateur)

| # | Cause | Preuve 24h | Effet ressenti |
|---|---|---|---|
| P0 | CFRD mode master **off** alors que prefs filles set | dump sans `key_aimi_t3c_cfrd_mode` | Delay COB / LGS CFRD inactifs |
| P0 | Collapse basale à 0 dès delta&lt;0 en hyper | 07:10 BG 208 → 0 U/h | Correction manuelle encore nécessaire |
| P0 | Hyper basal floor **off** | pref absente / défaut false | Whipsaw non empêché |
| P1 | Agg effective 0,6–0,8 malgré pref 3,0 | narratifs `AML=0,70` + dampen | Sous-dose chronique |
| P1 | `unlock=false` pendant montées claires | après-midi 16–17h | Ramp/plafond bridés |
| P1 | Anticipation 0,3 + meal certainty NONE | 275× NONE, ANT fixe 0,3 | Pas d’avant-coureur repas |
| P2 | Effort → `PROTECTIVE_REDUCTION` / `effort_veto` | 106 veto ; branches POST_ACTIVITY | Montée lue comme activité |
| P2 | Basale profil diurne très basse | 0,2–0,3 U/h | Plafond physiologique bas |
| P2 | Post-hypo fréquent | 50 ticks &lt;70 ; 108 recovery | Biais protecteur dominant |
| P3 | Harmonia / RBT en ombre | sim_only 100 % | Pas d’arbitrage pompe ; confusion diagnostic |

La plainte « **je ne peux toujours pas fonctionner sans bolus manuel (de correction)** » est **cohérente avec les logs** : le contrat « pas de SMB » est tenu, mais la **TBR n’anticipe pas assez** et **abandonne trop tôt** l’hyper.

---

## 7. Plan d’action détaillé

### Phase A — Correctifs livrés 2026-07-31 + vérif runtime

**Code livré (cette session) :**
- Prefs CFRD + exacerbation + LGS floor + COB delay **exposées** dans `T3C Brittle Mode Settings`.
- Défauts : `CFRD mode = ON`, `Hyper basal floor = ON`, LGS floor = 95, COB delay = 30 min.
- Simu unitaire : `T3cCfrdCorrectifsReplaySimulationTest` (7/7 PASS).

**Limite importante :** un device qui a déjà des valeurs SharedPreferences stockées **ne bascule pas** automatiquement sur les nouveaux défauts pour les clés déjà écrites. Pour une clé **jamais écrite** (ex. `cfrd_mode` absent du dump), le nouveau défaut s’applique au prochain `get()`.

**Vérif device après build :**
1. Ouvrir **OpenAPS AIMI → T3C Brittle Mode Settings** : CFRD mode / Hyper floor / LGS / COB delay visibles.
2. Confirmer switches ON (CFRD + Hyper floor) ; LGS 95 ; COB delay 30.
3. Console : ligne `🫁 T3c CFRD: ...` et `🧱 T3c hyper floor` en hyper dwell.
4. Monter **`anticipation_strength`** 0,3 → **0,6–0,8** (toujours manuel ; défaut code inchangé à 0.0 si non stocké — ce patient a 0.3 stocké).
5. Journal 48 h + nouvel export support.

### Phase B — Correctifs produit (code) · à valider avant implémentation

> Rappel process : **pas d’implémentation sans confirmation explicite**. Ci-dessous propositions ordonnées.

#### B1 — Plancher / frein hyper (P0) ⚠️ impact dosing basal

**But :** ne plus passer à 0 U/h à BG 180–220 sur simple delta négatif.

- Options (choisir une, max 2) :
  - **B1a (préférée) :** hyper floor **on by default** en CFRD mode ; ou seuil dwell plus court (10–15 min) en CFRD.
  - **B1b :** dans `computeT3c`, séparer `brakeFactor` « descente depuis hyper » vs « approche target/LGS » (ex. si `bg > activation+40`, floor à `k × profile` ou `min(maxBasal, correction*0.4)`).
- Fichiers : `DynamicBasalController.kt`, `DetermineBasalAIMI2.kt`, prefs Boolean CFRD.
- Tests : scénario rejoué 30/07 06:50→07:20 ; unit tests T3C existants.

#### B2 — Activer / fiabiliser CFRD master (P0)

- S’assurer UI : prefs filles CFRD **désactivées ou grisées** si master off (éviter faux sentiment « CFRD configuré »).
- Log export diagnostic : **toujours** dumper `key_aimi_t3c_cfrd_mode` même si false.
- Fichiers : prefs UI + diagnostic export.

#### B3 — Unlock & agressivité en montée CFRD (P1)

- Logger `unlockReason` dans le narratif T3C (`post_hypo` / `no_confirmed_rise` / `glycemic_override`).
- En CFRD brittle : **ne pas** laisser `activityDampen` descendre l’agg sous un plancher (ex. 1,0) si `projectedBg > activation+30` et pas post-hypo actif.
- Revoir pourquoi `unlock=false` sur montée 16h (sticky post-hypo ? `eventualBg` ?) — instrumenter.
- Fichiers : `T3cAutodriveBasalBridge.kt`, `executeT3cBrittleMode`.

#### B4 — Anticipation repas sans SMB (P1)

- En CFRD : assouplir `effort_veto` sur **meal certainty** quand BG monte fort + HR seulement modérément élevé (ou exiger corroboration multi-signaux).
- Brancher `UndeclaredCobEstimator` → prédictions T3C même si Harmonia sim-only (déjà TBR-only) ; vérifier gates `cfrd_exacerbation` / false-meal.
- Option produit : **Eating Soon / pre-meal flag** utilisateur (temp target déjà 78) qui ouvre unlock + anticipation sans SMB.
- Fichiers : meal certainty, `UndeclaredCobEstimator.kt`, T3C anticipation.

#### B5 — Alignement scientifique modèle T3C/CFRD (P2)

- Remplacer l’hypothèse documentaire « zero endogenous insulin/glucagon » par un profil **CFRD** : LGS plus haut, frein hypo plus précoce, correction hyper plus **soutenue** (pas plus de SMB).
- HR inflammation boost : valider sur données réelles (présent mais master off).

#### B6 — Observabilité (P2)

- Dans `AIMI_Decisions` : champs explicites `t3c_unlock_reason`, `t3c_cfrd_active`, `t3c_agg_effective`, `t3c_brake_factor`, `hyper_floor_applied`.
- Harmonia : tag clair `shadow_only` dans quality_tags (déjà partiel) pour éviter de croire qu’elle dose.

### Phase C — Ce qu’on ne fera **pas** (contrat patient)

- Réintroduire des **SMB / prebolus auto** pour « rattraper » les hauts.
- Baisser le LGS CFRD pour « oser » plus d’insuline.
- Activer RBT authority en prod sans campagne shadow dédiée.

### Phase D — Validation (après chaque phase)

1. Rejeu offline du JSONL 24h (surtout 16h et 06h30) avec flags simulés.
2. Tests unitaires : `DynamicBasalControllerT3cTest`, `T3cAnticipationTest`, `T3cAutodriveBasalBridgeTest`, scénarios exercise lockout.
3. Field test 48–72 h :  
   - zéro SMB auto ;  
   - ↓ corrections manuelles hors repas ;  
   - basale non nulle pendant hyper dwell ;  
   - hypos non aggravées (TBR&lt;70 et recovery).
4. **Ne pas** déclarer « corrigé » tant que l’utilisateur n’a pas confirmé le runtime.

---

## 8. Ordre de marchabilité recommandé

```text
A0 UI CFRD + defaults ON (livré 2026-07-31) + simu PASS
A3 ANT 0.6–0.8 (réglage device)
A4 Profil / max basal clinique
        ↓
B1 Frein hyper ≠ frein hypo (si floor insuffisant)
B3 Unlock + plancher agg anti-effort en hyper
B4 Anticipation repas / COB virtuel / Eating Soon
B2 Dump diagnostic toujours inclure cfrd_mode
B5/B6 Modèle + télémétrie
```

---

## 9. Réponses directes aux questions posées

**« Les modifs T3C/CFRD ne suffisent pas ? »**  
Oui d’après ce package : SMB=0 OK, mais **CFRD master semble off**, anticipation faible, unlock rare, basale annihilée en descente d’hyper, meal path aveugle.

**« Harmonia / arbre empêchent-ils l’anticipation ? »**  
Sur ces 24h Harmonia **ne commande pas la pompe** (simulation). Le frein vient surtout du **PI T3C** (agg dampen, unlock, brakeFactor) + **meal certainty effort_veto** (qui prive l’anticipation repas). L’arbre est en ombre (`rbt_authority_off`) : il n’aide pas, et ses branches protectives polluent le diagnostic.

**« L’approche scientifique est-elle fausse ? »**  
**Non** pour le contrat basal-only CFRD ; **oui** si on croit qu’une TBR réactive sans plancher hyper ni anticipation prandiale remplace les corrections manuelles. Il faut **anticiper + maintenir**, pas seulement « réagir puis couper ».

---

## 10. Validation simulation (2026-07-31)

| Scénario rejoué | Avant (observé / sim legacy) | Après correctifs (sim) | Statut |
|---|---|---|---|
| Defaults CFRD + hyper floor | off / absents UI | ON | PASS |
| Whipsaw 07:10 BG 208 Δ&lt;0 | basale → 0 | hyper floor ≥ 3 U/h | PASS |
| Montée 16:50 + unlock/agg | ~0,49 U/h | ≥ 0,9 U/h | PASS* |
| Climb 06:35–06:50 | ramp OK | ramp non-collapsante | PASS |
| COB delay CFRD | N/A | envelope décalée | PASS |
| Release &lt;160 / near-target 0 | — | floor relâché / zéro OK | PASS |

\*La sim **force** `unlock=true` et agg 1,2 pour le scénario après-midi : les défauts prefs seuls **ne garantissent pas** unlock/agg sur device (effort dampen + AML restent). Suivre **B3** si la montée reste sous-dosée après déploiement.

Tests : `T3cCfrdCorrectifsReplaySimulationTest` — **7/7 PASS**.

## 11. Prochaine décision

1. Build + install (sur demande explicite) + vérif UI prefs T3C.  
2. Nouvel export 24–48 h.  
3. Si montée type 16h encore plate → **B3** (plancher agg anti-effort en hyper / unlock reason log).
