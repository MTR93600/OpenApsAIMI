# VERDICT — Audit contradictoire du rapport « Basal-driven overcorrections → recurrent hypoglycemia »

Audit **read-only**. Aucun fichier produit modifié, aucune préférence changée, aucun commit, aucun
changement de branche. Branche : `feature/dexcom-oneplus-native`. HEAD au moment de l'audit :
`512f00cf33`. Build audité : `bf8973259e` (`4.0.0.0-dev.AIMI.310726`).

Le patient est désigné « l'utilisateur rapporteur ». Aucun nom, e-mail ni identifiant issu des
données transmises n'est reproduit ici.

> **Ce document est un audit technique destiné à révision par les mainteneurs.** Il ne constitue pas
> un avis médical et ne contient aucune recommandation de dosage. Les questions de sécurité
> thérapeutique soulevées relèvent de l'équipe soignante de la personne concernée.

Annexes : [EVIDENCE_INVENTORY.md](EVIDENCE_INVENTORY.md) · `mechanism_stats.txt` ·
`samebuild_stats.txt` · `mass_balance.txt` · `plateau_lift_check.txt`

---

## 1. Verdict exécutif

### **PARTIELLEMENT CONFIRMÉ** — avec une dissociation nette entre deux niveaux

| Niveau | Verdict | Confiance |
|---|---|---|
| **Le mécanisme décrit existe dans le code** (canal basal sous-protégé, garde post-hypo non contraignante, plafond mode repas utilisé comme consigne, learner qui apprend à la hausse sur les rebonds) | **CONFIRMÉ** | **élevée** |
| **Les épisodes E1–E6, les quantités d'insuline, les nadirs, le tick 16:21** | **INDÉTERMINABLE** | — (aucune donnée) |
| **La causalité TBR → hypoglycémie sur cette fenêtre** | **PLAUSIBLE MAIS NON DÉMONTRÉ** | faible |
| **Plusieurs affirmations analytiques centrales** (§4.1 masse d'insuline, §4.5 dosage proportionnel à eventualBG, §4.3 learner prudent, §6 attribution à `bf89732`, §7.1 ticks < 90) | **INFIRMÉES ou TROMPEUSES** | **élevée** |

**Formulation exacte du verdict :** le rapport identifie correctement une **faiblesse architecturale
réelle et démontrable** du canal basal, mais **son dossier de preuves ne peut pas être vérifié** (le
paquet de données annoncé est absent) et **son explication mécanistique est fausse sur un point
central** (le TBR n'est pas proportionnel à `eventualBG`). Sa métrique clé de « déplacement de la
demande vers le basal » est en outre **contredite par ses propres chiffres**.

**Limitation majeure et déterminante :** aucun des sept fichiers de données annoncés au §7 du
rapport n'est présent dans le workspace. Les agents 2, 5 et 9 de la mission ne sont donc pas
exécutables sur les données de la fenêtre. Toutes les conclusions ci-dessous portant sur le
comportement du moteur reposent sur **le code du build exact** plus **deux jeux de données
appareil de substitution**, dont la portée est explicitement bornée au §12.

---

## 2. Ce qui est directement prouvé

### 2.1 La provenance du build — établie (et c'est une limitation levée)

- `Versions.kt` à `bf8973259e` contient exactement `appVersion = "4.0.0.0-dev.AIMI.310726"`.
- Un `Diagnostic_Report.txt` d'appareil réel du 1 août 00:20 affiche
  `App Version: 4.0.0.0-dev.AIMI.310726 (1500)`.
- `git diff bf8973259e..HEAD -- DetermineBasalAIMI2.kt` = **5 insertions / 2 suppressions, toutes
  dans un commentaire `consoleLog`**.

→ **L'analyse du code de l'arbre de travail est valide pour le canal basal du build audité.**

### 2.2 L'asymétrie de protection SMB vs basal — CONFIRMÉE, largement

Treize conditions de mise à zéro dure et ~8 amortisseurs multiplicatifs sont **SMB-only**.
Protections présentes sur SMB et **absentes sur le TBR** :

| Protection | SMB | TBR | Preuve |
|---|---|---|---|
| `isCriticalSafetyCondition` (mise à 0) | **0 dur** | **aucune** | `DetermineBasalAIMI2.kt:12713-12719` — dans `applySafetyPrecautions`, fonction qui retourne un `Float` SMB |
| BG < cible + stable **sans COB** | oui | non | `:13508-13511` |
| COB == 0 | oui | **impossible structurellement** | `BasalDecisionEngine.kt:153` — `cobG = 0.0, // (non utilisé ici)` ; `Input` n'a aucun champ COB |
| contexte `HypoRecovery` | **0 dur** | **aucune** | `:12376-12379` |
| `bolusFactor` (0,5–0,85) | oui | **non** | consommé seulement à `:6979` `insulinReq * bolusFactor` |
| plafonds `maxSMB` / pattern caps | oui | non | `:11978-11990`, `:12241-12250`, `:4146-4150` |
| « eventualBG < cible ⇒ pas d'insuline » (règle oref classique) | oui | **absente côté TBR** | seul arrêt dur : `bgNow <= hypoGuard -> 0.0` (`:11526`) |
| IOB négatif | **aucune garde, sur aucun canal** | — | aucun test `iob < 0` ; seul code bas-IOB est un *plancher* (`:13778`) |

`hypoGuard = min_bg − 0,5·(min_bg − 40)` (`HypoThresholdMath.kt:13`) = **70 mg/dL** pour une cible
100. À BG 91, **rien n'arrête durement le canal basal**.

Le code documente lui-même ce mode de défaillance, `DetermineBasalAIMI2.kt:10866-10870` :

> « Without this, branches like the post-hypo REBOUND_GUARD TBR bridge or the meal boost concentrate
> the (SMB-suppressed) correction into a runaway TBR (**observed 8–9× profile**). »

L'atténuation qui suit (`:10872-10883`) est conditionnée à `exerciseInsulinLockoutActive` seul —
elle **ne couvre pas le cas hors exercice**, qui est celui du rapport.

### 2.3 Le « bridge » REBOUND_GUARD est calculé puis non contraignant — CONFIRMÉ, code + production

**Cause racine identifiée : un problème d'ordre des opérations.** Le plafond
`capBasalRateForCorrectionAggression` (cap-only, `CorrectionAggressionBasalCap.kt:31`) est appliqué à
`DetermineBasalAIMI2.kt:7829`, **avant** `setTempBasal`, dans lequel
`DynamicBasalController.calculateDynamicRate` multiplie le taux par **[0,0 … 10,0]** à `:11486-11493`,
puis `AdaptiveBasal` à `:11542`, puis l'ampli endocrine à `:11576`. Un plafond posé en amont d'un
multiplicateur ×10 ne borne pas la valeur finale.

**Vérification sur données de production réelles** (jeu de contrôle même build) : sur les **8 ticks
sur 8** où la narration imprime une valeur de bridge `BasalBoost`, la TBR finale diffère — et dans
7 cas sur 8 elle est **2,3 à 3,4× supérieure** au bridge annoncé :

| Heure | BG | IOB | `BasalBoost` narré | **TBR finale** |
|---|---|---|---|---|
| 31/07 20:32 | 85,6 | 7,27 | 1,13 | **3,09** |
| 31/07 20:37 | 99,7 | 7,18 | 1,13 | **3,55** |
| 31/07 20:42 | 106,4 | 7,13 | 1,13 | **3,50** |
| 31/07 20:47 | 118,9 | 7,08 | 1,29 | **3,81** |
| 01/08 00:02 | 76,8 | 2,24 | 1,13 | **0,00** |
| 01/08 00:07 | 81,0 | 1,95 | 1,13 | **2,56** |
| 01/08 00:12 | 85,7 | 1,94 | 1,13 | **2,74** |
| 01/08 00:17 | 89,3 | 1,91 | 1,13 | **2,61** |

Le profil de ce patient est 0,45–0,60 U/h : les TBR de 2,56–3,81 U/h à BG 81–119 valent **4,3 à
6,4× le profil**. Le motif du rapport (bridge annoncé ~1,1, TBR finale bien supérieure) est donc
**reproduit sur un autre patient, même build** — ce n'est pas une lecture erronée d'un tick isolé.

Nuance de traçage à porter au crédit d'une lecture rigoureuse : sous le tier `REBOUND_GUARD`,
`allowRocketBasalScale == false` ⇒ la fusion est `min(engine, rT.rate)` et le plafond est 1,5× le
profil ; le bridge est donc **fonctionnellement cap-only** malgré une écriture de type « boost ».
C'est précisément pourquoi il ne peut pas relever le taux — et pourquoi il ne peut pas non plus
le contenir une fois les multiplicateurs aval appliqués.

### 2.4 Le plafond `meal_modes_MaxBasal` est une **consigne exacte**, pas un plafond — CONFIRMÉ

`DetermineBasalAIMI2.kt:2450-2451` puis `:14885-14899` :

```kotlin
val modeTbrLimit = if (mealLimitPref > 0.1) mealLimitPref else profile.max_basal
fun manualMealModeTbr(runtimeMin: Long, logTag: String, overrideSafetyLimits: Boolean) {
    if (runtimeMin < 0 || runtimeMin >= 30) return
    val rateUh = modeTbrLimit.coerceAtLeast(0.05)
    setTempBasal(rateUh, 30, …, forceExact = true, adaptiveMultiplier = 1.0)
```

`forceExact = true` retourne à `:11374-11393` **avant** `DynamicBasalController`, `maxSafe`, le clamp
`max_basal`, `AdaptiveBasal` et le gouverneur endocrine. De plus,
`bypassSafety = (overrideSafetyLimits || isMealMode || isEarlyAutodrive) && bgNow > hypoGuard`
(`:11522`) remplace le plafond `maxSafe` par `profile.max_basal` (`:11537`).

**Le §4.6 du rapport est confirmé au niveau du code : ce n'est pas un plafond, c'est le taux
d'exploitation, réaffirmé à chaque tick pendant les 30 premières minutes de tout mode repas.**
Le défaut par défaut de `meal_modes_MaxBasal` est **1,0 U/h** (`DoubleKey.kt:408`, plage 0,05–25) :
la valeur 8,5 est une **configuration utilisateur**, mais le code l'utilise comme consigne exacte.

Trois signatures indépendantes concordent avec le récit E3 (8,5 U/h pendant ~29 min à BG 117–129) :
taux = valeur configurée exactement · durée ~29–30 min (`runtimeMin in 0..29`) · trou JSONL ~30–35 min.

### 2.5 La censure de télémétrie — CONFIRMÉE (et plus grave que décrit) pour les modes repas

`appendAimiDecisionsJsonlLine` n'est atteint que depuis **deux** points (`:15678` T3C et `:16338`
queue principale). **Treize retours anticipés** plus deux bypass `AimiLoopTickRecovery` n'exportent
rien. `logDecisionFinal` n'écrit **jamais** dans le JSONL.

Conséquence directe : **la classe de décision au basal le plus élevé est exactement celle qui n'est
jamais exportée.** Les 30 premières minutes de tout mode repas retournent à `:15659`, 679 lignes
avant l'export, et c'est le même chemin qui écrit la TBR à `meal_modes_MaxBasal`. Le trou de
~35 min autour de 19:10 est donc **structurellement expliqué** — ce n'est pas une ligne perdue,
c'est une exclusion déterministe.

Un correctif antérieur (`9f979b9698`, « empty JSONL bug ») a traité **un seul** des quatorze
retours ; la classe de défaut reste ouverte sur les treize autres.

### 2.6 BasalLearner : apprentissage à la hausse sur les rebonds — CONFIRMÉ

- **Formule** (`BasalLearner.kt:110-115`) : `short×0,40 + medium×0,35 + long×0,25`, clampée [0,70 ; 2,0].
  Vérification : `0,7×0,40 + 2,0×0,35 + 2,0×0,25 = 0,28+0,70+0,50 = **1,48**`. **Le 1,48 du rapport
  est arithmétiquement confirmé.** Clamps réels : 2,0 par terme *et* sur la sortie ; plafond effectif
  vers le basal = `OApsAIMIMaxMultiplier` (défaut **1,6**).
- **Aucune exclusion des fenêtres post-hypo.** `process()` (`:120-137`) ajoute chaque échantillon aux
  deux buffers **sans condition** — pas de plancher BG, pas de flag hypo, pas de test d'âge post-hypo.
  `grep hypo|rebound|blackout|exclud` sur `BasalLearner.kt` ne trouve que la KDoc de `onHypoDetected`.
- **Asymétrie structurelle** : un rebond post-hypo est haut-et-montant, donc `updateMediumTerm` prend
  la branche `avgBg > 150 && weightedError >= -0.5 -> ×1,12` (`:230`), avec `TAU_MEDIUM_MS = 3 h` qui
  **surpondère justement les échantillons de rebond récents**. L'hypo elle-même n'atteint que le terme
  court, à ×0,90 — soit **≈ −4 % sur la combinaison** (poids 0,40).
- **Pas de récupération** : `onHypoDetected` n'a **ni fenêtre, ni timer, ni décroissance** ; la
  réduction est persistée. Pire, `updateShortTerm` pose `adjustment = 1,0` quand `|weightedError| ≤ 0,5`,
  ce qui rend `ema(prev, prev, 0,25) = prev` — un **no-op algébrique**. La réduction ne se défait
  que si une tendance montante apparaît. `UnifiedReactivityLearner` documente et corrige exactement
  cette « zone gelée » (`:695-706`) ; `BasalLearner` n'a **aucun équivalent** sur ses trois termes.
- **`onPersistentHyper()` est du code mort** (`:188-193`, aucun appelant) : l'événement hyper ne se
  déclenche jamais, l'événement hypo si. L'asymétrie est donc dans le sens de la sur-délivrance.
- Le multiplicateur **atteint bien la pompe** (`:1611-1630` → `:7944` → `:11542-11566`), sauf quand
  T3C ou Harmonia possède le basal (`:7865`, `:7872` forcent 1,0).

### 2.7 dynISF / TDD : le signe de la boucle est établi mathématiquement

`tddIsf = 1800 / TDD24h` ⇒ `∂tddIsf/∂TDD < 0`. Dans `IsfFusion.fused`, tous les termes autres que la
constante `profile` sont non décroissants en `tddIsf` ⇒ **`∂fusedIsf/∂TDD24h ≤ 0`** : plus d'insuline
sur 24 h ⇒ ISF plus bas ⇒ correction plus grande. **Établi comme propriété du code.**

Le TDD inclut **le basal délivré (TBR réel échantillonné à 5 min), les SMB automatiques et les bolus
manuels**, avec **aucune exclusion** — ni suspension, ni fenêtre hypo, ni exclusion de l'insuline qui
a causé l'hypo (`TddCalculatorImpl.kt:136-173`). Le §4.2 du rapport est donc correct sur ce point.

Deux canaux **saturent** (`tddIsf ≥ 0,5·profileIsf`, `PkPdIntegration.kt:489-493`), ce qui borne le
gain de boucle. Mais un troisième **ne sature pas** : `ratio = tdd2Days / tdd24Hrs`
(`OpenAPSAIMIPlugin.kt:1018-1021`), appliqué en `sens × ratio` (`:9031`, `:5370`), **sans aucun clamp
ni lissage**.

Défauts adjacents établis, non signalés par le rapport et plus préoccupants que ce qu'il décrit :
- **Le limiteur par appel de la fusion ISF est asymétrique** : avec les défauts livrés
  (`OApsAIMIIsfFusionMaxChangePerTick = 0,4`), `maxUp = 1,40·prev` mais
  `maxDown = (0,85 − 0,40)·prev = **0,45·prev**` (`IsfFusion.kt:53-58`) — −55 % vers le bas contre
  +40 % vers le haut, biais vers plus d'insuline. Interaction probablement non intentionnelle entre le
  terme `0,85 −` et le défaut 0,4.
- **Un appel précoce passe un faux TDD** : `DetermineBasalAIMI2.kt:1981` passe
  `ctx.profile.max_daily_basal * 24.0` comme TDD dans un objet `IsfFusion` **à état** (`lastIsf`),
  ce qui **cliquette la valeur avant** l'appel au vrai TDD (`:8904`) — 4 appels par tick sur la même
  instance.
- **Le sens de l'autosens est incohérent** : les maths de dosage utilisent `sens × ratio`, l'export
  JSON utilise `1,0 / ratio` (`:8241-8245`). Les deux ne peuvent pas être justes.
- `physioMultipliers.isfFactor` (borné 0,85–1,15) est appliqué **jusqu'à 3 fois par tick**
  (`OpenAPSAIMIPlugin.kt:1284`, `:810`, `DetermineBasalAIMI2.kt:5329`) — pire cas 0,85³ ≈ 0,61.
- `dynamicDeltaCorrectionFactor` peut multiplier l'ISF par ≈0,05 sur une montée rapide
  (`OpenAPSAIMIPlugin.kt:659-684`), **non borné en bas** hors clamp absolu 5,0.

### 2.8 L'Auditeur n'agit que sur le SMB — CONFIRMÉ par les données ET par le code

Les 8 enregistrements `auditor_followup` du jeu de contrôle portent `advisory_only: true`, une
latence de **14–27 s** après la décision parente, et des verdicts explicites :

```
"verdict": "SOFTEN", "reason": "Verdict: SOFTEN - SMB reduced by 35%",
"smb_u": 0.487, "tbr_uph": 3.421, "tbr_min": 30
```

La réduction porte sur le SMB ; le `tbr_uph` reste à 3,42–4,11 U/h. Côté code,
`getModulationMode()` vaut **`AUDIT_ONLY` par défaut** (`AuditorOrchestrator.kt:494`) et les
ajustements bornés sont unidirectionnels (adoucissement). Arrivant 15–27 s après la décision,
l'auditeur ne peut de toute façon pas lier le tick qu'il audite.

---

## 3. Ce qui est probable mais non prouvé

1. **Que les TBR de 4,2–8,5 U/h ont réellement été délivrés par la pompe.** Aucune donnée pompe,
   aucun `basal_delivery_segments.csv`, aucun export Nightscout. Le rapport reconnaît lui-même
   reconstruire la période après 22:00 depuis des **TBR suggérés**. Ce qui est établi : le moteur
   *demande* de tels taux (jeu de contrôle : max 9,00 U/h ; CSV 30 465 lignes : max 10,00 U/h, 18,3 %
   des ticks > 4 U/h). *Demandé ≠ délivré.*
2. **Que les TBR élevés ont causé les hypoglycémies.** Direction temporelle plausible et
   cinétique compatible avec Lyumjev, mais dans le jeu de contrôle, sur 31 ticks à TBR > 4 U/h avec
   suivi 60–95 min, la variation médiane vers le nadir est **−48 mg/dL** et **4/31 seulement**
   descendent < 80, **0/31** < 70. Une association existe donc, mais elle n'est ni déterministe ni
   suffisante pour attribuer les 7 hypos de la fenêtre à ce mécanisme, d'autant que ~17,3 U de
   bolus/SMB (dont 3,5 U manuels) circulaient en parallèle.
3. **Que la boucle interjournalière TDD → ISF → surdélivrance s'est réellement fermée.** Le signe de
   chaque lien est établi (§2.7) ; le **gain de boucle** sur plusieurs jours ne l'est pas, et deux
   des trois canaux saturent. 30 h ne suffisent pas — et ici même ces 30 h manquent.
4. **Que l'oscillation autosens 0,41 → 1,15 en 10 min a eu lieu.** Le mécanisme est présent et non
   gardé (recalcul intégral chaque tick, sans EMA ni clamp, plus une division par `brainFactor` ∈
   [0,5 ; 1,5] pouvant produire un facteur 3). L'événement n'est pas vérifiable.
5. **Que `updateMediumTerm` a effectivement pris la branche ×1,12 sur ces rebonds.** Vérifiable via
   `mediumUpdateCount` / la ligne de log `BasalLearner: Medium-term update. AvgBG=…` (`:248-249`),
   absents du dossier.

---

## 4. Ce qui est faux ou trompeur

### 4.1 « La demande est re-routée vers le basal, qui a délivré les surdoses » — **inversé par ses propres chiffres**

Recalcul à partir des chiffres du rapport lui-même (§1) : fenêtre = 29,93 h.

| | U | part de l'insuline au-dessus du basal profil |
|---|---|---|
| Basal délivré | 34,9 | — |
| Basal profil (0,75 × 29,93 h) | 22,45 | — |
| **Excès basal** | **12,45** | **42 %** |
| **Bolus + SMB** | **17,3** | **58 %** |

**Le canal bolus/SMB a délivré plus d'insuline au-dessus du basal profil que le canal basal
(17,3 U vs 12,45 U).** Les 13,8 U de SMB automatiques représentent à eux seuls **77 % d'une journée
de basal profil** (18 U). Le même rapport se retrouve dans le jeu de contrôle même build :
**39 % basal / 61 % SMB** (basal 31,8 U vs profil 12,8 U ⇒ excès 18,9 U ; SMB/bolus 29,3 U).

La description « le canal SMB est throttlé pendant que le basal délivre les surdoses » est donc
**factuellement inexacte en masse**. Ce qui est vrai — et que les données confirment — est
différent : le SMB est **fréquemment mis à zéro** (67,1 % des ticks portent `SMB=0` dans le jeu de
contrôle) **mais délivre beaucoup quand il tire** (29,3 U sur 72 ticks, max 2,20 U/tick). C'est un
canal **intermittent**, pas un canal atténué.

### 4.2 « eventual BG est instable au point d'être indosable » + « tout canal qui dose proportionnellement à ce signal va dépasser » — **prémisse confirmée, mécanisme infirmé**

L'instabilité est **confirmée et même sous-estimée** (CSV 30 465 lignes) : `|ΔeventualBG|` entre
ticks consécutifs — médiane 10, **p90 = 96**, max 362 mg/dL, **20,9 % des ticks > 50**, 9,2 % > 100 ;
24,8 % des valeurs collées au plancher 39 ; 3,0 % ≥ 390.

Mais **le taux basal n'est pas proportionnel à `eventualBG`**. Dans `BasalDecisionEngine`, chacune
des 16 occurrences d'`eventualBg` (`BasalDecisionEngine.kt:143,162,209,234,246,252,254,276,295,299,345,387,477,508,533,572`)
est **un gate booléen, une chaîne de log, ou un champ mort** : `predictedBgOverride` (`:572`) n'est
jamais lu par `DynamicBasalController.compute` ; `LoopContext.eventualBg` (`:162`) n'est jamais lu par
`BasalPlanner`. La transformation dominante est
`DynamicBasalController.calculateDynamicRate` (`DetermineBasalAIMI2.kt:11486`), dont le multiplicateur
est `f(bg − targetBg, delta, shortAvgDelta)` — fonction du **BG courant** et de la **pente**.

**Formulation correcte :** `eventualBG` est **liant comme seuil de branchement** (`eventualBg > 110`,
`eventualBg > 120 && delta > 3`), pas comme facteur proportionnel. C'est ce qui explique la
bimodalité observée et les bascules : sur **62 paires de ticks quasi simultanés à BG identique**, un
écart d'`eventualBG` > 50 fait basculer la TBR finale de ~0,14 à 5,6–7,0 U/h (ex. 20/04 23:10,
BG 219,7 : ev 122→180, TBR 0,13→7,00). Le franchissement de seuil, pas la proportionnalité.

Cette distinction n'est pas cosmétique : elle **invalide la piste corrective n°2 du rapport**
(amortir le signal avant qu'il « se multiplie dans la demande basale »). Ce qu'il faut traiter est
l'**hystérésis des seuils** et l'ordre des opérations (§2.3), pas un gain proportionnel qui n'existe pas.

### 4.3 « Les deux learners ont des conclusions opposées, et le prudent ne gouverne pas le canal qui délivre » — **seconde moitié infirmée**

`UnifiedReactivityLearner` atteint **bien** le canal basal, par deux routes :
- `DetermineBasalAIMI2.kt:5214-5231` : `(basalaimi * multiplier * reactivity)` — branche
  grossesse/TIR ;
- surtout `OpenAPSAIMIPlugin.kt:1042-1043, 1097-1103` :
  `brainFactor = unifiedReactivityLearner.getCombinedFactor()`, puis
  `autosensResult.ratio = originalRatio / brainFactor`. Ce ratio scale ensuite **le basal, les
  cibles, l'IOB et l'ISF** (§2.7).

Le learner prudent gouverne donc bien plus que le SMB. L'affirmation du §4.3 est **fausse en tant
qu'énoncé architectural**. (Plancher réel : `FACTOR_MIN = 0,5` dans `ReactivityDaypart.kt:25` —
le « plancher 0,5 » du rapport est exact ; le SMB re-clampe à [0,60 ; 1,60],
`SmbInstructionExecutor.kt:262`.)

### 4.4 « La nouvelle préférence `bf89732` » — **attribution fausse**

`git show bf8973259e --stat` = **un fichier, 6 insertions / 5 suppressions**, dans
`compose/PkpdSettingsUi.kt`. Le commit **déplace** un interrupteur existant de la section Expert
vers Advanced. **Zéro changement algorithmique, aucune nouvelle clé, aucun défaut modifié.**

Le comportement a été introduit par **`32028d804b`**, 7 h 32 plus tôt le même jour, qui a ajouté la
`BooleanKey`, les trois `preferences.get(...)`, le prédicat moteur et un interrupteur fonctionnel.
`git diff bf8973259e HEAD -- AdvancedPredictionEngine.kt` est **vide**.

La **conclusion** du §6 (« Guard B ne peut pas corriger ce défaut ») est juste — voir §11 — mais le
raisonnement est bâti sur un commit qui ne fait rien, et « l'utilisateur a activé la préférence puis
n'a observé aucun changement » ne teste pas ce que le rapport croit tester.

### 4.5 « Les ticks < 90 mg/dL sont absents de `AIMI_Decisions.jsonl` » — **trop général**

Dans le jeu de contrôle même build : **34 ticks < 90 mg/dL exportés, 10 < 80, minimum 71,5 mg/dL.**
L'export sous 90 fonctionne donc sur ce build.

Ce qui est réel est **sélectif** : trois portes censurent spécifiquement les ticks bas **en
descente** — LGS TIER1 (`bg < lgsTh`, ou `bg < 70 && delta < 0`), l'arrêt basal hypo
(`dropPerHour ≥ 65 && delta < 0 && bg < 85`), et surtout **HARD_BRAKE** qui exige
`falling && decelerating && bg < targetBg + 10` (`:2733-2737`). Un minimum exporté à 90,2 est
exactement ce que produit `targetBg ≈ 80` — cohérent, mais non prouvé (le `targetBg` runtime n'est
pas déductible du code).

Le biais est donc **réel et pire que décrit sur un point** (il frappe précisément les descentes,
c'est-à-dire les pires moments) **et surestimé sur un autre** (ce n'est pas un filtre BG global).
Deuxième biais non relevé par le rapport : la porte d'exercice (`:15646`) ne laisse passer que les
ticks `BG > 220` dès qu'un contexte d'activité est actif — le jeu exporté est biaisé deux fois vers
l'hyperglycémie. **Toute statistique de fréquence calculée sur ce JSONL hérite de ce biais, y
compris les « 59 % », « 65 ticks », « 43 ticks » du §4.1 du rapport.**

### 4.6 Incohérences arithmétiques internes

| Affirmation | Recalcul | Écart |
|---|---|---|
| basal profil = 21,3 U | 0,75 × 29,93 h = **22,45 U** | −1,15 U ; 21,3 U implique 28,4 h, pas la fenêtre déclarée |
| E4 : « 4,90 U/h soutenu ~2¼ h » + « 3,5 U extra » | 3,5 / (4,90−0,75) = **51 min** | (4,90−0,75)×2,25 = 9,34 U ≠ 3,5 U. La colonne « durée » décrit une **fenêtre**, pas une durée de délivrance |
| « 7 dips < 70 » vs « 8 épisodes/24 h, charge 9,5 % » | deux mesures différentes (comptage graphique vs métrique interne du learner) | ne doivent pas être présentées comme concordantes |

Les colonnes « Extra insulin » par épisode sont en revanche **internement cohérentes** avec les taux
et durées annoncés (E1, E3, E5, E6 vérifiés).

### 4.7 « fused ISF 16–39,5 contre un profil à 74 » — comparaison non fondée

Trois problèmes :
1. La narration citée par le rapport dit elle-même **`profile=39`**, pas 74. Le terme `profile` de la
   fusion n'est pas l'ISF statique du profil.
2. L'ISF appliqué est `min(pkpdRuntime.fusedIsf, profile.variable_sens)`
   (`DetermineBasalAIMI2.kt:8795-8805`) — **deux pipelines distincts**, et la branche la plus
   agressive gagne toujours. Le rapport ne distingue pas laquelle il cite.
3. **16 est en dessous du plancher dérivable du pipeline 1** (`fusedIsf ≥ 0,30 × profileIsf ≈ 22`) :
   les deux chiffres ne peuvent pas venir du même appel. Soit ils mélangent les pipelines, soit le 16
   est le `variable_sens` appliqué — qui, lui, n'a **aucun plancher relatif au profil**.

Avec `dynisf_adjust_sensitivity` **activé** (le rapport le confirme), un ISF appliqué nettement sous
l'ISF statique du profil est le comportement **conçu**, pas une anomalie. La question de la
*magnitude* reste ouverte et légitime ; le cadrage « effondrement à 2–4× l'agressivité du profil »
ne l'est pas.

---

## 5. Vérification chiffrée

Légende : **[P]** donnée primaire · **[R]** recalculé · **[C]** confirmé par le code · **[X]** non vérifiable (données absentes)

| Affirmation du rapport | Valeur rapportée | Valeur recalculée / vérifiée | Verdict | Source |
|---|---|---|---|---|
| Build | `4.0.0.0-dev.AIMI.310726` | identique | **confirmé [P][C]** | `Versions.kt` @ `bf89732` + `Diagnostic_Report.txt` |
| Commit `bf89732` = HEAD | HEAD | HEAD = `512f00cf33` ; `bf89732` à −3 commits | **partiellement faux [C]** | `git log` |
| `bf89732` = nouvelle préférence Guard B | oui | commit **UI uniquement** ; comportement = `32028d804b` | **faux [C]** | `git show bf8973259e --stat` |
| Fenêtre | 06:24 → 12:20 | 29,93 h | confirmé | — |
| Basal profil sur la fenêtre | 21,3 U | **22,45 U** | **écart −1,15 U [R]** | 0,75 × 29,93 |
| Basal délivré | 34,9 U | — | **non vérifiable [X]** | aucun segment de délivrance |
| Excès basal / part | (implicite : dominant) | **12,45 U = 42 %** | **inversé [R]** | §4.1 |
| Bolus + SMB | 17,3 U (3,5 manuels) | **= 58 % de l'excès** | **contredit la thèse [R]** | §4.1 |
| Temps à 0 U/h | 17,3 h (58 %) | 57,8 % de 29,93 h — cohérent | cohérent [R] | — |
| Max BG fenêtre | 164 | ~163–164 sur le graphique | cohérent [P] | `overview_30h.png` |
| 7 dips < 70 ; 2 < 54 ; nadir 50 | — | compatible visuellement | **plausible, non re-dérivable [X]** | graphique seul |
| TBR max 8,5 U/h | 8,5 | barre unique ~8,5 vers 19:30 ; moteur capable de 9,0 [P] / 10,0 [P] | **suggéré confirmé ; délivré non prouvé [X]** | jeux de substitution |
| `meal_modes_MaxBasal` utilisé comme point d'exploitation | oui | **`forceExact = true`, consigne exacte, 30 min** | **confirmé [C]** | `:14885-14899`, `:11374-11393` |
| ISF(fused) 15,8–39,5 vs profil 74 | oui | jeu de contrôle : 16–47, médiane 28 — mais profil de *ce* patient 10–19 | **non vérifiable pour le patient ; comparaison mal posée [C]** | §4.7 |
| `Basal× 1,48` depuis 0,7/2,0/2,0 | 1,48–1,50 | **1,48 exactement** | **confirmé [C]** | `BasalLearner.kt:110-115` |
| clamp learner = 2,0 | oui | 2,0 par terme et combiné ; plafond effectif 1,6 | **confirmé + précisé [C]** | `:95-96`, `DoubleKey.kt:451` |
| `React×` au plancher 0,5 | 0,5 | `FACTOR_MIN = 0,5` existe ; contrôle : 0,66–0,83 | **plancher confirmé [C] ; état patient non vérifiable [X]** | `ReactivityDaypart.kt:25` |
| eventualBG 39 → 152 → 261 → 42 | instable | **p90 = 96 ; 20,9 % > 50 ; 24,8 % à 39** | **confirmé et sous-estimé [P]** | CSV n=30 465 |
| bridge REBOUND_GUARD 1,10 → TBR 5,25 | oui | **8/8 ticks : bridge ≠ finale, 7/8 en hausse ×2,3–3,4** | **confirmé [P][C]** | §2.3 |
| `AdaptiveBasal ×0,70` = seul réducteur liant | oui | 0,70 est le **plancher** de `hMult` ; appliqué **après** les clamps | **confirmé + aggravé [C]** | `:1617-1618`, `:11542` |
| ticks < 90 absents du JSONL | oui | **34 ticks < 90, min 71,5** exportés (même build) | **trop général [P]** | §4.5 |
| trou JSONL ~19:10 | oui | exclusion déterministe de 30 min sur mode repas | **confirmé [C]** | §2.5 |
| `Auditor: STALE` = protection désactivée | implicite | advisory-only, `AUDIT_ONLY` par défaut, latence 14–27 s | **observation vraie, inférence fausse [P][C]** | §2.8 |
| Guard B incapable d'agir sur ce défaut | oui | **confirmé** (3 preuves indépendantes) | **confirmé [C]** | §11 |

---

## 6. Analyse des épisodes E1 à E6

**Aucun épisode n'est vérifiable.** Il manque : la série CGM, les segments de basal délivré, les
bolus, les traitements Nightscout et les ticks JSONL de la fenêtre. Ce qui suit est donc limité à
(a) la cohérence interne des chiffres du rapport, (b) la lecture du graphique, (c) la
**productibilité** du scénario par le code.

Degré de causalité selon l'échelle demandée — **aucun épisode ne dépasse « possible »**, faute de
données d'insuline.

| Ép. | Cohérence interne | Lecture graphique | Mécanisme code identifié | Causalité |
|---|---|---|---|---|
| **E1** 11:30–14:10, pic 148, 12,2 U (8,7 auto), double nadir 55 puis 50 | cohérente | creux ~55 vers 14:15 et ~50 vers 15:55 confirmés visuellement | 3 U + 0,5 U manuels **déclarés** + chaîne SMB : l'insuline bolus domine ici | **possible** — 3,5 U manuels au petit-déjeuner sont un facteur alternatif majeur, non attribuable au canal basal |
| **E2** 16:20–16:50, rebond 25 min après nadir 50, IOB −2,0, 1,43 U en 30 min | cohérente (1,43 U ≈ 3,8× profil) | burst visible ~16:00–16:45 | correction post-hypo sur canal basal **structurellement possible** : `hypoGuard` = 70, aucun verrou TBR post-hypo effectif (§2.2, §8) | **possible** — c'est l'épisode le mieux aligné avec le mécanisme confirmé ; mais 1,43 U seul n'explique pas un nadir à 58 |
| **E3** 18:45–21:05, 8,5 U/h × ~29 min + 1,5 U, pic 129 | cohérente (excès 8,5 U/h ≈ 3,75 U) | barre ~8,5 vers 19:30 | **identifié avec 3 signatures concordantes** : `manualMealModeTbr`, `forceExact`, 30 min, trou JSONL (§2.4, §2.5) | **probable au niveau du mécanisme**, non démontré au niveau de l'issue (dip à 69 seulement) |
| **E4** 21:45–00:55, 4,90 U/h « ~2¼ h », 3,5 U, pic 164 | **incohérente** — 3,5 U implique ~51 min, pas 2¼ h | barres ~4,9 vers 22:30 et 23:30 avec interruptions | — | **faible** — la description de durée est contredite par sa propre quantité |
| **E5** 08:40–09:55, 4,20 U/h à BG 104–143, 3,1 U, nadir 62 | cohérente (~3,2 U/h moyen) | barre ~4,2 vers 09:00 | montée post-petit-déjeuner ; 0,75 U de bolus visibles en parallèle | **possible** |
| **E6** 10:20–11:00, 4,20 U/h à BG 96–152, 1,7 U, 10 min après récupération | cohérente | barre ~4,2 vers 11:00 | re-correction post-hypo : même mécanisme que E2 | **possible** |

**Point méthodologique décisif :** le rapport attribue chaque hypoglycémie au TBR qui la précède.
Or sur la fenêtre, **17,3 U ont transité par le canal bolus/SMB contre 12,45 U d'excès basal**
(§4.1). Toute attribution au canal basal sans intégration séparée des deux canaux, avec la cinétique
Lyumjev (DIA 420 min appris), est **non démontrée**. Les glucides d'hypo — présents (4 contextes
`HypoRecovery`, annonces « 29 g / 32 g additional carbs required ») mais **jamais quantifiés**, COB
restant à 0 — constituent une variable non contrôlée majeure sur E2, E4 et E6.

---

## 7. Analyse du tick du 1er août 16:21

**Les valeurs de ce tick ne sont pas vérifiables** : le JSONL de la fenêtre est absent. Le rapport
cite un `event_id evt_…` non résolvable. Ce qui suit établit (a) que le tick est **productible** par
le code, (b) l'arbre de calcul, (c) des **analogues réels** sur données primaires.

### 7.1 Analogues réels — le scénario n'est pas un artefact de lecture

Combinaison « TBR > 4 U/h **et** BG < 100 **et** IOB < 0 » dans `basal_adaptive_records.csv`
(n = 30 465, colonnes vérifiées §A.2) : **318 occurrences**, dont

| Horodatage | BG | eventualBG | IOB | TBR finale suggérée |
|---|---|---|---|---|
| 18/03 05:22 | 90,6 | 144 | **−2,63** | **7,38** |
| 21/03 06:27 | 88,2 | 209 | −0,29 | **8,00** |
| 21/03 08:32 | 81,3 | 177 | −1,15 | **7,00** |

Le tick rapporté (BG 91,4 · IOB −1,97 · demande pré-damping 7,50 U/h) est **structurellement
identique** à des configurations qui se produisent des centaines de fois dans un enregistrement
appareil indépendant. **La demande de 7,50 U/h à BG 91 avec IOB négatif est un comportement réel et
récurrent du moteur**, pas une valeur mal lue.

### 7.2 Arbre de calcul du tick

```
ENTRÉES        BG 91,4 · delta +5,3 · IOB −1,97 · COB 0 · profil 0,75 U/h · cible 100
               hypoGuard = 100 − 0,5·(100−40) = 70          → 91,4 > 70 : AUCUN arrêt dur
                                                              (:11526 non déclenché)
① CANAL SMB    isCriticalSafetyCondition → "BG below target stable without COB"
               (:13508-13511)                                → SMB = 0 (correct)   [:12713-12719]
               contexte HypoRecovery                         → finalUnits = 0      [:12376-12379]
               ⇒ le canal SMB est correctement neutralisé — et n'influence PAS le taux basal

② ESTIMATION   basalaimi (TDD/poids, multiplicateurs TIR)     [:5176-5267]
   ISF          sens = min(pkpdRuntime.fusedIsf, variable_sens) = 24 (« profile=39 ») [:8795-8805]
                effectiveSens = sens × autosensRatio (scale 0,89)                    [:5370]

③ MOTEUR       BasalDecisionEngine.decide(basalEstimate, variableSensitivity)   [:16287-16290]
               eventualBG 189 entre ici comme SEUIL, pas comme facteur :
                 eventualBg > 110 → branche de boost active   [BasalDecisionEngine.kt:387]
               règle « BG 90-100 rising: 50% basal » :
                 chosenRate = profileCurrentBasal × 0,5        [BasalDecisionEngine.kt:330-331]
                 ⇒ LIANTE DANS LE MOTEUR (vraie affectation, pas un log)
                 ⇒ mais atteinte seulement si BasalPlanner.plan == null ET
                    AIMIAdaptiveBasal.rateUph == null           [:169, :213-216]

④ POST-HYPO    REBOUND_GUARD → « post-hypo TBR bridge » = calculateRate(…, 1,5) ≈ 1,10 U/h
                                                              [:6201-6215 → rT.rate :6421]
               tier REBOUND_GUARD ⇒ allowRocketBasalScale = false
                 ⇒ fusion = min(engine, rT.rate)               [CorrectionAggressionBasalCap.kt:45]
                 ⇒ cap = 1,5 × profil                          [:7829]
               ⇒ le bridge est FONCTIONNELLEMENT CAP-ONLY

⑤ ★ RUPTURE    setTempBasal (:7944) — TOUT ce qui précède est en AMONT de :
   D'ORDRE       DynamicBasalController.calculateDynamicRate → × [0,0 … 10,0]      [:11486-11493]
                   multiplicateur = f(bg − targetBg, delta, shortAvgDelta)
                 pkpdPreferTbrBoost                                                 [:11508]
                 clamps maxSafe / max_basal / BASAL_FLOOR                           [:11525-11539]
                 AdaptiveBasal × (plancher 0,70) — APRÈS les clamps                  [:11542-11566]
                 ampli endocrine                                                     [:11576-11607]
               ⇒ 7,50 × 0,70 = 5,25 U/h                        → rT.rate = 5,25     [:11616]

⑥ FINALISATION finalResult.rate                                                      [:7995]
               Auditeur : dernière écriture possible, ×[0,8 ; 1,2], AUDIT_ONLY par défaut [:8145]
               PAS d'arrondi pompe dans AIMI ; contraintes en aval  [LoopPlugin.kt:751]
```

### 7.3 Réponses aux 8 questions posées

1. **Le bridge à 1,10 U/h était-il recommandation, plafond, candidat ou narration ?**
   Un **candidat écrit dans `rT.rate`** (`:6421`, l'écriture précède le log `:6425`) qui, sous le tier
   REBOUND_GUARD, ne peut fonctionner que comme **plafond** (fusion `min`, cap 1,5× profil).
2. **Était-il censé devenir contraignant ?** Oui — comme borne supérieure. L'intention est explicite
   dans le commentaire `:10866-10870`.
3. **A-t-il été remplacé en aval ?** **Oui, inconditionnellement.**
4. **Par quelle fonction, quelle condition ?** `setTempBasal` → `rT.rate = rate` à **`:11616`**,
   sans condition. La valeur y arrive après `DynamicBasalController.calculateDynamicRate`
   (`:11486`, ×0–10) puis `AdaptiveBasal` (`:11542`). **Aucune condition à désactiver : c'est un
   défaut d'ordre des opérations**, un plafond posé en amont d'un multiplicateur.
5. **« BG 90-100 rising: 50% basal » agit-elle sur la valeur finale ?**
   **Liante dans le moteur, non autoritaire à la pompe.** Vraie affectation
   (`BasalDecisionEngine.kt:330`), jamais écrasée *dans* le moteur (tous les blocs suivants sont
   gardés `if (chosenRate == null)`), puis **re-scalée** par `calculateDynamicRate` (`:11486`) dont le
   multiplicateur est étranger à ce 0,5. Le nombre narré n'est pas le nombre délivré.
6. **5,25 U/h : proposé, enacté, ou délivré ?** **Proposé/suggéré uniquement, sur les preuves
   disponibles.** `rT.rate` est une valeur demandée ; l'enactment passe par
   `applyBasalConstraints` (`LoopPlugin.kt:751`) puis `commandQueue.tempBasalAbsolute`
   (`:1092/1172`). Aucune donnée pompe ni Nightscout n'a été fournie. **Non prouvé comme délivré.**
7. **Remplacement du TBR avant 30 min ?** Non vérifiable ; mais **structurellement systématique** —
   chaque tick (5 min) réémet une TBR de 30 min. Le rapport comptabilise pourtant « un TBR
   5,25 U/h × 30 min = 2,6 U en une décision » : c'est une **surestimation** si le TBR est remplacé
   au tick suivant. L'intégration correcte doit utiliser le **temps effectif jusqu'au remplacement**,
   ce que le rapport ne documente pas.
8. **L'IOB négatif signifie-t-il une absence d'insuline active ?** **Non.** C'est la convention
   AndroidAPS : l'IOB est net du basal profil ; un IOB net de −1,97 U signifie que la délivrance a
   été **inférieure au profil** sur la fenêtre récente (17,3 h à 0 U/h dans ce récit), pas qu'aucune
   insuline n'agit. L'insuline **activité** peut rester non nulle. Le rapport utilise l'IOB négatif
   comme preuve d'absence d'insuline : c'est une **interprétation erronée**, même si sa conclusion
   (dosage inapproprié au vu du contexte) peut rester défendable par d'autres voies.

---

## 8. Architecture SMB versus basal

Voir la matrice complète en §2.2. Réponse à la question architecturale posée par la mission :

**Il n'y a PAS une variable de demande partagée.** Les deux canaux sont **calculés indépendamment**
à partir d'un état amont commun. `insulinReq` (`:6956-6981`) est dérivé de `smbToGive` et est
**SMB-only** — `grep insulinReq` sur `BasalDecisionEngine.kt` = **0 occurrence**. Aucun terme
résiduel/leftover n'existe. L'évaluateur de sécurité partagé `safetyAdjustment` (`:10491-10629`)
émet **deux sorties disjointes** : `bolusFactor` (SMB uniquement, `:6979`) et `stopBasal` (basal
uniquement, et seulement si `dropPerHour ≥ 65 && delta < 0 && bg < 85`).

**Le terme « reroutage » n'est donc PAS justifié comme mécanisme général.** Mais **quatre
dépendances de données réelles** SMB → basal existent, et deux branches de compensation explicites :

| # | Dépendance | Preuve |
|---|---|---|
| 1 | **Mutex basal-first sur le SMB post-throttle** : les canaux T3C et Harmonia se déverrouillent *précisément quand le SMB a été mis à zéro* | `:7308`, `:7500` — `if ((rT.units ?: 0.0) > 0.0 …) return blockT3cBasalFirstProduction(…, "smb_already_requested")` |
| 2 | **Le throttle PKPD coupe le SMB et pose un boost TBR dans le même bloc** | `:12175` `gatedUnits × effectiveSmbFactor` puis `:12200` `pkpdPreferTbrBoost = if (throttle.preferTbr) 1.15`, consommé `:11508`. `SmbTbrThrottleLogic.kt:26-44` : `smbFactor = 0,6/0,3` **avec** `preferTbr = true` — une seule règle, SMB ↓ et TBR ↑ |
| 3 | **SMB Autodrive « strippé » littéralement converti en U/h**, **sans passer par la pile de sécurité SMB** | `T3cAutodriveBasalBridge.kt:97-114` — `boost = strippedSmbU / 0,25 h`, cap 3,0 U/h |
| 4 | `smbToGive == 0.0` ouvre une branche basale ×5 (honeymoon) | `BasalDecisionEngine.kt:289` |

**Compensations explicites quand le SMB est bloqué :**
- `:6335-6341` — `"Prudent Compensation (SMB blocked)"`, `calculateRate(basal, safeMax, 1.4, …)` ;
- `:6197-6215` — le bridge post-hypo REBOUND_GUARD (×1,5).

**Formulation exacte à retenir :** ce n'est pas un transfert global de la dose supprimée, c'est
**un second canal non protégé, plus deux branches de substitution explicites et quatre couplages
ponctuels**. La thèse du rapport est donc **vraie dans ses effets, imprécise dans son mécanisme**.

---

## 9. Analyse dynISF / fused ISF / TDD

| Niveau | Contenu |
|---|---|
| **Mathématique (établi)** | `∂fusedIsf/∂TDD24h ≤ 0` (§2.7) · ISF appliqué = `min(fusedIsf, variable_sens)`, la branche la plus agressive gagne toujours (`:8802`) · **aucun plancher relatif au profil inconditionnel** ; seuls des bornes absolues [5 ; 300] · limiteur par appel **asymétrique** −55 %/+40 % · `isfFactor` appliqué jusqu'à 3× par tick · `dynamicDeltaCorrectionFactor` jusqu'à ×0,05 · TDD inclut basal + SMB + bolus **sans aucune exclusion** (`TddCalculatorImpl.kt:136-173`) · autosens recalculé chaque tick **sans EMA ni clamp** |
| **Observé (30 h manquantes)** | rien. Jeu de contrôle : fused ISF 16–47, médiane 28 — mais profil de ce patient 10–19, donc **non transposable** |
| **Causalité interjournalière** | **non démontrable.** Deux des trois canaux TDD→ISF **saturent** (`tddIsf ≥ 0,5·profileIsf`) ; le canal `ratio = tdd2Days/tdd24Hrs` ne sature pas. Le *signe* de chaque lien est établi ; le **gain de boucle** ne l'est pas, et 30 h — absentes — n'y suffiraient pas |

Le §4.2 du rapport est **correct sur le signe et sur l'absence d'exclusions**, **incorrect dans son
cadrage** (comparaison à l'ISF statique 74, §4.7), et **non démontré** quant à la boucle.

---

## 10. Analyse des learners

**BasalLearner** — voir §2.6. Confirmé : formule (1,48 exact), clamps (2,0), absence totale
d'exclusion post-hypo, `onHypoDetected` limité au terme court (≈ −4 % combiné) **sans fenêtre ni
décroissance et sans chemin de récupération** (no-op algébrique sur BG calme), `onPersistentHyper`
**mort**, et arrivée effective à la pompe. **L'affirmation « le learner est entraîné à la hausse par
les rebonds post-hypo qu'il provoque » est structurellement confirmée** — l'ordre des appels
l'illustre : `process()` (`:7664-7671`) tourne **avant** `notifyBasalLearnerHypoIfNeeded()`.

**Unified Reactivity** — plancher 0,5 confirmé (`ReactivityDaypart.kt:25`). Mais l'affirmation que ce
learner prudent « ne gouverne que le canal qui ne délivre pas » est **infirmée** : via
`brainFactor` → `autosensResult.ratio` (`OpenAPSAIMIPlugin.kt:1042-1043, 1097-1103`), il scale
**basal, cibles, IOB et ISF**. Voir §4.3.

**Conclusion nuancée :** les deux learners ont bien des **conclusions divergentes** (l'un amplifie,
l'autre freine) et le mécanisme d'apprentissage à la hausse sur rebonds est réel. Mais la géométrie
« prudent → SMB / imprudent → basal » que le rapport en déduit est fausse : les deux atteignent le
basal, par des chemins différents.

---

## 11. Analyse Stack-Aware Guard B

### La préférence ne peut PAS corriger ce défaut — **CONFIRMÉ**, par le graphe d'appel

`OApsAIMIPkpdStackAwareGuardB` a exactement **trois sites de lecture runtime**
(`DetermineBasalAIMI2.kt:9082`, `:14153`, `:15386`), tous forwardant vers
`AdvancedPredictionEngine.predictCurves`. **Aucun module ISF, basal ou sécurité ne lit cette clé.**

Prédicat unique (`AdvancedPredictionEngine.kt:162-168`) :
`stackCanBreachFloor = enabled && delta < 0 && iob × sens > FRACTION × (BG − endoBaseline)`,
qui alimente **uniquement** `endoActive`, dont les deux seuls effets sont
`lastIob/lastCob/lastUam/lastHybrid = max(x, endoBaseline)` avec
`endoBaseline = min(80,0 ; max(BG ; 39,0))`.

Trois preuves indépendantes :

1. **Monotonie** — les deux opérateurs sont des `max(...)`. Suspendre le plancher ne peut que
   **baisser ou laisser égal**. Une surcorrection basale exige un `eventualBG` **haut** ; Guard B ne
   peut jamais abaisser une valeur haute.
2. **Portée bornée** — l'ancre est `≤ 80 mg/dL`. Sur les hypers documentés (`eventualBG` 189–400),
   `max(hybrid, ≤80)` était **déjà un no-op** : basculer la préférence ne change **rien** à ce
   nombre. Guard B n'est vivant que dans la bande 39–80.
3. **Ni l'ISF ni le taux ne sont touchés** — `effectiveSensitivity` est une **entrée en lecture
   seule** du prédicat, jamais écrite ; aucune écriture `rT.rate` / `setTempBasal` n'est atteignable
   depuis le guard. L'effet indirect via `eventualBG` (`:14192`, `:15407`) est **descendant
   uniquement**, et sur la suspension il est **permissif** (`applyBasalFloor`, `:16469-16476` : un
   BG prédit plus bas ne fait qu'*autoriser* un taux plus bas).

**Le §6 du rapport est donc juste dans sa conclusion**, y compris son observation que Guard B aide
plutôt le mode de défaillance **inverse**. Mais son attribution du commit est fausse (§4.4), et
« l'utilisateur a activé la préf sans effet » ne teste pas ce qu'il croit.

**Compléments (défaut par défaut OFF, `BooleanKey.kt:562-566`) :**
- Une hypothèse d'agent selon laquelle `PLATEAU_FLOOR_LIFT` (`DoseTerminalSnapshot.kt:100-181`)
  fabriquerait l'`eventualBG` gonflé a été **testée et falsifiée** : `plateau_floor_lifted == true`
  sur **0 / 237** ticks du jeu de contrôle.
- **Ce qui gonfle réellement l'eventual dosant** est identifié sur données primaires : la source du
  `dose_terminal_snapshot` est `SCENARIO_*` sur ~203/237 ticks, `authority_applied` sur **237/237**,
  et la source `SCENARIO_CONSENSUS` porte la moyenne de TBR la plus haute (**2,99 U/h**, max 9,00
  contre 0,85–1,07 pour les autres sources). Les 7 ticks « BG < 110 mais eventual ≥ 150 » sont
  **tous** `SCENARIO_CONSENSUS` sauf un — ex. BG 99,7 → eventual **400** → TBR 3,55 U/h.
  **C'est la projection de scénario, pas le plancher PKPD, qui alimente ces décisions.** Cela
  corrobore le §3-point-4 du rapport (« le rebond glucidique non compté est projeté comme un repas
  complet ») tout en désignant un composant différent de celui qu'il incrimine.
- Guard B a **zéro test unitaire** (`AdvancedPredictionEngineTest.kt:202-207` ne passe jamais le
  flag) et une **incohérence de dépendance** : la dépendance UI déclarée est
  `OApsAIMIPkpdHyperReversion` alors que la garde runtime est `OApsAIMIPkpdEndogenousReversion`.

---

## 12. Limites de preuve

1. **Le paquet de données du rapport est entièrement absent** (7 fichiers sur 8). C'est la limitation
   dominante : les épisodes, les nadirs, les quantités d'insuline et le tick 16:21 **ne sont pas
   vérifiables**. Aucune valeur n'a été inventée pour combler ce vide.
2. **Jeux de substitution — portée strictement bornée :**
   - Le paquet du 31 juillet est **même build (310726, confirmé) mais un patient différent**
     (profil 0,45–0,60 U/h non plat, ISF profil 10–19 vs 0,75 plat / 74 annoncés). Valide pour tester
     des propriétés du **moteur** ; **invalide** pour les valeurs patient du rapport.
   - `basal_adaptive_records.csv` couvre **16 mars → 17 juil. 2026**, hors fenêtre, **provenance
     patient inconnue**, **build non vérifiable**.
3. **Suggéré ≠ délivré.** Toutes les statistiques de TBR de cet audit portent sur `finalResult.rate`
   (vérifié §A.2), c'est-à-dire une valeur **demandée**. Aucune donnée pompe, aucun export
   Nightscout, aucun `basal_delivery_segments.csv` n'a été fourni. **La délivrance réelle des
   4,2–8,5 U/h n'est pas établie.**
4. **La reconstruction du rapport après 22:00 est spéculative par son propre aveu** (TBR suggérés
   traités comme délivrés), et l'arrêt de synchronisation Nightscout à 22:00 n'est pas vérifiable.
5. **L'intégration des TBR par le rapport ne documente pas le remplacement anticipé.** Avec un tick
   toutes les 5 min réémettant 30 min, compter « 2,6 U par décision » surestime probablement.
6. **Le jeu de données du rapport est censuré, pas seulement clairsemé** (§2.5, §4.5), et la censure
   est **corrélée aux deux variables d'intérêt** (BG bas en descente, grosses décisions mode repas).
   Toute fréquence calculée dessus est biaisée — y compris les statistiques du §4.1 du rapport.
7. **Écart code / build borné mais non nul** : `bf89732` ≠ HEAD (−3 commits). L'écart a été mesuré et
   est nul en substance sur le canal basal (§2.1). Le code courant n'est néanmoins **pas** une preuve
   absolue du comportement d'un binaire historique : aucun APK n'a été analysé.
8. **Non établi faute de données** : la provenance du « fused ISF 16 » (pipeline 1 ou 2, §4.7) ;
   l'oscillation autosens 0,41→1,15 ; la branche ×1,12 de `updateMediumTerm` ; le `targetBg` runtime
   qui déciderait du seuil HARD_BRAKE à 90,2 ; la fréquence de `ReboundSuspected` + `Tier.FULL`
   (le seul trou où le bridge post-hypo pourrait lier **vers le haut**).
9. **Analyse statique** : Agent 3 n'a pas énuméré de façon exhaustive les ~40 consommateurs
   d'`eventualBG` ; aucun consommateur inversé n'a été trouvé, mais l'exhaustivité n'est pas prouvée.
10. **Facteur de configuration non discuté par le rapport** : pour que 7,50 U/h survive au clamp
    `maxSafe` (`:11513-11519`) avant `AdaptiveBasal`, il faut que les réglages `max_basal` et
    `current_basal_safety_multiplier` de l'utilisateur autorisent ≥ 7,5 U/h sur un profil 0,75 U/h
    (≥ 10×). Une part de la magnitude observée est donc **permise par la configuration utilisateur**,
    pas uniquement par la logique AIMI.

---

## 13. Risques techniques classés

| # | Risque | Preuve | Classement |
|---|---|---|---|
| R1 | **Ordre des opérations : les plafonds de correction/post-hypo sont appliqués en amont d'un multiplicateur ×0–10.** Toute protection ainsi placée est structurellement inopérante | `:7829` (cap) puis `:11486-11493` (×0–10), `:11542` ; **8/8 en production** | **CRITIQUE** |
| R2 | **`meal_modes_MaxBasal` est une consigne exacte, pas un plafond** — `forceExact = true` court-circuite `DynamicBasalController`, `maxSafe`, `max_basal`, `AdaptiveBasal` et le gouverneur endocrine, avec en plus `bypassSafety` | `:14885-14899`, `:11374-11393`, `:11522` | **CRITIQUE** |
| R3 | **La classe de décision au basal le plus élevé n'est jamais exportée** — 13 retours anticipés sans télémétrie ; la fenêtre de 30 min du mode repas est exclue de façon déterministe. Débogage terrain aveugle sur les pires événements | 2 sites d'export vs 13 retours ; `9f979b9698` n'a corrigé qu'un cas | **ÉLEVÉ** |
| R4 | **Asymétrie de protection SMB/TBR** : `isCriticalSafetyCondition`, contexte `HypoRecovery`, `bolusFactor`, COB==0, plafonds SMB, règle « eventual < cible » — aucun n'agit sur le TBR. Seul arrêt dur : `BG ≤ 70` | §2.2 ; commentaire du code `:10866-10870` reconnaissant « runaway TBR observed 8–9× profile » | **ÉLEVÉ** |
| R5 | **BasalLearner apprend à la hausse sur les rebonds post-hypo, sans exclusion et sans récupération** — `onHypoDetected` = −4 % combiné, permanent mais non réversible sur BG calme ; `onPersistentHyper` mort | `BasalLearner.kt:120-137`, `:177-182`, `:206-215`, `:188-193` | **ÉLEVÉ** |
| R6 | **Aucune garde IOB négatif sur aucun canal** ; le seul code bas-IOB est un *plancher* qui **relève** la dose | aucun test `iob < 0` ; `:13778` | **ÉLEVÉ** |
| R7 | **Limiteur de fusion ISF asymétrique** (−55 % vs +40 % avec les défauts livrés), biaisé vers plus d'insuline ; probablement non intentionnel | `IsfFusion.kt:53-58` + `DoubleKey.kt:359-361` | **MODÉRÉ** |
| R8 | **Faux TDD injecté dans un objet ISF à état** (`max_daily_basal × 24`), cliquetant `lastIsf` avant le vrai TDD, 4 appels/tick | `:1981`, `:8904`, `:10221`, `:10258` | **MODÉRÉ** |
| R9 | **Convention autosens incohérente** entre maths de dosage (`× ratio`) et export (`× 1/ratio`) — inverse le sens de chaque branche gardée par `ratio` | `:9031`, `:5370` vs `:8241-8245` | **MODÉRÉ** |
| R10 | **Autosens recalculé chaque tick sans lissage ni clamp**, avec repli silencieux sur une préférence quand l'appel async échoue | `OpenAPSAIMIPlugin.kt:1018-1022`, `:943`, `:958` | **MODÉRÉ** |
| R11 | **`classifyPostHypoState` appelé ≥3×/tick** malgré un invariant « une fois par tick » documenté, en mutant `lastHypoBelow70At` — désynchronise l'état post-hypo du tier | `:13071` (invariant), `:15650`, `:15815`, `:15829` | **MODÉRÉ** |
| R12 | **Guard B sans aucun test unitaire** + dépendance UI ≠ garde runtime | `AdvancedPredictionEngineTest.kt:202-207` ; `BooleanKey.kt:562-566` | **FAIBLE** |
| R13 | Code mort dans le chemin de sécurité : `evaluateAndLogCorrectionAggression` (aucun appelant), `HarmoniaHarmonizer.Outcome.smbFactor` (aucun consommateur), `runT3cAutodriveShadowTick` (résultat jeté) | `:10887-10913` ; `HarmoniaHarmonizer.kt:20` ; `:16989-17013` | **FAIBLE** |
| R14 | Causalité TBR → hypoglycémies sur cette fenêtre | aucune donnée d'insuline ni CGM | **NON CONFIRMÉ** |
| R15 | Boucle de rétroaction interjournalière TDD → ISF → surdélivrance | signes établis, gain de boucle non mesuré, 2 canaux sur 3 saturent | **NON CONFIRMÉ** |

---

## 14. Orientations possibles

Orientations **architecturales** uniquement. Aucune modification n'a été faite ; aucune n'est
recommandée sans revue des mainteneurs, et R1/R2 touchent directement la sécurité de dosage.

1. **Traiter R1 comme un invariant d'ordre, pas comme une règle supplémentaire.** Le problème n'est
   pas qu'une protection manque, c'est qu'elle est posée **en amont** d'un multiplicateur ×0–10.
   Toute correction consistant à ajouter une règle en amont de `:11486` reproduira le défaut. La
   forme structurelle est un **point de plafonnement unique et terminal**, après le dernier
   multiplicateur et avant `rT.rate = rate` (`:11616`) — avec l'autorité du tier propagée jusque-là.
2. **Séparer « plafond » et « consigne » dans le chemin mode repas (R2).** `forceExact = true`
   court-circuitant cinq étages de sécurité mérite d'être requalifié : soit un plafond
   (`coerceAtMost`), soit une consigne explicitement nommée et bornée par `maxSafe`.
3. **Hisser l'export de télémétrie en un point unique et inconditionnel (R3)** — condition préalable
   à toute validation terrain, y compris à la ré-instruction de ce rapport. Tant que les décisions
   au basal le plus élevé et les descentes hypo ne sont pas exportées, aucune statistique de
   fréquence issue de ce JSONL n'est défendable.
4. **Décider explicitement quelles protections sont « par canal » et lesquelles sont « par dose ».**
   La matrice §2.2 montre un partage historique, non conçu. `isCriticalSafetyCondition` et le
   contexte `HypoRecovery` sont des jugements sur l'**état du patient**, pas sur un canal.
5. **Fenêtre d'exclusion pour l'apprentissage post-hypo, et chemin de retour au neutre (R5).**
   `UnifiedReactivityLearner` possède déjà le motif (convergence neutre, `:695-706`) ;
   `BasalLearner` n'a aucun de ses trois termes couvert.
6. **Hystérésis sur les seuils `eventualBG`, pas amortissement d'un gain proportionnel.** Le rapport
   propose l'inverse ; les données (62 bascules à BG identique) montrent que le problème est le
   franchissement de seuil.
7. **Instruire à nouveau le dossier avec un paquet de données complet et vérifiable** avant toute
   conclusion clinique : segments de basal **délivré** (pompe ou Nightscout), série CGM complète,
   bolus avec flag manuel, et les ticks JSONL de la fenêtre. Sans cela, la question « ces TBR ont-ils
   causé ces hypoglycémies » reste ouverte.

---

## 15. Annexe de traçabilité

| Conclusion | Donnée ou test | Fichier | Fonction / lignes | Confiance |
|---|---|---|---|---|
| Build 310726 = `bf8973259e` | `git show` + rapport diagnostic appareil | `buildSrc/.../Versions.kt` ; `Diagnostic_Report.txt` | `appVersion` | **élevée** |
| Code courant valide pour le canal basal du build audité | `git diff bf89732..HEAD` | `DetermineBasalAIMI2.kt` | 5 ins/2 sup, commentaire seul | **élevée** |
| Paquet du 31 juil. = patient différent | 237 ticks `baseline_state` | `AIMI_Decisions_Last24h.jsonl` | `profile_basal_uph` 0,45–0,60 ; `profile_isf_mgdl` 10–19 | **élevée** |
| Colonne `basal` du CSV = TBR **suggérée** | trace d'appel | `DetermineBasalAIMI2.kt` ; `BasalNeuralLearner.kt` | `:8172-8174` → `updateLearning` → `logRecord` `:292-311` | **élevée** |
| Bridge REBOUND_GUARD non contraignant | **8/8 ticks de production** + ordre des lignes | `AIMI_Decisions_Last24h.jsonl` ; `DetermineBasalAIMI2.kt` | `:6201-6215`, `:7829`, `:11486`, `:11616` | **élevée** |
| `meal_modes_MaxBasal` = consigne exacte | lecture de code | `DetermineBasalAIMI2.kt` | `:2450-2451`, `:14885-14899`, `:11374-11393`, `:11522` | **élevée** |
| Asymétrie protections SMB/TBR | matrice de 15 protections | `DetermineBasalAIMI2.kt` ; `BasalDecisionEngine.kt` | `:12713-12719`, `:12376-12379`, `:6979` ; `:153` | **élevée** |
| TBR **non** proportionnel à `eventualBG` | 16 occurrences classées gate/log/mort | `BasalDecisionEngine.kt` | `:143…:572` ; dominante `:11486` | **élevée** |
| `eventualBG` liant **par seuil** | 62 paires à BG identique, TBR 0,14 → 5,6–7,0 | `basal_adaptive_records.csv` | `mechanism_stats.txt` T4 | **élevée** |
| Instabilité `eventualBG` | n=18 818 transitions : p90 96, 20,9 % > 50 | `basal_adaptive_records.csv` | `mechanism_stats.txt` T3 | **élevée** |
| Demande 7–8 U/h à BG < 100 avec IOB < 0 est récurrente | **318 occurrences** | `basal_adaptive_records.csv` | `mechanism_stats.txt` T2 | **élevée** (provenance patient inconnue) |
| Excès d'insuline majoritairement via SMB, non basal | recalcul des chiffres du rapport + mass balance | — | §4.1 ; `mass_balance.txt` | **élevée** |
| `Basal× = 1,48` depuis 0,7/2,0/2,0 | arithmétique | `BasalLearner.kt` | `:110-115` | **élevée** |
| Aucune exclusion post-hypo dans l'apprentissage | grep + lecture | `BasalLearner.kt` | `:120-137`, `:177-182`, `:206-215` | **élevée** |
| Unified Reactivity atteint le basal | trace `brainFactor` → `ratio` | `OpenAPSAIMIPlugin.kt` | `:1042-1043`, `:1097-1103` | **élevée** |
| `∂fusedIsf/∂TDD ≤ 0` | dérivation formelle | `IsfFusion.kt` ; `PkPdIntegration.kt` | `:27-61` ; `:485-496` | **élevée** |
| TDD sans exclusion (basal+SMB+bolus) | lecture | `TddCalculatorImpl.kt` | `:136-173` | **élevée** |
| Guard B incapable d'agir | monotonie + portée ≤ 80 + ISF/rate intouchés | `AdvancedPredictionEngine.kt` | `:162-168`, `:196-230` | **élevée** |
| `bf89732` = commit UI seul | `git show --stat` | `compose/PkpdSettingsUi.kt` | `:357-361` ; comportement en `32028d804b` | **élevée** |
| `PLATEAU_FLOOR_LIFT` non impliqué | **0/237** ticks | `AIMI_Decisions_Last24h.jsonl` | `plateau_lift_check.txt` | **élevée** |
| `SCENARIO_CONSENSUS` = source des eventual gonflés | 237/237 `authority_applied` ; moyenne TBR 2,99 max 9,00 | `AIMI_Decisions_Last24h.jsonl` | `plateau_lift_check.txt` | **moyenne** (patient différent) |
| Ticks < 90 exportés sur ce build | 34 ticks < 90 ; min 71,5 | `AIMI_Decisions_Last24h.jsonl` | `samebuild_stats.txt` C1 | **élevée** |
| Censure sélective des descentes basses | 13 retours pré-export | `DetermineBasalAIMI2.kt` | `:2733-2737`, `:15646`, `:16201` | **élevée** |
| Blackout 30 min mode repas | chemin de retour | `DetermineBasalAIMI2.kt` | `:15037-15051` → `:15659` | **élevée** |
| Auditeur = SMB seul, advisory | 8 enregistrements `advisory_only` + défaut `AUDIT_ONLY` | `AIMI_Decisions_Last24h.jsonl` ; `AuditorOrchestrator.kt` | `"SMB reduced by 35%"` ; `:494` | **élevée** |
| Épisodes E1–E6 non vérifiables | 7 fichiers sur 8 absents | — | `EVIDENCE_INVENTORY.md` §A.1 | **élevée** |

---

## Réponses explicites aux 14 questions de la mission

1. **Les TBR de 4,2 à 8,5 U/h ont-ils réellement été délivrés ?**
   **Non établi.** Le moteur *demande* de tels taux — c'est prouvé (jeu de contrôle max 9,00 U/h ;
   CSV max 10,00 U/h, 18,3 % des ticks > 4 U/h). Mais aucune donnée pompe ni Nightscout n'a été
   fournie, et le rapport reconnaît reconstruire l'après-22:00 depuis des TBR **suggérés**.
2. **Leur quantité intégrée est-elle correctement calculée ?**
   **Non, avec au moins deux défauts.** Le basal profil est faux (21,3 U contre 22,45 U recalculés) ;
   E4 est auto-contradictoire (« 2¼ h » vs 3,5 U ⇒ 51 min) ; et l'intégration ne documente pas le
   **remplacement des TBR avant expiration**, ce qui surestime « 2,6 U par décision ».
3. **Sont-ils causalement liés aux hypoglycémies observées ?**
   **Non démontré** — aucun épisode ne dépasse « possible ». Association plausible et cinétique
   compatible, mais 17,3 U ont transité par le canal bolus/SMB (contre 12,45 U d'excès basal), les
   glucides d'hypo ne sont jamais quantifiés, et dans le jeu de contrôle seuls 4/31 ticks à TBR > 4
   sont suivis d'un passage sous 80 (0/31 sous 70).
4. **Les protections SMB sont-elles absentes ou contournées dans le canal basal ?**
   **Absentes, pas contournées** — et c'est plus grave. Ce ne sont pas les mêmes protections
   court-circuitées : le canal basal n'a jamais reçu `isCriticalSafetyCondition`, le contexte
   `HypoRecovery`, `bolusFactor`, COB==0, les plafonds SMB, ni la règle « eventual < cible ». Il
   possède ses propres protections, en forme de **planchers**, plus des bypass explicites
   (`:11522`, `:11529-11536`).
5. **Le `REBOUND_GUARD` est-il réellement calculé puis écrasé ?**
   **Oui — confirmé par le code et par 8/8 ticks de production.** Écrasé inconditionnellement à
   `:11616`, après un multiplicateur ×0–10 (`:11486`). Cause racine : **ordre des opérations**, pas
   condition manquante.
6. **Existe-t-il un verrou post-hypoglycémique effectif pour les TBR ?**
   **Non.** `postHypoRecoveryActive()` n'agit sur le TBR que comme plafond T3C (`:17248`) ; le
   contexte `HypoRecovery` ne zéroe que le SMB ; le seul arrêt dur est `BG ≤ hypoGuard` (**70 mg/dL**
   pour une cible 100). À BG 81–91 en rebond, rien ne verrouille le canal basal — les 4 ticks de
   production à BG 81–89 avec TBR 2,56–2,74 U/h (4,3–5,7× profil) le démontrent.
7. **Le fused ISF est-il anormalement agressif au regard du profil ?**
   **Question mal posée, et non vérifiable pour ce patient.** L'ISF appliqué est
   `min(fusedIsf, variable_sens)` ; la narration citée dit elle-même `profile=39`, pas 74 ; le
   chiffre 16 est sous le plancher dérivable du pipeline 1. Ce qui **est** établi : **aucun plancher
   relatif au profil inconditionnel** n'existe, seules des bornes absolues [5 ; 300], et le limiteur
   par appel est asymétrique vers le bas (−55 % / +40 %).
8. **dynISF et la TDD peuvent-ils créer une rétroaction positive ?**
   **Le signe est établi** (`∂fusedIsf/∂TDD ≤ 0` ; TDD inclut basal+SMB+bolus **sans exclusion**).
   **Le gain de boucle ne l'est pas** : deux des trois canaux saturent à `0,5·profileIsf`, le canal
   `tdd2Days/tdd24Hrs` ne sature pas. **Non démontré comme fermé** — et indémontrable sur 30 h,
   absentes de surcroît.
9. **BasalLearner apprend-il à la hausse depuis les rebonds post-hypo ?**
   **Oui, structurellement confirmé.** Aucune exclusion post-hypo ; le rebond haut-et-montant prend
   la branche `×1,12` du terme moyen, surpondérée par `TAU_MEDIUM_MS = 3 h` ; l'hypo n'agit que sur
   le terme court (≈ −4 % combiné), **sans décroissance et sans chemin de récupération** sur BG
   calme ; `onPersistentHyper` est du code mort.
10. **Unified Reactivity agit-il sur le basal ou seulement sur SMB ?**
    **Sur les deux** — via `brainFactor` → `autosensResult.ratio`, qui scale basal, cibles, IOB et
    ISF (`OpenAPSAIMIPlugin.kt:1042-1043`, `:1097-1103`). **L'affirmation contraire du rapport (§4.3)
    est infirmée.**
11. **L'instabilité d'eventualBG influence-t-elle directement les TBR élevés ?**
    **Oui, mais par franchissement de seuil, pas par proportionnalité.** 62 paires de ticks à BG
    identique montrent la TBR basculer de ~0,14 à 5,6–7,0 U/h selon le seul `eventualBG`. La
    formulation du rapport (« dose proportionnellement à ce signal ») est **fausse**, et sa piste
    corrective n°2 en découle mal.
12. **Guard B est-il architecturalement incapable de corriger ce mécanisme ?**
    **Oui — confirmé par trois preuves indépendantes** (monotonie `max()`, ancre bornée à ≤ 80 alors
    que les eventual incriminés valent 189–400, ISF et `rT.rate` non atteignables). Précision : le
    commit `bf89732` cité est **purement UI** ; le comportement vient de `32028d804b`.
13. **Le rapport exagère-t-il ou interprète-t-il mal certaines données ?**
    **Oui, sur six points** : (a) la masse d'insuline est **inversée** — le SMB/bolus a délivré 58 %
    de l'excès, pas le basal ; (b) « dosage proportionnel à eventualBG » est faux ; (c) le learner
    prudent gouverne bien le basal ; (d) `bf89732` ne change aucun comportement ; (e) « ticks < 90
    absents » est trop général (min réel exporté 71,5) ; (f) l'IOB négatif est lu comme une absence
    d'insuline active alors que c'est une convention nette du basal profil. S'y ajoutent le basal
    profil (21,3 vs 22,45 U) et l'auto-contradiction d'E4. **Aucun de ces défauts n'est de la
    fabrication** — ce sont des erreurs d'analyse dans un dossier par ailleurs sérieux et
    inhabituellement bien instrumenté.
14. **Quel est le niveau réel de danger et de confiance dans le diagnostic ?**
    **Danger : élevé sur le plan architectural, non quantifié sur le plan clinique.** Deux défauts
    **critiques** sont confirmés dans le chemin de dosage — R1 (protections posées en amont d'un
    multiplicateur ×0–10, avec 8/8 confirmations en production) et R2 (`meal_modes_MaxBasal` comme
    consigne exacte court-circuitant cinq étages de sécurité). Le code lui-même documente le mode de
    défaillance (« runaway TBR observed 8–9× profile », `:10866-10870`) et son atténuation ne couvre
    que le cas exercice. **Confiance : élevée sur le mécanisme et sur l'existence du défaut ; faible
    sur l'attribution causale des sept hypoglycémies rapportées**, qui reste indéterminée faute de
    données. Le diagnostic de l'utilisateur pointe le bon endroit du système pour de mauvaises
    raisons mécanistiques.

---

*Audit read-only conduit le 2026-08-02 sur `feature/dexcom-oneplus-native` @ `512f00cf33`, build
cible `bf8973259e`. Aucun fichier produit modifié, aucune préférence changée, aucun commit, aucun
changement de branche. Document destiné à révision par les mainteneurs ; ne constitue pas un avis
médical.*
