# Inventaire ancre post-P3 — côté clinique uniquement

> Date de lecture : 2026-09-24
> Rôle : ancre AIMI Référence (pas le delta lots / drivers / pompes — c’est KMP Delta)
> **Ancre inventaire post-P3 : GAPS** — tips confirmés ; écarts dose-facing ouverts après l’ancre P3.8. Ce fichier ne réécrit pas [`docs/kmp-migration/STATUS.md`](../../docs/kmp-migration/STATUS.md) ni [`docs/kmp-migration/DELTA-remaining.md`](../../docs/kmp-migration/DELTA-remaining.md) (#117).

Aucune formule n’est inventée. Chaque seuil ci-dessous est copié d’un fichier à un SHA.

## 1. Tips confirmés (`git fetch` 2026-09-24)

| Rôle | SHA demandé | SHA lu | Sujet lu |
|---|---|---|---|
| Tip ref `origin/dev_OAPSAIMI` | `166ddb6db0cec3b5195006db1d5fa77f544f88c3` | **identique** | `feat(garmin): enhance therapy mode handling with new 'sport' mode and FCL temporary target` (2026-09-24 00:10 +0200) |
| Tip study `origin/kmp-aimi-migration-study` | `ce1384814e53a73d1566006b56dd8006f7121559` | **identique** | `docs(kmp): suivi consolidé tip + structure agents (post-P3.8) (#117)` |
| Parent du tip study | `91dc6106` | `91dc6106a484c066dacf69884417968a0ca59a14` | `feat: Implement Aimi retention management system` |
| Ancre clinique P3.8 (dans #117) | `c653fc4485dd985088d9a30a42c99af4e3b285e4` | commit présent, ancêtre du tip ref | `feat(calibration): add new strings for calibration feedback and warnings` |
| Merge P3.8 sur study | `f4ed4e401cb88e8a08907c0c3cec4661cc1094a4` | commit présent, ancêtre du tip study | `P3.8 — calibration health notifications (#115)` |

`git merge-base --is-ancestor c653fc4485 166ddb6db0` : oui.
`git merge-base --is-ancestor f4ed4e401c ce1384814e` : oui.

Fenêtres lues :

- Ref : `git log c653fc4485..166ddb6db0` → **6** commits.
- Study : `git log f4ed4e401c..ce1384814e` → **24** commits (dont le lot d’ancres docs P0.8–P3.8 déjà fusionné, puis les commits code cités dans le brief).

Les histoires ont divergé bien avant P3.8. `git rev-list --count` entre les deux tips (1066 / 2803) n’est **pas** le delta clinique de cette fenêtre. Seules les deux plages ci-dessus sont inventoriées.

## 2. Classement

| Classe | Sens ici |
|---|---|
| ALIGNÉ | Même prédicat ou mêmes constantes numériques aux deux tips, dans cette fenêtre |
| ÉCART_CLINIQUE | Formule, seuil, token ou actionneur de dose présent d’un seul côté |
| HORS_ANCRE | Produit, affichage, export, drivers, pompes — sans changement de dose mesuré dans le diff |
| DÉJÀ_COUVERT_P3 | Contrat déjà ancré P3.1–P3.8 ; pas un lot nouveau |

Dose-facing = le nombre peut changer une basal, une SMB, une cible ou un COB virtuel au tick (ou armer un mode qui le fait). Infra = fichier, cache, rétention, tests, cadence d’entraînement sans formule de tick.

## 3. Commits ref `c653fc4485..166ddb6db0`

| SHA | Sujet | Classe | Nature |
|---|---|---|---|
| `505b848fb6aedb3659b7fc3c0cf0e7371615b728` | tests + module rétention AIMI | ALIGNÉ | infra — porté study par `91dc6106a4` (seuils identiques, §5) |
| `468cf7a03491c9c7eb3a4a84db3b8c3476b5e033` | docs `docs/superpowers/plans|specs/…-aimi-telemetry-retention*` | HORS_ANCRE | documentation seule |
| `6a6561caabed433fe8b7d22c295809cf54077855` | `AwakeRestingHeartRate` + `WorkingIsf` + numérateur basal + `/mode` Garmin + cadence SMB | **ÉCART_CLINIQUE** | dose-facing (trois gestes) + cadence SMB ; Glass = affichage |
| `b7e05f3037e6362d6a7c85f86a2d9abfec87f50b` | facteurs de profil auditeur (ISF / cible) | **ÉCART_CLINIQUE** | dose-facing, opt-in `defaultValue = false` |
| `4b0675549dafbc590427751dd844f4ab90f7155b` | shadow meal-boost / merge basal | HORS_ANCRE | observation ; le diff dit que rien n’applique ces champs à une dose |
| `166ddb6db0cec3b5195006db1d5fa77f544f88c3` | mode `sport` + TT FCL 80 mg/dL | **ÉCART_CLINIQUE** | actionneur dose ; formule FCL elle-même déjà P3.4 |

Aucun de ces 6 commits ne touche `CalibrationMath` / ONE+ / Libre3. Les caveats P3.8 (`entriesForFit`, `isApplicable` de `1b81e356`) ne bougent pas dans cette fenêtre. Ils restent nommés dans [P3.8-ANCHOR](P3.8-ANCHOR.md) et dans DELTA #117 : **DÉJÀ_COUVERT_P3 / HORS_ANCRE**, pas un lot ancre nouveau.

## 4. Commits study `f4ed4e401c..ce1384814e` (code cité)

Les commits `6a56545c7f` … `c9ff5e2f0e` sont les ancres docs P0.8–P3.8 (#81 … #116). **DÉJÀ_COUVERT_P3.** `ce1384814e` est #117 (ce snapshot). On ne le réécrit pas.

| SHA | Sujet | Classe | Nature |
|---|---|---|---|
| `b423b73af36b1837a935d97a5e325288252eba04` | tests PKPD (`androidHostTest` seulement + `AIMI_PORT_STATE.md`) | HORS_ANCRE | tests, pas de formule prod |
| `c6b0e10d837a430071e75525e37556c06a2a6867` | tests safety / SMB | HORS_ANCRE | tests |
| `6d753ebff049bd92cd47540b687389fa72a477a8` | tests APS (fichiers déplacés depuis `_docs/kmp/deferred-tests`) | HORS_ANCRE | tests |
| `0e2ed12bc334d52af3e84be8860052990005c814` | slew `IsfFusion` + plancher IOB stacking + appel `mealModeActive` | ALIGNÉ | rattrapage d’un comportement **déjà sur** `c653fc4485` (`fe719f7efa` et `aaa30588f7` en sont ancêtres). Tokens = tip ref |
| `3dce5b95f1ff133848c21f4d842f5565cfd9c898` | merge `origin/kmp-aimi-migration-study` (`c9ff5e2f`) | DÉJÀ_COUVERT_P3 | pas de formule propre |
| `6a6afc40bbf72a00768a0216bff5470e3b8a3e5c` | gate COB : FC toujours si HR élevée | ALIGNÉ | le tip ref fait déjà ça ; la constante 11,0 n’est plus lue par `estimate()` des deux côtés |
| `ec7783a509f7e16c4d3346bdc9fd5f3db434c30b` | `AimiKeyValueCache` | HORS_ANCRE | cache TTL, pas `Preferences` / pas une dose |
| `91dc6106a484c066dacf69884417968a0ca59a14` | rétention AIMI | ALIGNÉ | infra, seuils = `505b848fb6` |

### 4.1 Preuves d’alignement (pas des lots)

`IsfFusion` aux deux tips : `maxChangePer5Min` défaut `0.03` ; `NOMINAL_TICK_MS = 300_000.0` ; `DOWN_SLEW_GAIN = 1.375` ; `MIN_DOWN_MULTIPLIER = 0.45` ; `MAX_CATCHUP_TICKS = 2.0`.

`InsulinStackingStance` aux deux tips : `IOB_FLOOR_MIN_U = 1.0` ; `IOB_FLOOR_MAX_IOB_FRACTION = 0.26` ; bande prudence `70.0`–`130.0` mg/dL. Le `max(3.2, maxIob * 0.26)` n’est plus le retour de `iobFloorU` côté study après `0e2ed12bc3`.

`UndeclaredCobEstimator.estimate` aux deux tips : `if (input.hrInflammationElevated) return Result.gated("hr_inflammation")` sans test `delta >= 11`. La constante `HR_GATE_RISE_SUSPEND_MGDL_PER_5MIN = 11.0` reste déclarée et **n’est plus appelée** dans `estimate()`. Côté ref ce corps est déjà dans `c653fc4485` (ancêtre `5220fc5e2f`). P3.1 a ancré le gate conditionnel de `f61474bb` (`riseTooFastForHeartRate`) : ce contrat-là est historique. L’état vivant des deux tips est le gate inconditionnel. `HeartRateTrendIsf.RISE_SUSPEND_MGDL_PER_5MIN = 11.0` (geste ×0.9) est inchangé des deux côtés : **DÉJÀ_COUVERT_P3**.

Rétention : `STALE_DAYS = 90`, `DROP_AFTER_DAYS = 7`, `DEFAULT_HARD_CAP_BYTES = 256 * 1024 * 1024`. Blobs policy `75ef3b64f3` (ref) et `f85e504d73` (study) : le diff restant est un paragraphe KDoc commonMain, pas un seuil.

`FclMealBasal` aux deux tips : `MAX_TEMP_TARGET_MGDL = 85.0`, `MIN_GLUCOSE_MGDL = 80.0`, `MAX_FALL_MGDL_PER_5MIN = -3.0`. `therapy.kt` : `FCL_MIN_WINDOW_MS = 60 * 60_000L`. **DÉJÀ_COUVERT_P3** (P3.4).

## 5. Écarts cliniques ouverts

### E1 — Baseline FC éveillée + plancher ISF sur la sensibilité qui dose

- SHA ref : `6a6561caabed433fe8b7d22c295809cf54077855`
- Study : fichiers absents (`AwakeRestingHeartRate.kt`, `WorkingIsf.kt`). Call-site study encore `rhrRestingBpm = stressSnapshot?.rhrResting ?: 0` dans `plugins/aps/src/androidMain/.../OpenAPSAIMIPlugin.kt`.
- Ref : `plugins/aps/src/main/.../physio/AwakeRestingHeartRate.kt`, `.../ISF/WorkingIsf.kt`, `.../ISF/StressIsfFloor.kt`, `OapsProfileAimi.stress_floor_isf_mgdl`.

Tokens lus sur le tip ref :

| Token | Valeur |
|---|---|
| `WINDOW_DAYS` | 7 |
| `AWAKE_FIRST_HOUR` / `NIGHT_FIRST_HOUR` | 6 / 23 |
| `PERCENTILE` | 0.10 (rang ceil) |
| `MAX_STEPS_LAST_15M` | 50 |
| `MIN_SAMPLES` / `MIN_DISTINCT_DAYS` | 50 / 3 |
| `HR_ABOVE_RESTING_BPM` | 20 (inchangé vs P3.2) |
| `REASON_NO_BASELINE` | `"no_baseline"` si FC présente et repos ≤ 0 |
| `WorkingIsf.MIN_MGDL_PER_U` / `MAX_MGDL_PER_U` | 5.0 / 300.0 |
| `ARMED_FLOOR_MULTIPLIER` | 1.0 |
| Clé | `key_aimi_stress_isf_floor`, `defaultValue = false` |

Le seuil +20 bpm est **DÉJÀ_COUVERT_P3** (P3.2). Ce qui change la dose : la baseline n’est plus le repos nocturne publié dans `rhrResting`. Sans baseline honnête, le ref passe 0 et le plancher tombe (`REASON_NO_BASELINE`), y compris un plancher déjà actif, sans grâce. Study compare encore au `rhrResting` du snapshot.

`stress_floor_isf_mgdl` n’est non nul que si `stressVerdict.active && stressFloorArmed`. `WorkingIsf.raiseToStressFloor` / `finalize` font un `max` (la sensibilité ne peut qu’augmenter sur les chemins qui divisent par elle). Le KDoc de `WorkingIsf` borne l’exception réseau SMB à `min(0.05 U, 25 % de la dose)` et la décrit comme chemin legacy contourné. Ce plafond n’a pas été re-mesuré ici hors du commentaire source.

**Dose-facing.** Lot ancre dédié. Ne pas le mélanger avec le ×0.9 de P3.1.

### E2 — Numérateur du boost basal fenêtre repas

- Même SHA : `6a6561caab`.
- Ref : `BasalDecisionEngine` — `sensitivityRatio = preFloorSens / variableSensitivity`, et **pas de boost** si `preFloorCommandedSens` est nul, non fini ou ≤ 0.
- Study : `plugins/aps/src/commonMain/.../basal/BasalDecisionEngine.kt` ligne du ratio = `input.profileSens / input.variableSensitivity`.
- Champ porté : `OapsProfileAimi.pre_floor_isf_mgdl`.

Le commentaire du commit dit que ce correctif **n’est pas** derrière `OApsAIMIStressIsfFloor` : le numérateur commanded après plancher (y compris le plancher 0,5 × profil) grossissait le boost. Study n’a pas `preFloorCommandedSens`.

**Dose-facing**, même lot que E1 (même SHA, même chaîne ISF), hunk basal séparé à relire tel quel.

### E3 — Cadence d’entraînement SMB 24 h

- Même SHA : `6a6561caab`, `AimiSmbTrainer`.
- Ref : `MIN_NEW_ROWS_TO_RETRAIN = 200` (inchangé) puis, sinon, tentative si `nowMs - lastAttempt > STALE_ATTEMPT_MS` avec `STALE_ATTEMPT_MS = 24L * 60 * 60 * 1000L`. Aussi `MIN_TRAINING_SAMPLES = 10`.
- Study : si `newRows < 200`, skip. Pas de `STALE_ATTEMPT_MS`.

Pas une formule de tick. Ça change **quand** le modèle SMB est réentraîné, donc une dose ultérieure possible. Distinct du `STALE_TRAINING_MS` 4 h basal de P3.7.

**ÉCART_CLINIQUE**, nature cadence. Lot court à part, pas dans E1.

### E4 — Facteurs de profil auditeur (ISF et cible)

- SHA ref : `b7e05f3037e6362d6a7c85f86a2d9abfec87f50b`
- Study : aucune occurrence de `AuditorProfileFactor` ni de `key_aimi_auditor_profile_factors`.
- Ref : `.../advisor/auditor/AuditorProfileFactorGate.kt`, `AuditorProfileFactorModels.kt` (`AuditorProfileFactorLimits`), call-sites `DetermineBasalAIMI2` (`evaluateIsf`, `applyIsfFloor`, `evaluateTarget`, `targetForDoseSite`).
- Clé : `OApsAIMIAuditorProfileFactors`, `key_aimi_auditor_profile_factors`, `defaultValue = false`, dépendance `AimiAuditorEnabled`.

Tokens lus :

| Token | Valeur |
|---|---|
| `FACTOR_MIN` / `FACTOR_MAX` | 0.85 / 1.15 |
| `NEUTRAL_BAND` | 0.01 |
| `RAISE_MAX_AGE_MS` | 15 min |
| `PROTECT_MAX_AGE_MS` | 30 min |
| `TARGET_MIN_MGDL` / `TARGET_MAX_MGDL` | 80.0 / 200.0 |
| `RESISTANCE_RATIO` | 0.85 |
| `REQUEST_MIN_INTERVAL_MS` | 15 min |
| Budget cible | `dT <= e * (a / 0.85 - 1)` dans `combinedTargetBudgetMgdl` (`e = bg - target`, `a` = facteur ISF effectif) |
| Schéma | `auditor_profile_factors_v1` |

Clé off : le tick logge l’ombre, la dose n’est pas scalée. Clé on : ISF et cible des sites SMB et basal bougent, dans ces bornes.

**Dose-facing opt-in.** Lot ancre séparé. Hors E1.

### E5 — Garmin `/mode`, mode `sport`, cible temporaire FCL

- Introduction de l’endpoint : `6a6561caab` (`ALLOWED_THERAPY_MODES` sans `sport`, note `"$keyword $duration"`, pas de TT).
- Tip : `166ddb6db0` ajoute `"sport"` (durée défaut **120** min), `FCL_TEMP_TARGET_MGDL = 80.0`, `FCL_TEMP_TARGET_DURATION_MIN = 30`, et la note redevient **le mot-clé seul** (la durée reste sur `TherapyEvent.duration`).
- Fichiers ref : `plugins/sync/src/main/.../garmin/GarminPlugin.kt`, `LoopHub.kt`, `LoopHubImpl.kt`.
- Study : `plugins/sync/src/androidMain/.../garmin/GarminPlugin.kt` existe ; `git grep postTherapyMode|FCL_TEMP_TARGET|ALLOWED_THERAPY` sur ce tip dans ces trois fichiers : **aucune ligne**.

Durées défaut lues sur le tip ref : bfast/lunch/dinner 60, highcarb 90, fcl 30, sport 120, meal 60, snack 30, stop 1. Durée requête bornée `coerceIn(0, 480)` puis, à l’insert, `coerceIn(1, 480)` (stop ≥ 1).

`postTempTarget(80, 30)` n’existe que pour `rawMode == "fcl"`. `FclMealBasal.declared` (déjà aux deux tips) exige `targetBgMgdl <= 85.0`. 80 est sous ce plafond : la montre arme le chemin FCL déjà ancré. `sport` est déjà reconnu par `therapy.kt` des deux côtés (`note.contains("sport")` et pas marche/walk, fenêtre = `event.duration`). Le manque study est l’**actionneur** montre, pas le prédicat FCL.

Le changement de note (`"fcl 30"` → `"fcl"`) ne change pas `contains("fcl")`. La fenêtre FCL reste `max(event.duration, FCL_MIN_WINDOW_MS)`.

**Dose-facing** (impact clinique mesurable). Ce n’est pas un driver CGM : ne pas le ranger avec ONE+ / Libre3. Lot ancre « actionneur thérapie montre », formule FCL non réécrite.

### E6 — Shadow meal-boost (pas un écart de dose)

- SHA : `4b0675549dafbc590427751dd844f4ab90f7155b`
- `CorrectionAggressionBasalCap.evaluateMealBoostCap` et les champs `meal_boost_*` / `engine_rate_uph` / `merge_*`.
- Le hunk `DetermineBasalAIMI2` dit : mesure seule, `FINAL_BASAL_MERGE` inchangé, enregistrement jamais relu pour une dose.
- Les multiplicateurs 10× / 8× du boost ne sont pas modifiés (commentaire seulement). `MERGE_EPSILON_UPH = 1e-9` sert au libellé `engine` / `rt` / `equal`.

**HORS_ANCRE** (observation). Pas un lot clinique. Study peut vivre sans cet export.

## 6. Recommandation

### Lots ancre (clinique, après P3.8)

1. **E1 + E2** — un lot : baseline éveillée, `REASON_NO_BASELINE`, `WorkingIsf`, `stress_floor_isf_mgdl`, numérateur `pre_floor_isf_mgdl`. Source unique `6a6561caab`. Ne pas retoucher `HR_ABOVE_RESTING_BPM`, le ×0.9, ni `FclMealBasal`.
2. **E4** — un lot opt-in auditeur. Source `b7e05f3037`. Clé défaut false à conserver.
3. **E5** — un lot actionneur Garmin (`6a6561caab` + `166ddb6db0`) : `/mode`, `sport` 120 min, TT FCL 80 mg/dL × 30 min, note = mot-clé seul. Ne pas réécrire les constantes 85 / 80 / −3.
4. **E3** — lot court cadence SMB `STALE_ATTEMPT_MS` 24 h, séparé de P3.7.

### Hors-scope ancre (ne pas ouvrir ici)

- E6 export shadow.
- Glass dans `6a6561caab` (chaînes / dashboard) : affichage.
- Rétention `505b848fb6` / `91dc6106a4`, cache `ec7783a509`, tests PKPD/SMB/APS `b423b73a` `c6b0e10d` `6d753ebf`.
- Docs `468cf7a034` et ancres #81–#116.
- ONE+ / Libre3 / `entriesForFit` / `isApplicable` `1b81e356` : déjà nommés P3.8 + DELTA #117, **pas** dans les 6 commits ref de cette fenêtre.
- Pompes iOS Trio, priorisation lots produit : KMP Delta.

### Déjà aligné, ne pas re-porter

Slew ISF, plancher IOB stacking + bande 70–130, gate COB HR inconditionnel, constantes FCL / HeartRateTrend 11,0, politique de rétention 90 j / 7 j / 256 Mio.

## 7. Verdict

**Ancre inventaire post-P3 : GAPS**

Les deux tips demandés sont exacts. Le gate COB, le slew ISF, le plancher d’empilement et la rétention sont alignés aux tips. Il reste quatre écarts dose-facing non couverts par P3.1–P3.8 : baseline FC éveillée et ISF qui dose (E1), numérateur de boost basal (E2), facteurs auditeur opt-in (E4), actionneur Garmin sport + TT FCL 80 (E5), plus la cadence SMB 24 h (E3).
