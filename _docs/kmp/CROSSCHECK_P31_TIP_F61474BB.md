# Cross-check clinique tip `origin/dev_OAPSAIMI` — Migration KMP

Relu sur le code du tip. Pas un port. Inventaire study `3269d9f3` / agent `bc-eca121cd` non rejoué ici (SHA study = tip de `kmp-aimi-migration-study` ; `HeartRateTrendIsf` **absent** de cette branche). Reco P3.1 prise telle que formulée dans la mission.

## Tip SHA confirmé

| Ref | SHA | Note |
|---|---|---|
| `origin/dev_OAPSAIMI` (fetch 2026-09-18) | `f61474bb739703c128d9e6a915724c1634ff3957` | = `f61474bb` |
| Ancien tip ancré | `fe96b64f12f2f691ec6cfeeebf5c39bd446bedbc` | ancêtre du tip |
| Delta | **37 commits** | confirmé |
| Study inventaire | `3269d9f39687866fc4e042859ab30c50b010490b` | `kmp-aimi-migration-study` ; **ne contient pas** fe96 ni P3.1 |

Message tip : *Implement calibration handling for Dexcom ONE+ with corresponding request and response parsing*.

---

## Verdict P3.1 : **CONFIRM_WITH_CAVEATS**

Les trois pièces existent sur le tip, avec les formules et call sites cités. **P3.1 first reste OK** pour extraire les objets purs + tests. Ne pas traiter le bundle comme un seul geste always-on dose-strengthening.

### Ce qui est confirmé

**A. `HeartRateTrendIsf`** — `9d0e9bdb9fd91cd50aeb5d624d7523871adce44f`

Fichier : `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/ISF/HeartRateTrendIsf.kt`

| Constante | Valeur |
|---|---|
| `TREND_RATIO` | 1.1 (`hr10/hr60`) |
| `MAX_STEPS_10M` | 100 |
| `MIN_GLUCOSE_MGDL` | 110 |
| `RISE_SUSPEND_MGDL_PER_5MIN` | **11.0** |
| `ISF_MULTIPLIER` | **0.9** (ISF ↓ → dose ↑) |

Conditions (toutes nécessaires) : baseline mesurée, nombres finis, `hr60>0`, steps10 < 100, bg > 110, **Δ5m < 11**, `hr10/hr60 > 1.1`. Sinon `1.0`.

À fe96 c’était inline, **sans** rise-suspend ni baseline réelle :

```
heartRateTrend = avg10/avg60
if (steps10 < 100 && trend > 1.1 && bg > 110) variableSensitivity *= 0.9f
```

Call site unique : `DetermineBasalAIMI2.runPostBasalBootstrapIobTickStepsAndHeartRate` (~L6234). **Pas de `BooleanKey`.** Always-on au sens pref.

`heartRateBaselineIsReal` : `hr60List.isNotEmpty()` ; substitute 80 bpm + catch → `false` (~L6209–6228, L10860). Tests : `HeartRateTrendIsfTest` (11).

**B. Rise-suspend** — même seuil 11.0, même raisonnement (cortisol max empirique 9.1 mg/dL/5 min) aussi dans :

- `StressIsfFloor.RISE_HOLD_MGDL_PER_5MIN` (hold d’un *floor* réduction-only, pref)
- `UndeclaredCobEstimator.HR_GATE_RISE_SUSPEND_MGDL_PER_5MIN`

**C. Gate COB HR** — `5220fc5e2f4a11febe3e5e3dc687dea0f36b3024`

`UndeclaredCobEstimator` : `hrInflammationElevated` ne mute plus si `deltaMgdl5m >= 11`. En-dessous, le gate `hr_inflammation` reste. Commentaire objet : virtual COB **ajouté à `effectiveCOB` (courbes / TBR anticipation), jamais SMB autonome**. Tests dédiés dans `UndeclaredCobEstimatorTest`.

Même commit, **dose-facing sibling** : `MpcController` retire `hr > rhr+5` du `legacyDawnRise` (le HR du matin bloquait la branche Unannounced Meal). Always-on, pas de pref.

### Caveats (pourquoi pas CONFIRM)

1. **Le ×0.9 ne survit pas au stade suivant.** HR trend fait `variableSensitivity *= 0.9`, puis `runBasalAimiTddCarbLimitsTirEarlyBasalAndPaiIsf` **écrase** `variableSensitivity = paiBaseSensitivity` (`sens` fusionné **d’avant** le geste, L6427 / L18089). Le log `HR_TREND_ISF` part ; `sens` des prédictions/SMB part de PAI → endo/activité → clamp/physio. **Même overwrite à fe96.** Porter le câblage tel quel = bit-identique *et* no-op ISF. Corriger l’ordre = changement de comportement vs tip — décision produit, pas du port.

2. **Pas de test COB/carbs sur `HeartRateTrendIsf` lui-même.** Le « gate COB HR » est un objet séparé (relaxation d’un mute protecteur, pas un renforcement ISF).

3. **« Always-on dose-strengthening »** s’applique à l’*intention* du geste A, pas au gate COB (prédiction/TBR) ni à MPC dawn (déblocage UAM, pas ×0.9).

4. **`HeartRateCarryForward`** (même commit 9d0e) : hygiène snapshot HR, 15 min, drop → 0. Alimente `StressIsfFloor` (pref, réduction), **pas** les moyennes `DetermineBasal` du geste A.

---

## P3.2–P3.8 — présence sur tip (esquisse, pas un port)

| Item | Présent ? | SHA d’arrivée (post-fe96) | Pref | Dose-facing ? |
|---|---|---|---|---|
| **P3.2 StressIsfFloor** | oui | `e87cb03e87` + rise-hold `9d0e9bdb9f` | `OApsAIMIStressIsfFloor` **default false** | oui, **réduction-only** (`floorAgainstProfile`, never strengthens). Verdict shadow always-on. Call : `OpenAPSAIMIPlugin` ~L1482 — *autre* ISF (`profile.sens`) que le geste A (`variableSensitivity`). |
| **P3.3 RiseCeiling ↔ DescentRedose** | **asymétrique** | RiseCeiling `9a234151c7` ; DescentRedose **supprimé dans ce même commit** (était à fe96) | `OApsAIMIRiseCeilingGuard` **default false** | RiseCeiling : **oui** (refuse bolus ceiling répété si Δ≥8, 3 ticks). Verdict always computed ; withhold si armed. Session 18.5 : ratio 18.55 non reproductible ; reco disarm. DescentRedose : **absent** du tip. |
| **P3.4 FCL** | oui | `3dd116826a` (`FclMealBasal`) puis `41e59f9bc0` (déclaration + **prebolus one-shot**) | pas de clé FCL-note ; modes meal existants à part | **oui**. Floor basal `FclMealBasal.rateUph` (note `fcl` + TT ≤85, pas sport, bg≥80, Δ > −3). L’objet dit « pas de prebolus » ; `DetermineBasalAIMI2` ~L17022 ajoute `FCL_P1` one-shot (`OApsAIMIautodrivePrebolus`). Porter les deux, pas seulement l’objet. |
| **P3.5 MCER** | oui | latch `335ce308b4` + fix descente pré-repas `11f064dd48` | `OApsAIMIMealConfirmedEarlyRelease` **default false** | **oui** (relâche le floor insulin-only → plus d’autorité précoce). Latch = **hold-off only**, jamais une hausse. |
| **P3.6 TPO** | oui (revert) | `f3de6740ee` + diag `d5f0ca81d6` | session TPO (pas un bool APS) | **prefs**, pas un calcul ISF/SMB. `TpoRevertPolicy` : restore baseline ssi live == overlay. Dose-adjacent si les clés overlay sont dose. |
| **P3.7 ML stale** | oui | `b4f5704564` (`STALE_TRAINING_MS` = 4 h) | train découplé des prefs d’usage | **training path**, pas le tick dose. Force un retry si modèle existant mais silencieux. |
| **P3.8 cal ONE+/Libre3** | oui | `1b81e356c8` + `f61474bb73` (parse Tx/Rx ONE+) | n/a (capteur) | **entrée glucose**, pas APS. ONE+ : queue + opcode calibrate. Libre3 : wiring plugin/calibration math. Indirectement dose-facing. |

Extra post-fe96 **dose-facing** hors liste : `AnticipationBasalFloor` (`bce972221c`, `OApsAIMIAnticipBasalFloor` default **false**, **hausse** basale bornée, note « anticip »). À inventorier à part.

Non cliniques (ignore KMP P3) : Glass / DASHBOARD_V2, versions app, HTML Compose, TDD cache, PKPD Advisor/Tuning (advisor UI).

---

## Commits post-fe96 cliniquement pertinents (court)

1. `e87cb03e87` — StressIsfFloor + DescentRedose (ce dernier retiré plus tard)
2. `b4f5704564` — ML stale 4 h
3. `9a234151c7` — RiseCeiling ; **delete DescentRedose**
4. `335ce308b4` / `11f064dd48` — MCER latch
5. `f3de6740ee` — TPO revert
6. `bce972221c` — AnticipationBasalFloor + meal evidence (opt-in)
7. `9d0e9bdb9f` — HeartRateTrendIsf + rise-suspend + baseline réelle + HR carry-forward
8. `5220fc5e2f` — gate COB HR + MPC dawn sans HR
9. `3dd116826a` / `41e59f9bc0` / `44bb6e6245` — FCL meal basal (+ prebolus one-shot, invariants)
10. `1b81e356c8` / `f61474bb73` — calibration ONE+ / Libre3

---

## Dose-facing vs obs/tools

**Dose-facing (change SMB/TBR/ISF commandé, ou entrée glucose) :** HeartRateTrendIsf *(intention ; persist ISF = caveat 1)*, MPC dawn HR-off, FCL basal+prebolus, RiseCeiling *(si armed)*, StressIsfFloor *(si armed, réduction)*, MCER *(si armed)*, AnticipationBasalFloor *(si armed)*, TPO revert de clés dose, calibration CGM.

**Obs / shadow / tools :** verdicts Stress/RiseCeiling quand pref off ; `HeartRateCarryForward` + export `hr_sample_age_ms` ; TPO diagnostics ; ML stale coordinator ; Advisor/Tuning refactor ; Glass ; `UndeclaredCobEstimator` grams (prédiction/TBR, pas SMB — commenté dans l’objet).

---

## Reco KMP

- **P3.1 first : OUI**, scoped : extraire `HeartRateTrendIsf` + tests, flag `heartRateBaselineIsReal`, gate COB 11.0, et (même famille) le retrait HR du `legacyDawnRise` MPC.
- **Ne pas fusionner** gate COB et ×0.9 ISF dans un seul « always-on strengthening ».
- **Câblage DetermineBasal :** reproduire l’overwrite PAI pour fidélité tip, **ou** demander explicitement si le ×0.9 doit être appliqué *après* PAI (changement clinique vs `f61474bb`).
- Ensuite P3.2 (pref-off, réduction, autre ISF path), P3.4 FCL (objet **et** prebolus P1), P3.5 MCER latch. RiseCeiling = opt-in + session 18.5. DescentRedose = **ne pas reporter**, supprimé sur tip. Cal = lot capteur, pas APS.
