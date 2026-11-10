# AIMI Harmonia - Simulation and Production Branch

## Objectif

Ce lot transforme Harmonia en branche active de simulation. Elle ne se limite plus a exposer un arbre physiologique: elle produit une decision virtuelle bornee a partir de l'arbre, du BG, de la tendance, de l'IOB, du COB et des limites du tick.

Le lot suivant branche Harmonia comme chemin production basal-first reel. La simulation reste l'hypothese calculee; la production est exposee separement dans `adjustments.harmonia_production`.

Le lot RBT-native ajoute ensuite Harmonia dans le `Recursive Belief Tree`: le RBT expose un canal `HARMONIA_PRODUCTION_BASAL_FIRST` et un objet `resolution.harmonia_basal_first`.

## Principe produit

Harmonia devient une branche de raisonnement active:

- elle choisit une action virtuelle: `OBSERVE`, `BASAL_FIRST`, `MEAL_SUPPORT`, `PROTECTIVE_REDUCTION` ou `BLOCKED`;
- elle calcule une basale virtuelle et un SMB virtuel;
- elle applique les caps pompe/IOB/sensor/hypo;
- elle explique pourquoi elle gagne ou pourquoi elle est bloquee;
- elle exporte toujours `simulation_only=true` et `applies_to_pump=false`.

La branche production reprend cette decision uniquement si elle est eligible, sans SMB, sans double application avec T3C, et apres les garde-fous finaux.

## Garde-fou central

La simulation peut etre active, mais elle ne peut pas commander l'insuline.

Le JSON contient explicitement:

```json
{
  "simulation_only": true,
  "applies_to_pump": false,
  "source": "harmonia_simulation_branch_v1"
}
```

Cela permet de comparer l'hypothese Harmonia avec la decision reelle sans confondre simulation et commande.

La production est exportee separement:

```json
{
  "mode": "APPLIED",
  "basal_first_only": true,
  "adds_smb_authority": false,
  "applies_to_pump": true,
  "source": "harmonia_production_branch_v1"
}
```

## Flux runtime

Pendant le tick AIMI:

1. `PhysiologicalTreeBuilder` construit l'arbre.
2. `HarmoniaSimulationEngine` construit une decision virtuelle.
3. `PatientRuntimeSnapshot` transporte `physiologicalTree` et `harmoniaSimulation`.
4. Le finalizer peut selectionner `HARMONIA_PRODUCTION_BASAL_FIRST` si T3C n'a pas gagne et si aucun garde-fou ne bloque.
5. AIMI Context affiche une ligne `Harmonia sim: ...`.
6. `AIMI_Decisions.jsonl` exporte `adjustments.harmonia_simulation` et `adjustments.harmonia_production`.

## Branche production

`harmonia_production` devient `READY`, puis `APPLIED`, uniquement si:

- la simulation est eligible;
- l'action est `BASAL_FIRST`, `MEAL_SUPPORT` ou `PROTECTIVE_REDUCTION`;
- aucune autorite SMB RBT n'est active;
- aucun SMB n'est deja demande dans `RT`;
- T3C natif n'a pas gagne le tick;
- sport/activity lockout est inactif;
- post-hypo guard est inactif;
- aucun meal conflict critique n'est present;
- maxIOB et surveillance IOB ne bloquent pas;
- le LGS/predictive hypo final ne bloque pas;
- le taux est borne par les caps profil/environnement et par une rampe de hausse.

La branche ne cree jamais de SMB. Si `MEAL_SUPPORT` est l'action source, seule la composante basale bornee peut etre retenue.

## Branche RBT-native

Le RBT peut maintenant resoudre Harmonia comme branche basal-first native:

- `BasalFirstChannel.HARMONIA_PRODUCTION_BASAL_FIRST`;
- `RbtExtendedSignals.harmonia*`;
- feuilles meso: `HARMONIA_ACTIVE`, `HARMONIA_BASAL_DEMAND`, `HARMONIA_MEAL_SUPPORT`, `HARMONIA_PROTECTIVE_REDUCTION`, `HARMONIA_SAFETY_BLOCK`;
- export `recursive_belief.resolution.harmonia_basal_first`;
- log RBT `bf=HARMONIA_READY`, `bf=HARMONIA_APPLIED` ou `bf=HARMONIA_BLOCK(...)`.

Priorite d'arbitrage:

1. T3C natif conserve la priorite si `T3C_BASAL_FIRST` est eligible.
2. Harmonia peut gagner si T3C n'est pas eligible et si Harmonia est eligible.
3. Si le RBT est indisponible, le chemin production Harmonia conserve le fallback basal-first existant.

Ce lot ne change pas l'autorite SMB et ne modifie pas les preferences.

## Advisor et Control Center

Le lot suivant expose Harmonia en lecture seule dans les surfaces produit:

- `AIMI Control Center` affiche une carte `Harmonia in RBT` dans la famille `Physiological influence`;
- `AIMI Advisor` ajoute une carte `HARMONIA IN RBT - LAST 24H`;
- les deux surfaces lisent `AIMI_Decisions.jsonl`, principalement `adjustments.recursive_belief.resolution.harmonia_basal_first`;
- `adjustments.harmonia_production` complete le statut applique/bloque quand il existe;
- aucun slider, aucune preference et aucun chemin SMB/TBR ne sont modifies par cette exposition.

Les champs importants a observer sont:

- `basal_first_channel`;
- `active`, `eligible`, `selected_for_production`;
- `source_action`, `branch`;
- `basal_demand_rate_uph`, `bounded_rate_uph`, `max_basal_cap_uph`;
- `dominant_blocker`, `runtime_blocker`;
- `applied_rate_uph`, `applied_duration_min`;
- `adds_smb_authority`, qui doit rester `false`.

## Environnement de simulation

`HarmoniaSimulationEnvironment` contient:

- `currentBgMgdl`;
- `deltaMgdl5m`;
- `iobU`;
- `cobG`;
- `currentBasalUph`;
- `maxBasalUph`;
- `maxSmbU`;
- `maxIobU`;
- `pumpBasalStepUph`;
- `pumpSmbStepU`;
- `sensorAgeMin`;
- `sensorNoise`;
- `seed`.

`randomizedEnvironment(seed=...)` permet de rejouer un environnement pompe/capteur aleatoire de facon deterministe.

## Branches actives

`BASAL_FIRST`:

- utilise une augmentation basale virtuelle bornee;
- ne propose pas de SMB virtuel;
- vise les contextes resistance/endogene/stress.

`MEAL_SUPPORT`:

- propose une petite aide virtuelle SMB + basale;
- reste bornee par `maxSmbU`, `maxIobU` et les pas pompe;
- vise les trajectoires repas avec delta positif.

`PROTECTIVE_REDUCTION`:

- reduit virtuellement la basale;
- vise activite/post-activite.

`BLOCKED`:

- bloque tout SMB virtuel;
- conserve la basale courante virtuelle;
- se declenche sur sensor/hypo/post-hypo/maxIOB/risque critique.

## Integration Auditor

Auditor recoit maintenant:

- `physiological_tree`;
- `harmonia_simulation`.

Le prompt precise que Harmonia est une branche sandbox. L'Auditor peut l'utiliser pour CONFIRM/SOFTEN/SHIFT_TO_TBR dans ses bornes existantes, mais ne peut pas traiter `simulated_smb_u` ou `simulated_basal_uph` comme une commande pompe.

## Integration Advisor

`AdvisorContext` et `AdvisorReport` portent maintenant `harmoniaSimulation`.

Ce lot n'ajoute pas encore d'auto-apply de preferences depuis Harmonia. La prochaine etape produit sera de transformer les patterns observes en cartes Advisor explicites, avec confirmation utilisateur.

## Integration Meal Advisor

Meal Advisor ajoute Harmonia au contexte envoye au modele uniquement comme aide pour `insulin_relevant_notes`.

La consigne precise:

- ne pas modifier l'estimation visuelle des glucides a cause de Harmonia;
- utiliser le contexte seulement pour expliquer l'absorption, la resistance ou l'incertitude.

## Tests ajoutes

- `HarmoniaSimulationEngineTest`: basal-first, blocage hypo, caps pompe/IOB, random seed deterministe.
- `AuditorPromptSafeguardsTest`: payload Harmonia et `applies_to_pump=false`.
- `MealVisionJsonParserTest`: contexte Harmonia borne a `insulin_relevant_notes`.
- `PatientStatePresentationBuilderTest`: affichage `Harmonia sim:`.
- `PatientStateRuntimeRefresherTest`: conservation/recalcul sur refresh physio.

## Limites

La partie simulation ne modifie pas:

- la dose reelle;
- l'autorite SMB;
- l'autorite TBR;
- les preferences utilisateur;
- les schemas Hormonitor;
- le ML synchrone.

La partie production modifie uniquement la basale finale, au meme point final que T3C, et uniquement comme proprietaire basal-first unique. La partie RBT-native rend cette propriete explicable et exportable. Elle ne modifie pas l'autorite SMB, les preferences utilisateur, Advisor, Control Center ou les schemas Hormonitor.

## Matrice repas non declare / meal_rise (verification 2026-06)

Objectif produit : l'arbre attrape le repas non declare ; Harmonia harmonise la posture avant sur-correction.

| Signal / etape | Detecte repas latent ? | Autorite dose ? | Passe par Harmonia ? |
|----------------|------------------------|-----------------|----------------------|
| `PhysioLatentState.mealProb` + causal | Oui → branche `meal` | Non | Oui (arbre) |
| `PhysiologicalTree` feuille undeclared hint | Oui (texte) | Non | Oui |
| `HarmoniaSimulation` `MEAL_SUPPORT` | Si meal≥0.60 et delta≥1 | Virtuel seulement | Oui |
| `HarmoniaProduction` depuis `MEAL_SUPPORT` | Reprise basale bornee | TBR si gates OK | Oui, **sans SMB** |
| `meal_rise_confirmed` (safety) | Delta/phase, souvent COB=0 | Uplift terminals | **Non — bypass** |
| `MealCorrectionContextResolver` | Phase, UAM, latent | SMB/TBR amont | **Non — bypass** |
| Autodrive early TBR 7.5–9 U/h | Montee « non expliquee » | TBR fort | **Non — aval dominant** |

**Consequence :** Harmonia **voit** le repas non declare dans l'arbre et peut simuler `MEAL_SUPPORT`, mais les chemins SMB/TBR agressifs en amont peuvent **deja avoir decide** avant que la production Harmonia ne s'applique (et seulement en basal-first residuel).

## Stabilisation glycemique / anti-yoyo (verification 2026-06)

| Mecanisme | Stabilise BG ? | Lien Harmonia |
|-----------|----------------|---------------|
| `PROTECTIVE_REDUCTION` (activite/post-activite) | Reduit basale virtuelle | Harmonia sim ; production si eligible |
| Blockers hypo / `postHypoBlock` | Bloque production Harmonia | Oui |
| `PatientEventMemory.correctionFragilityScore` | Arbre + TPO | Arbre lit ; **TPO applique prefs** |
| RBT `CHAOTIC` / post-hypo episodes | Dampen SMB | Parallele RBT |
| `PostHypoProjectionCap` | Cap rebound | Parallele safety ; **bug** si BG>plafond |
| TPO 45 min packs | Rails prefs temporaires | **Orthogonal** — voir overlay doc |

Harmonia n'a pas encore d'action dediee **yoyo** ; la stabilisation reelle est **partagee** avec TPO et RBT. Lot **H5** (plan §14 `aimi-harmonia-implementation.md`) vise a renforcer cette branche.

## Prochain lot recommande

### Priorite produit (alignement vision)

1. **H0** — `PostHypoProjectionCap` robuste (tick ne doit pas avorter).
2. **H4** — Meal-rise bridge : **partiel 2026-07-17** (`chooseAction` DIGESTION+meal_rise+BG>target+30 → `MEAL_SUPPORT` vs `PROTECTIVE`) ; reste harmoniser avec `MealCorrectionContextResolver`.
3. **H5** — Stabilisation : `correctionFragilityScore` → `PROTECTIVE_REDUCTION` / plafond hausse basale.
4. **H6** — Feuilles arbre → prompts Auditor (lecture seule) comme vraie 2e confirmation LLM.

### Technique (replay)

Ajouter un runner de replay Harmonia dedie:

- charger une sequence JSONL;
- injecter des environnements pompe/capteur aleatoires par seed;
- comparer decision reelle vs `harmonia_simulation`;
- exporter taux de blocage, divergence, caps appliques et faux positifs repas/endogene;
- preparer ensuite des cartes Advisor "Harmonia found recurring mismatch".
