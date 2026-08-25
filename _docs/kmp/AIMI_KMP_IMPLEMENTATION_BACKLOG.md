# AIMI KMP/iOS — backlog d'implémentation ordonné par gates

> **Usage :** transformer l'étude d'architecture en lots livrables et vérifiables.  
> **Règle :** une tâche n'est `Done` que si son critère d'acceptation est automatisé ou accompagné
> d'une preuve archivée.  
> **Référence :** `AIMI_KMP_MIGRATION_BLUEPRINT.md` et annexes 5 à 9.

## 1. Workstreams et responsabilités

| Code | Domaine | Responsabilité |
|---|---|---|
| ARCH | architecture/état/replay | contrats, modules, state machine, capture, CI, consolidation |
| CORE | décision/physiologie | Tree, patient state, Harmonia, RBT, PKPD, safety, Hormonitor |
| ML | modèles/apprentissage | TFLite, NN Kotlin, SMB/basal/T3C, Autodrive learning, model stores |
| IOS | plateforme Apple | framework, Swift bridge, lifecycle, BLE, HealthKit, background, stockage |
| PRODUCT | fonctions adjacentes | Advisor, Auditor, TPO, Meal Advisor, UI, LLM, SOS |
| DEVICE | intégration pompe/CGM | capabilities, quantification, enactment, receipts et recovery |
| SAFETY | validation indépendante | invariants, review de gates, campagne shadow et go/no-go |

Une personne peut tenir plusieurs rôles, mais une gate dose-facing doit avoir une seconde revue.

## 2. Dépendances majeures

```text
M0 Freeze
  |
  v
M1 Capture Android exécutable -----> M2 Contrats/modules KMP
  |                                      |
  +----------------+---------------------+
                   v
             M3 Vertical slice
                   |
                   v
             M4 Engine stateful
              /       |       \
             v        v        v
         M5 CORE    M6 ML    M7 Physio/Hormonitor
              \       |       /
               +------v------+
                      M8 Android master KMP
                              |
                 +------------+------------+
                 v                         v
             M9 iOS/Trio              M10 Product parity
                 +------------+------------+
                              v
                         M11 Shadow
                              |
                              v
                      M12 Controlled enactment
```

## 3. Milestone M0 — décisions et freeze

**Objectif :** savoir exactement quelle version et quelles fonctions sont portées.

| ID | Owner | Tâche | Livrable / acceptation |
|---|---|---|---|
| M0.1 | ARCH | Créer un tag immuable du commit AIMI de référence | SHA et tag inscrits dans chaque capture |
| M0.2 | ARCH | Établir le manifest des 441 fichiers et de leurs propriétaires | chaque fichier a domaine, criticité, wave et disposition |
| M0.3 | PRODUCT | Lister toutes les prefs/features activables et leur autorité | `observe/config/dose/platform` attribué à chaque feature |
| M0.4 | ML | Figer les artefacts et schémas ML | SHA de `modelUAM`, schema 18f/21f/16f, model cards |
| M0.5 | IOS/DEVICE | Choisir application hôte et première paire pompe/CGM | matrice de capabilities signée ; BLE/classic explicite |
| M0.6 | SAFETY | Décider les défauts corrigés avant baseline | ADR pour train/serve skew, lifecycle, Auditor/TPO et Hormonitor loss |
| M0.7 | ARCH | Définir unités, arrondis et tolérances | égalité finale après quantification documentée |
| M0.8 | SAFETY | Définir données privées/publiques et consentement | politique de corpus, redaction et rétention |

**Gate M0 :** aucune ambiguïté sur le matériel v1, le modèle actif, les fonctions actives et la
référence comportementale.

## 4. Milestone M1 — capture Android réellement exécutable

### M1-A — registre des reads et états

| ID | Owner | Tâche | Acceptation |
|---|---|---|---|
| M1.1 | ARCH | Générer le registre des 239 `private var` de `DetermineBasalAIMI2` | taxonomie complète INPUT/CONFIG/WORKING/STATE/LEARNER/CACHE/EFFECT/TELEMETRY |
| M1.2 | ARCH/CORE | Relever chaque lecture de service pendant le tick | aucun accès DB/prefs/physio non recensé |
| M1.3 | CORE | Inventorier singletons et mémoires inter-ticks | politique persist/rebuild/reset pour chacune |
| M1.4 | ML | Inventorier états optimizer/model/trainer | generation, high-water marks et rollback documentés |
| M1.5 | PRODUCT | Inventorier intents, overlays et callbacks asynchrones | consommation/expiry/tick boundary définis |

### M1-B — contrats de capture

| ID | Owner | Tâche | Acceptation |
|---|---|---|---|
| M1.6 | ARCH | Implémenter `AimiInputSnapshot` v1 côté Android | capture sérialisable, unités et freshness explicites |
| M1.7 | ARCH | Implémenter `AimiConfigSnapshot` | les 109 clés de `DetermineBasal` et les clés du shell ont une projection |
| M1.8 | ARCH/CORE | Implémenter `AimiEngineState` v1 en observation | hash avant/après présent sans encore gouverner le code |
| M1.9 | ML | Capturer inputs/outputs UAM et manifest modèle | chaque tick UAM possède schema/model/runtime ID |
| M1.10 | ARCH | Définir `engine-replay-v1` séparé de `decision-projection-v1` | parser/writer round-trip sans perte numérique |
| M1.11 | ARCH | Capturer toutes les sorties anticipées | aucun early return sans expected result et state |

### M1-C — preuve de reproductibilité

| ID | Owner | Tâche | Acceptation |
|---|---|---|---|
| M1.12 | ARCH | Rejouer la capture dans un harness Android isolé | même commande et mêmes owners sur le corpus initial |
| M1.13 | SAFETY | Ajouter scénarios NaN/stale/missing/restart | comportement protecteur vérifié |
| M1.14 | ARCH | Ajouter test de contamination par tick précédent | scratch empoisonné n'altère pas le tick suivant |
| M1.15 | DEVICE | Enregistrer commande sémantique et commande quantifiée | grille pompe et receipt traçables |

**Gate M1 — go/no-go majeur :** si Android ne peut pas reproduire ses propres décisions depuis le
snapshot et l'état enregistrés, arrêter le portage iOS et compléter le contrat.

## 5. Milestone M2 — socle KMP et testkit

| ID | Owner | Tâche | Acceptation |
|---|---|---|---|
| M2.1 | ARCH | Créer les modules `aimi-contracts`, `aimi-engine`, `aimi-learning`, `aimi-io`, `aimi-testkit` | JVM, `iosArm64`, `iosSimulatorArm64` compilent |
| M2.2 | ARCH | Ajouter règle d'imports interdits dans le domaine | build échoue sur Android/JVM I/O/prefs/DB |
| M2.3 | ARCH | Définir IDs d'unité et valeurs timed | `Missing/Denied/Unsupported/Stale` testés |
| M2.4 | ARCH | Ajouter clock et PRNG AIMI | tests déterministes JVM/Native |
| M2.5 | ARCH | Mettre en place serialization versionnée | migrations n-1→n et unknown fields testés |
| M2.6 | ARCH | Construire le comparateur de parité | sévérités C0–C5 et distance aux seuils |
| M2.7 | ARCH | CI macOS/iOS simulator | build + tests communs obligatoires sur chaque PR |
| M2.8 | ARCH | Packaging test framework/Swift | API consommable dans un petit harness Swift |

**Gate M2 :** aucun code thérapeutique n'est encore requis ; les contrats, la CI et le replay sont
prêts avant le déplacement massif des sources.

## 6. Milestone M3 — vertical slice Tree → commande sûre

**Objectif :** prouver une tranche verticale sur Native avant de migrer tout AIMI.

| ID | Owner | Tâche | Acceptation |
|---|---|---|---|
| M3.1 | CORE | Porter DTO patient/causal minimaux | aucun type AAPS dans l'API |
| M3.2 | CORE | Porter `PhysiologicalTree` pour un sous-ensemble de signaux | mêmes beliefs sur golden corpus |
| M3.3 | CORE | Porter `HarmoniaDecisionEngine` | mêmes actions/blockers/targets |
| M3.4 | CORE | Porter sélection RBT/channel minimale | même authority/owner |
| M3.5 | CORE | Porter terminal safety et quantification simulée | même Hold/SMB/TBR final |
| M3.6 | ARCH | Exécuter slice JVM, simulateur et iPhone | aucune divergence C0–C2 |
| M3.7 | SAFETY | Fault injection : valeur manquante, stale et exception modèle | safe hold/neutralité conformes |

**Gate M3 :** validation de la faisabilité Native et de l'API. Une divergence structurelle déclenche
une correction du contrat avant la suite.

## 7. Milestone M4 — extraction du moteur stateful

### M4-A — working state

| ID | Owner | Tâche | Acceptation |
|---|---|---|---|
| M4.1 | ARCH | Introduire `AimiTickWorkingState` Android | scratch initialisé une fois par tick |
| M4.2 | ARCH/CORE | Migrer champs tick-local par lots | aucun changement dans replay Android |
| M4.3 | ARCH | Convertir buffers console/JSON en trace structurée | reason codes stables, texte localisé hors moteur |
| M4.4 | ARCH | Supprimer aliasing des paramètres mutables | snapshot profondément immuable |

### M4-B — state machine

| ID | Owner | Tâche | Acceptation |
|---|---|---|---|
| M4.5 | CORE | Migrer hystérésis, holds et episodic memories | stateBefore/stateAfter comparables |
| M4.6 | ARCH | Définir actor single-writer par patient | deux ingress concurrents n'exécutent pas deux commandes |
| M4.7 | ARCH/DEVICE | Définir commit + enactment receipt idempotent | crash entre opérations rejoué sans double dose |
| M4.8 | ARCH | Définir lifecycle restart/migration d'état | tests kill/relaunch/version incompatible |

### M4-C — stages

| ID | Owner | Tâche | Acceptation |
|---|---|---|---|
| M4.9 | ARCH/CORE | Regrouper les 46 positions en stages S1–S6 | ordre et early returns identiques |
| M4.10 | ARCH | Remplacer early returns implicites par résultats sealed | chaque sortie expose command/state/events |
| M4.11 | ARCH | Retirer les lectures externes des stages | règle statique et test de fake ports |
| M4.12 | ARCH | Retirer les writes/notifications/enactment des stages | uniquement événements de résultat |

**Gate M4 :** le nouveau moteur stateful reproduit le moteur Android sur le corpus séquentiel avant
que le shell AAPS ne change d'autorité.

## 8. Milestone M5 — cœur décisionnel complet

### M5-A — état patient et physiologie calculée

| ID | Owner | Tâche | Acceptation |
|---|---|---|---|
| M5.1 | CORE | Patient event memory et causal posterior | replays meal/post-hypo/dawn |
| M5.2 | CORE | phases, absorption et patterns | hystérésis/restart testés |
| M5.3 | CORE | effort/activity belief et mémoire | activité déclarée/détectée distinguée |
| M5.4 | CORE | latent state et UAM hypotheses | confidence séparées CGM/wearable/causal |

### M5-B — autorités

| ID | Owner | Tâche | Acceptation |
|---|---|---|---|
| M5.5 | CORE | Tree complet + MealCertainty | repas confirmé ne contourne pas hypo safety |
| M5.6 | CORE | Harmonia décision + production + harmonizer | mêmes blockers/actions/channels |
| M5.7 | CORE | RBT leaves/resolver/memory/paradox registry | mêmes tensions et authority |
| M5.8 | CORE | ownership SMB/basal/T3C/Autodrive | exclusivité et exceptions prouvées |

### M5-C — prédiction, PKPD et sécurité

| ID | Owner | Tâche | Acceptation |
|---|---|---|---|
| M5.9 | CORE | kernels/observer/governors PKPD | mêmes DIA/peak/activity stages |
| M5.10 | CORE | scenario/prediction authority/terminal snapshot | unique vérité dose-facing |
| M5.11 | CORE | safety, stacking, post-hypo, load governor | property tests et corpus limites |
| M5.12 | CORE/DEVICE | basal/SMB terminal invariants | commande exacte après quantification |

**Gate M5 :** aucune divergence C0–C2 sur le corpus cœur, JVM et Native.

## 9. Milestone M6 — ML et learners

### M6-A — TFLite/UAM

| ID | Owner | Tâche | Acceptation |
|---|---|---|---|
| M6.1 | ML | Corriger/fixer politique d'installation Android | modèle importé non écrasé involontairement |
| M6.2 | ML | Implémenter `UamInferencePort` et metadata | shape/type/schema/SHA validés |
| M6.3 | ML/IOS | Exécuter même `modelUAM.tflite` sur iPhone | golden 18f dans tolérances |
| M6.4 | ML | Fallback/circuit breaker/rollback | corruption et runtime failure testés |

### M6-B — réseaux Kotlin et datasets

| ID | Owner | Tâche | Acceptation |
|---|---|---|---|
| M6.5 | ML | Porter `AimiNeuralNetwork` + PRNG + codec | Native tests forward/train/save/load |
| M6.6 | ML | Créer `DatasetStore` transactionnel | append/high-water/rewrite/crash testés |
| M6.7 | ML | Corriger le skew `trendIndicator` SMB | feature servie = feature entraînée, schema migré |
| M6.8 | ML | Trainer SMB + validation/publish | aucune publication sans holdout gates |
| M6.9 | ML | Basal/T3C trainer et governance | fenêtres/checkpoints durables |
| M6.10 | ML/ARCH | Résoudre un `AimiResolvedModelBundle` par tick | générations/SHA/schemas de tous modèles et checkpoints figés dans le replay |
| M6.11 | ML/PRODUCT | Versionner la provenance des labels | source, config, state et modèle producteurs reconstructibles |

### M6-C — learners et Autodrive

| ID | Owner | Tâche | Acceptation |
|---|---|---|---|
| M6.12 | ML | BasalLearner et UnifiedReactivity | history version explicite, restart parity |
| M6.13 | ML | Autodrive DataLake/backfill/trainer | jobs reprenables, candidat `KEPT` ≠ failure |
| M6.14 | ML | ModelRegistry et publication atomique | tick voit une génération cohérente |
| M6.15 | ML | OnlineLearner et OREF personnel restent shadow | test empêchant activation accidentelle |
| M6.16 | ML/IOS | Benchmark entraînement réel | CPU/mémoire/énergie/expiration documentés |

**Gate M6 :** le loop fonctionne sans entraînement ; restart, interruption et corruption ne changent
pas le modèle incumbent ; les décisions avec modèles fixés sont identiques.

## 10. Milestone M7 — données physiologiques et Hormonitor

### M7-A — contrat de données

| ID | Owner | Tâche | Acceptation |
|---|---|---|---|
| M7.1 | CORE/IOS | Définir `PhysiologySnapshot` typé | source/unité/metric/quality/age par signal |
| M7.2 | CORE | Séparer confidence CGM/wearable/causal | absence wearable ne ferme pas qualité CGM |
| M7.3 | IOS | Mapper HealthKit HR/steps/sleep/temp | matrice device réelle et permissions |
| M7.4 | CORE/IOS | Séparer RMSSD et SDNN | baselines et seuils indépendants |
| M7.5 | IOS | Définir neutralité et stale policy | refus/absence/stale scénarisés |

### M7-B — lifecycle et refresh

| ID | Owner | Tâche | Acceptation |
|---|---|---|---|
| M7.6 | CORE | Sortir refreshs asynchrones du moteur | aucune reconstruction lossy dose-facing |
| M7.7 | IOS | Acquisition event-driven | pas de cadence supposée ; freshness visible |
| M7.8 | CORE/IOS | Replays avec trous de données | dégradation progressive et sûre |

### M7-C — Hormonitor

| ID | Owner | Tâche | Acceptation |
|---|---|---|---|
| M7.9 | CORE | Codec et events communs schema 1.5+ | `hrv_metric_kind` et compatibilité 1.4 |
| M7.10 | CORE | Corriger overflow `DROP_OLDEST` | pertes comptées exactement ou spool durable |
| M7.11 | IOS | writer/store/export iOS | sandbox, rotation, atomicité, Document Picker |
| M7.12 | IOS/PRODUCT | viewer minimal | mêmes agrégations, UX adaptée |

**Gate M7 :** aucune donnée physiologique absente n'est convertie en preuve ; Hormonitor mesure ses
pertes et n'affecte jamais le chemin doseur.

## 11. Milestone M8 — Android consomme le moteur KMP

| ID | Owner | Tâche | Acceptation |
|---|---|---|---|
| M8.1 | ARCH | Adapter AAPS types → snapshot | mapping exhaustif et versionné |
| M8.2 | ARCH | Adapter résultat → `RT` sans enactment direct | même résultat AAPS |
| M8.3 | DEVICE | Effect runner Android | receipts/idempotence/caps |
| M8.4 | ARCH | Dual-run ancien/nouveau en shadow | rapport par tick |
| M8.5 | SAFETY | Campagne 72 h puis corpus étendu | zéro C0–C2 inexpliqué |
| M8.6 | ARCH | Basculer Android master avec kill switch | rollback instantané |
| M8.7 | ARCH | Supprimer chemin ancien seulement après soak | aucune dépendance résiduelle non documentée |

**Gate M8 :** Android fonctionne en production de test sur le moteur commun. C'est le prérequis de
l'autorité iOS ; sinon deux moteurs divergeraient dès la première correction future.

## 12. Milestone M9 — framework Apple et Trio

### M9-A — distribution du framework

| ID | Owner | Tâche | Acceptation |
|---|---|---|---|
| M9.1 | IOS | Définir API Swift stable et façade | aucun type non exportable ; erreurs explicites |
| M9.2 | IOS | XCFramework/SPM et CI | device/simulator, symbols et versioning |
| M9.3 | IOS | composition root des actuals | clock/store/TFLite/logger/scheduler injectés |
| M9.4 | IOS | migrations state/config/models | rollback et version incompatible testés |

### M9-B — données Trio/LoopKit

| ID | Owner | Tâche | Acceptation |
|---|---|---|---|
| M9.5 | IOS | Map profile/glucose/temp basal | golden mapping avec unités |
| M9.6 | IOS | Map dose/history/IOB/COB | mêmes fenêtres et freshness |
| M9.7 | IOS/DEVICE | Capability/quantization du driver v1 | même commande quantifiée |
| M9.8 | IOS | App groups/storage coordination | aucune double vérité state |

### M9-C — lifecycle

| ID | Owner | Tâche | Acceptation |
|---|---|---|---|
| M9.9 | IOS | Actor single-writer et triggers BLE | pas de tick concurrent |
| M9.10 | IOS | state restoration/relaunch | scénarios kill/reboot/reconnect |
| M9.11 | IOS | BGProcessing opportuniste | cancellation/checkpoint/expiration sûrs |
| M9.12 | IOS | notifications/capabilities | limites Critical Alerts explicites |
| M9.13 | IOS | diagnostics/kill switch | export et rollback sans outil de développement |

**Gate M9 :** l'app iOS peut exécuter le moteur, conserver son état et produire des commandes shadow
sans dépendre d'un timer périodique garanti.

## 13. Milestone M10 — parité des fonctions produit

### M10-A — configuration et intents

| ID | Owner | Tâche | Acceptation |
|---|---|---|---|
| M10.1 | PRODUCT | Reason codes et localization | replay indépendant de la langue |
| M10.2 | PRODUCT | Intent store transactionnel | one-shot consommé exactement une fois |
| M10.3 | PRODUCT | Meal Advisor common policy | safety avant advisor, expiry/confirmation |
| M10.4 | PRODUCT | Context intents et influence | source/TTL/confiance explicites |

### M10-B — TPO et Auditor

| ID | Owner | Tâche | Acceptation |
|---|---|---|---|
| M10.5 | PRODUCT | TPO devient overlay immuable | config stable pendant un tick |
| M10.6 | PRODUCT | Rebase/revert conflict-safe | édition utilisateur jamais écrasée |
| M10.7 | PRODUCT/CORE | Local Sentinel commun | déterministe, offline et replayable ; placé après le second passage Harmonia, avant la safety terminale, avec autorité uniquement restrictive |
| M10.8 | PRODUCT | Auditor externe advice tick N+1 | aucun callback ne mute résultat N |

### M10-C — UI et réseau

| ID | Owner | Tâche | Acceptation |
|---|---|---|---|
| M10.9 | PRODUCT/IOS | SwiftUI dashboard/Control Center | preview exact des changements |
| M10.10 | PRODUCT/IOS | Health/model/learner/Hormonitor status | source/âge/version visibles |
| M10.11 | PRODUCT/IOS | providers LLM et Keychain | offline, redaction, timeout, schema validation |
| M10.12 | PRODUCT/IOS | SOS capability | UX supportée/non supportée documentée |

**Gate M10 :** chaque fonction produit annonce son autorité et son fallback ; aucune latence réseau
n'entre dans le tick.

## 14. Milestone M11 — shadow iOS

| ID | Owner | Tâche | Acceptation |
|---|---|---|---|
| M11.1 | IOS | Enregistrer chaque décision sans enactment | corrélation avec événements et receipts Android |
| M11.2 | ARCH | Comparaison automatique quotidienne | rapport C0–C5, modèles/config/state hashes |
| M11.3 | SAFETY | Revue des divergences et ADR | aucune divergence ignorée comme “float” sans preuve |
| M11.4 | IOS | Soak background/relaunch/BLE | taux de ticks, stale et recovery mesurés |
| M11.5 | ML | Soak models/training | publications, restarts et incumbent vérifiés |
| M11.6 | CORE | Soak physio/Hormonitor | gaps, source HRV et overflow mesurés |
| M11.7 | SAFETY | Atteindre durée et diversité décidées en M0 | plusieurs semaines, scénarios obligatoires couverts |

**Gate M11 :** comité go/no-go explicite. Une compilation, un test isolé ou une journée parfaite ne
permettent pas le passage à l'enactment.

## 15. Milestone M12 — enactment contrôlé

| ID | Owner | Tâche | Acceptation |
|---|---|---|---|
| M12.1 | DEVICE | Activer une paire pompe/CGM et un petit périmètre | allowlist explicite |
| M12.2 | SAFETY | Limites conservatrices de lancement | caps et fallbacks plus protecteurs documentés |
| M12.3 | IOS | Monitoring local et support package | diagnostic disponible sans réseau |
| M12.4 | IOS/DEVICE | Exercices disconnect/reconnect/duplicate commands | aucune double dose, recovery sûr |
| M12.5 | ARCH | Rollback engine/model/state schema | procédure testée sur appareil |
| M12.6 | SAFETY | Revue terrain progressive | ouverture par capabilities, pas globale |

## 16. Lots explicitement hors v1

Sauf décision contraire en M0 :

- port complet de toute l'application AAPS ;
- UI Android reproduite pixel pour pixel ;
- toutes les pompes Bluetooth Classic ;
- activation clinique d'OnlineLearner ;
- activation décisionnelle OREF personnel/ONNX placeholders ;
- auto-application libre de recommandations LLM ;
- garantie d'un loop par timer toutes les cinq minutes ;
- promesse d'alertes Critical Alerts sans entitlement ;
- certification réglementaire et étude clinique.

## 17. Tableau de sortie par milestone

| Milestone | Produit observable | Autorité pompe |
|---|---|---|
| M0 | scope et baseline | Android ancien |
| M1 | replay Android complet | Android ancien |
| M2 | socle KMP/Swift harness | aucune |
| M3 | slice Native | aucune |
| M4 | engine stateful en comparaison | Android ancien |
| M5–M7 | domaines communs | Android ancien |
| M8 | Android sur moteur KMP | Android KMP avec rollback |
| M9–M10 | iOS fonctionnel en shadow | aucune sur iOS |
| M11 | preuve longitudinale | aucune sur iOS |
| M12 | enactment limité | iOS allowlistée |
