# AIMI Control Center + Advisor Bridge 2026-06-14

## Objectif

Rendre le produit plus harmonieux entre:

- les préférences legacy AIMI,
- le `AIMI Control Center`,
- l'`AIMI Advisor`,
- et les comportements réellement observés par l'utilisateur.

Le besoin produit n'était pas seulement "ajouter des sliders". Il fallait surtout corriger trois défauts:

1. le Control Center ne disait pas clairement ce qu'il pilotait vraiment,
2. un curseur pouvait être perçu comme un preset absolu, alors que l'utilisateur attend un déplacement relatif depuis son état réel,
3. l'Advisor n'aidait pas encore assez à relier un résultat concret (`yoyo`, hyper lente à corriger, faux repas, etc.) à la bonne famille de comportement.

---

## Diagnostic produit

## 1. Toutes les préférences ne doivent pas être pilotées pareil

L'analyse du code et des documents existants confirme qu'il ne faut pas faire croire que **toutes** les préférences AIMI sont pilotées directement par les 5 familles.

Il existe en pratique deux couches:

- **couche standard pilotée par familles**:
  - protection,
  - capture repas,
  - stabilité,
  - physio,
  - autonomie.
- **couche expert / AIMI Lab**:
  - réglages fins de gouvernance,
  - modes repas détaillés,
  - trajectoire / straight-line tube,
  - options ML avancées,
  - shadow / auditor / LLM / connecteurs contextuels,
  - raffinements techniques qui ne doivent pas être réécrits silencieusement par un slider macro.

Le défaut initial du Control Center était donc moins un manque de sliders qu'un manque de **transparence sur la couverture réelle**.

## 2. Le curseur devait partir des valeurs existantes

Le besoin utilisateur exprimé dans nos échanges est très clair:

- si des préférences existent déjà,
- le niveau affiché doit refléter cet état,
- puis un déplacement de slider doit avancer de **plusieurs granularités cohérentes depuis cet état réel**,
- pas revenir à un preset caché qui écrase la personnalisation existante.

## 3. L'Advisor devait parler en familles, pas seulement en clés

Pour un comportement observé comme:

- "je fais le yoyo",
- "mes repas non déclarés sont corrigés trop tard",
- "j'ai trop d'hyper sans que les curseurs m'aident à comprendre",

le produit doit répondre au niveau de l'intention:

- réduire la nervosité,
- renforcer la capture repas,
- augmenter la protection,
- remonter l'autonomie si la stratégie choisie reste trop passive.

---

## Ce qui a été implémenté

## 1. Registre explicite de couverture des familles

Ajout du registre:

- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/compose/AimiBehaviorFamilyRegistry.kt`

Ce registre définit, pour chaque famille:

- les clés **pilotées directement** par les sliders ou modes,
- les clés **expert-layer** liées à la même intention clinique mais volontairement laissées hors contrôle direct.

Effet produit:

- le Control Center ne prétend plus tout piloter,
- l'utilisateur voit ce qui relève du cockpit standard et ce qui reste expert,
- l'Advisor peut maintenant raisonner avec la même taxonomie.

## 2. Projection de la famille repas nettoyée

Correction importante:

- la famille `MealCapture` ne mélange plus un signal d'autonomie (`OApsAIMIautoDriveActive`) avec les préférences réellement liées à la capture repas.

Effet produit:

- le niveau "Capture repas" reflète beaucoup mieux la stratégie repas,
- on évite une fausse lecture où l'autonomie gonfle artificiellement le profil repas.

## 3. Write-back ancré sur la valeur actuelle

Le write-back des familles pilotées par ladder n'écrit plus seulement une cible statique.

Il utilise désormais:

- la valeur actuelle lue en préférence,
- l'échelon le plus proche,
- puis applique le delta de niveau demandé depuis cet ancrage réel.

Effet produit:

- si l'utilisateur est déjà entre deux mondes legacy, le slider part de cet état,
- un cran à droite ou à gauche correspond à un mouvement cohérent,
- on limite les réécritures brusques ou incompréhensibles.

## 4. Visibilité produit renforcée dans le Control Center

Le Control Center expose maintenant:

- le nombre de familles,
- le nombre de clés pilotées par sliders,
- le nombre de clés expert-layer,
- la couverture par famille.

Le libellé ambigu `expert profiles` a été remplacé par une formulation plus correcte de type `expert mixes`.

Effet produit:

- le cockpit explique mieux ce qu'il fait,
- l'utilisateur comprend qu'un profil peut rester partiellement expert,
- on diminue la confusion entre "lecture", "projection" et "réécriture".

## 5. Bridge Advisor -> familles

Ajout d'un nouveau pont produit:

- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/advisor/AimiBehaviorFamilyBridge.kt`

Ce bridge prend les métriques observées et génère des suggestions de familles:

- `swing_variability`
- `meal_rise`
- `hypo_guard`
- `hyper_stable`
- `mixed_balance`

Le principe est:

- lire les résultats observés,
- produire un brouillon de familles,
- harmoniser ce brouillon avec l'Advisor du Control Center,
- montrer ensuite le **preview exact des clés legacy** avant application.

Effet produit:

- l'Advisor ne dit plus seulement "change telle clé",
- il peut dire "ton problème ressemble à un déficit de stabilité" ou "ta stratégie repas est trop prudente pour ce type de montée".

## 6. Variabilité ajoutée aux métriques Advisor

`AdvisorMetrics` intègre maintenant `variabilityCv`.

Effet produit:

- les situations de yoyo sont mieux identifiées,
- l'Advisor peut proposer un mouvement de famille sur un critère de variabilité mesuré,
- au lieu de sur-interpréter seulement l'hyper ou l'hypo séparément.

## 7. Carte causale produit dans l'Advisor

L'Advisor affiche maintenant une **AIMI causal map** avant le bridge familles.

Cette carte ne propose pas encore directement des clés brutes. Elle répond d'abord à la question produit:

- quel est le type de problème observé ?
- quelle famille est probablement en cause en premier ?
- quelle famille est secondaire ?
- quelle confiance accorder à cette lecture ?

Les cas actuellement couverts de manière déterministe sont:

- `yoyo_instability`
- `meal_latency`
- `hypo_overshoot`
- `hyper_passive`
- `physio_ambiguity`

Effet produit:

- l'utilisateur comprend mieux **pourquoi** AIMI propose un mouvement de famille,
- l'Advisor ne saute plus directement au "quoi faire" sans expliquer le "qu'est-ce qui semble casser",
- les situations ambiguës de type cortisol / endocrine / faux repas sont enfin représentées explicitement au niveau produit.

## 8. Coach LLM désormais ancré sur la carte causale

Le Coach LLM n'est plus nourri uniquement par:

- les métriques,
- le bloc OREF,
- les recommandations techniques.

Il reçoit maintenant aussi un résumé structuré de la **carte causale familles**.

Effet produit:

- la narration du Coach reste plus alignée avec le produit réel,
- il explique plus facilement des cas comme "le yoyo vient d'abord d'un problème de stabilité" ou "la montée ressemble à un déficit de capture repas plutôt qu'à un manque global d'agressivité",
- on réduit le risque d'un texte LLM convaincant mais déconnecté des leviers réellement disponibles dans l'interface.

---

## Ce que cela change avant / après

## Avant

- le Control Center projetait les réglages mais sans dire clairement ce qu'il gérait réellement,
- le niveau repas pouvait être pollué par l'autonomie,
- un déplacement de slider risquait d'être vécu comme une réécriture de preset,
- l'Advisor restait trop éloigné du langage produit attendu par l'utilisateur.

## Après

- chaque famille a une couverture plus explicite,
- les réglages existants sont mieux respectés lors du déplacement,
- la capture repas est mieux séparée de l'autonomie,
- l'Advisor peut relier un résultat observé à un mouvement cohérent de familles,
- l'Advisor peut aussi expliquer **quelle famille semble responsable en premier**,
- l'utilisateur peut prévisualiser les clés legacy exactes avant de confirmer.

---

## Ce qui n'est volontairement pas mis sous slider

Tout n'a pas vocation à être absorbé immédiatement dans le cockpit standard.

Restent volontairement en couche experte:

- les facteurs repas détaillés par type de repas,
- les intervalles repas détaillés,
- la gouvernance adaptive basal fine,
- trajectory guard / straight-line tube,
- certaines options physio contextuelles,
- les raffinements ML avancés,
- les éléments de diagnostic, auditor, shadow et instrumentation.

La logique produit est la suivante:

- le cockpit standard règle l'intention dominante,
- la couche experte permet le raffinement clinique ou R&D,
- mais le standard doit toujours rester lisible et suffisant pour 80 % des ajustements de comportement.

---

## Pourquoi ne pas brancher le LLM directement sur les JSON tout de suite

Le LLM est pertinent, mais **pas comme première couche de décision**.

Si on branche d'abord un LLM sur les JSON pour suggérer des réglages, on prend le risque de:

- créer des conseils non alignés avec les familles réellement disponibles,
- produire des explications convaincantes mais non actionnables,
- introduire un écart entre narration produit et write-back legacy.

La bonne architecture produit est:

1. **base déterministe**:
   - métriques,
   - familles,
   - preview exact des clés.
2. **couche d'explication / narration assistée LLM**:
   - lecture des JSON AIMI / HORMONITOR,
   - hypothèse causale,
   - reformulation pour l'utilisateur,
   - mais toujours contrainte par les familles et le write-back existant.

---

## Proposition d'évolution produit suivante

Étape suivante recommandée:

- intégrer dans l'Advisor une vue "Observed pattern -> likely family cause".

Exemples:

- `yoyo important`:
  - suspect principal `Stability`,
  - parfois `Protection` trop correctif,
  - parfois capture repas tardive suivie de sur-correction.
- `hyper persistante après montée rapide`:
  - suspect principal `MealCapture`,
  - puis `Protection` si l'autorité de correction reste trop faible.
- `faux repas hormonaux / cortisol / dawn`:
  - suspect principal `MealCapture` trop assertif,
  - ou `Physio` trop faible dans les contextes ambigus.

Le LLM, s'il est activé plus tard, devrait servir à:

- raconter cette hypothèse dans un langage clinique lisible,
- relier plusieurs événements successifs,
- proposer l'ordre d'ajustement,
- mais jamais court-circuiter le bridge familles -> clés.

---

## Fichiers principaux modifiés

- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/compose/AimiBehaviorFamilyRegistry.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/compose/AimiControlCenterSnapshot.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/compose/AimiControlCenterSupport.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/compose/AimiControlCenterScreen.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/advisor/AdvisorModels.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/advisor/AimiAdvisorService.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/advisor/AimiBehaviorFamilyBridge.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/advisor/AimiBehaviorCausalAnalyzer.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/advisor/AimiProfileAdvisorActivity.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/advisor/AiCoachingService.kt`
- `plugins/aps/src/main/res/values/strings.xml`

Tests ajoutés ou adaptés:

- `plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/advisor/AimiBehaviorCausalAnalyzerTest.kt`
- `plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/compose/AimiControlCenterSupportTest.kt`
- `plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/advisor/AimiBehaviorFamilyBridgeTest.kt`
- `plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/advisor/tuning/TuningContextEngineTest.kt`
- `plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/advisor/PkpdAdvisorTailDampingTest.kt`

---

## Validation réalisée

- compilation complète APK:
  - `./gradlew --no-daemon :app:assembleDebug`
  - **succès**

Les tests unitaires ciblés n'ont pas encore été relancés avec un passage complet validé dans cette itération, mais le code modifié compile dans l'ensemble de l'application.

---

## Références documentaires utilisées

- `docs/AIMI_CONTROL_CENTER_PRODUCT_BLUEPRINT_2026-06-10.md`
- `docs/AIMI_FAMILY_RUNTIME_HARMONIZATION_2026-06-11.md`
- `docs/AIMI_TUNING_AND_ADVISOR.md`
