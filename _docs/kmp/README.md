# Dossier de décision — migration AIMI vers KMP/iOS

> **Référence AIMI :** `dev_OAPSAIMI` @ `06e7bc5021ca8fdd976505d1fefb03cc88681c19`  
> **Référence KMP :** `kmp` @ `4957c26eb85a71103e649498e7e991cb473e3098`  
> **Date d'audit :** 2026-08-25

## Document à lire en premier

[`AIMI_KMP_MIGRATION_BLUEPRINT.md`](AIMI_KMP_MIGRATION_BLUEPRINT.md) est le document directeur.
Il définit la décision d'architecture, la parité recherchée, la frontière stateful, les modules,
les gates et le budget consolidé.

[`AIMI_KMP_IMPLEMENTATION_BACKLOG.md`](AIMI_KMP_IMPLEMENTATION_BACKLOG.md) transforme cette décision
en milestones M0 à M12, tâches propriétaires et critères de sortie vérifiables.

Les annexes 5 à 9 complètent ou corrigent les études initiales. En cas de contradiction, le
blueprint et ces annexes spécialisées font autorité sur `AIMI_KMP_MIGRATION_STUDY.md` et les annexes
1 à 4.

## Répartition de l'audit

| Domaine attribué | Livrable | Contenu |
|---|---|---|
| Orchestrateur architecture | [`AIMI_KMP_MIGRATION_BLUEPRINT.md`](AIMI_KMP_MIGRATION_BLUEPRINT.md) | périmètre, architecture cible, ports, stages, gates, coûts |
| Agent ML/apprentissage | [`annex-5-ml-training-migration.md`](annex-5-ml-training-migration.md) | TFLite, SMB/basal/T3C, learners, Autodrive ML, scheduling et model stores |
| Agent cœur/physiologie | [`annex-6-core-physiology-hormonitor-migration.md`](annex-6-core-physiology-hormonitor-migration.md) | Tree, Harmonia, RBT, PKPD, safety, HealthKit et Hormonitor |
| Agent iOS/runtime | [`annex-7-ios-runtime-trio-integration.md`](annex-7-ios-runtime-trio-integration.md) | framework Apple, lifecycle, BLE/background, Trio, stockage, distribution |
| Orchestrateur état/replay | [`annex-8-state-replay-and-extraction-contract.md`](annex-8-state-replay-and-extraction-contract.md) | snapshot, état durable, commit, replay exécutable, extraction DetermineBasal |
| Orchestrateur produit | [`annex-9-product-advisor-ui-and-optional-surfaces.md`](annex-9-product-advisor-ui-and-optional-surfaces.md) | Advisor, Auditor, TPO, Meal Advisor, UI, LLM, SOS, outils |
| Orchestrateur programme | [`AIMI_KMP_IMPLEMENTATION_BACKLOG.md`](AIMI_KMP_IMPLEMENTATION_BACKLOG.md) | ordre d'exécution M0–M12, dépendances, owners et gates d'acceptation |

## Études initiales conservées comme preuves

| Document | Valeur conservée | Limite |
|---|---|---|
| [`annex-1-milos-kmp-audit.md`](annex-1-milos-kmp-audit.md) | état de la fondation KMP et méthode de migration | ne prouve pas l'exécution Native d'AIMI |
| [`annex-2-aimi-portability-inventory.md`](annex-2-aimi-portability-inventory.md) | inventaire 441 fichiers, LOC et dépendances | la classification par imports sous-estime état et types transitifs |
| [`annex-3-ios-platform-blockers.md`](annex-3-ios-platform-blockers.md) | contraintes background, matériel et distribution | certaines conclusions sont trop catégoriques |
| [`annex-4-branch-divergence.md`](annex-4-branch-divergence.md) | conflits, dépendances hors plugin et stratégie d'extraction | charge limitée à l'intégration de branches |
| [`AIMI_KMP_MIGRATION_STUDY.md`](AIMI_KMP_MIGRATION_STUDY.md) | synthèse initiale et scénarios | recommandations et coûts remplacés par le blueprint |

## Corrections architecturales retenues

1. **TFLite est conservé.** `modelUAM.tflite` est le modèle actif ; il est exécuté sur iOS via un
   runtime TFLite/LiteRT natif derrière un port KMP.
2. **Les algorithmes Kotlin ne sont pas portables “tels quels”.** Les stores, JSON, clocks,
   atomiques, lifecycle, données AAPS et schedulers doivent être adaptés.
3. **La frontière n'est pas la signature actuelle de `determine_basal`.** Elle comprend un snapshot
   complet, un état versionné et un manifest de modèles.
4. **Les fixtures JSONL actuelles sont analytiques.** Un second format doit capturer entrées,
   état avant/après et modèles pour réexécuter le moteur.
5. **La parité cible est décisionnelle et fonctionnelle AIMI.** La copie exacte de toutes les
   surfaces Android/AAPS n'est pas possible sur iOS.
6. **La configuration est immuable pendant un tick.** TPO et les réponses LLM sont intégrés à la
   frontière du tick suivant.
7. **Aucun callback ne mute un résultat déjà retourné.** L'Auditor externe devient un advice différé.
8. **RMSSD et SDNN restent deux métriques distinctes.** Elles ne partagent ni baseline ni seuils.
9. **Hormonitor conserve son schéma scientifique, pas les chemins de fichiers Android.**
10. **La compilation Native n'est qu'une gate technique.** Replay séquentiel et shadow précèdent
    toute activation de dosage iOS.

## Ordre de lecture recommandé

1. blueprint ;
2. annexe 8 pour comprendre la frontière et le replay ;
3. annexe 6 pour le protocole Tree → Harmonia → RBT → safety ;
4. annexe 5 pour les modèles et learners ;
5. annexe 7 pour l'intégration iOS ;
6. annexe 9 pour les fonctions produit adjacentes ;
7. annexes 1 à 4 pour les preuves historiques et métriques.

## Questions qui exigent une décision produit

- application hôte iOS et première paire pompe/CGM ;
- modèle UAM uniquement embarqué ou import utilisateur versionné ;
- correction des défauts Android connus avant gel de la baseline ;
- politique de persistence/reconstruction des mémoires après restart ;
- corpus privé et critères de shadow ;
- fonctionnalités v1 : core-only, learners, HealthKit, Hormonitor, Advisor/TPO ;
- politique de distribution et d'entitlements Apple ;
- seuil de parité interne et règle exacte après quantification pompe.

## Règle de mise à jour

Tout changement de décision doit modifier le blueprint et l'annexe propriétaire dans le même lot.
Un changement de comportement thérapeutique exige un ADR, un replay avant/après et une justification
distincte du refactor KMP.
