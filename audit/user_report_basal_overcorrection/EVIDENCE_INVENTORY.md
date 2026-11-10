# Annexe A — Inventaire des preuves et qualité des sources

Audit read-only. Aucun fichier produit modifié, aucun commit, aucun changement de branche.

## A.1 Fichiers annoncés dans le rapport vs présents dans le workspace

Recherche exhaustive sur `/Users/mtr` (hors `~/Library`) des noms exacts annoncés au §7 du rapport.

| Fichier annoncé (§7 du rapport) | Présent ? | Constat |
|---|---|---|
| `data/AIMI_Decisions_2026-08-01_0624__2026-08-02_1220.jsonl` | **ABSENT** | aucun fichier de ce nom ni équivalent couvrant la fenêtre |
| `data/ticks_summary.csv` | **ABSENT** | — |
| `data/bg_series.csv` | **ABSENT** | — |
| `data/basal_delivery_segments.csv` | **ABSENT** | — |
| `data/boluses.csv` | **ABSENT** | — |
| `data/nightscout_treatments_raw.json` | **ABSENT** | — |
| `data/learner_states_snapshot.json` | **ABSENT** | — |
| `episode_E1_E2_lunch.png`, `episode_E3_E4_dinner.png`, `episode_E5_E6_breakfast.png` | **ABSENT** | — |
| `overview_30h.png` | **PRÉSENT** (joint à la conversation) | image seule, sans données sous-jacentes |

**Conséquence directe :** aucune des métriques du rapport ne peut être recalculée à partir des
données primaires annoncées. Les agents 2 (reproduction quantitative), 5 (valeurs du tick 16:21)
et 9 (causalité par épisode) ne peuvent pas être exécutés sur les données de la fenêtre.

## A.2 Sources de substitution effectivement disponibles

| Source | Période | Primaire / reconstruite | Complétude | Fiabilité | Limites |
|---|---|---|---|---|---|
| `overview_30h.png` (joint) | 1 août 06:00 → 2 août 12:30 | **dérivée** (graphique produit par l'auteur du rapport) | visuelle | moyenne | non re-dérivable ; résolution de lecture ~±3 mg/dL, ~±0,3 U/h ; ne distingue pas TBR suggéré / délivré |
| `AIMI_Support_Package_1785536421103/` (`AIMI_Decisions_Last24h.jsonl`, 245 lignes, 8,6 Mo) | **31 juil. 00:27 → 1 août 00:17** | **primaire** (export appareil) | 237 ticks + 8 enregistrements auditeur ; 86 % des ~285 boucles attendues | élevée | **s'arrête ~6 h AVANT le début de la fenêtre** ; **patient différent** (voir A.3) |
| `AIMI_Support_Package_.../Diagnostic_Report.txt` | 1 août 00:20 | primaire | — | élevée | confirme `App Version: 4.0.0.0-dev.AIMI.310726 (1500)` |
| `AIMI JSON/basal_adaptive_records.csv` (30 465 lignes) | 16 mars → 17 juil. 2026 | **primaire** (log appareil) | continue | élevée pour les colonnes vérifiées | hors fenêtre ; provenance patient inconnue ; build non vérifiable |
| Code source `bf8973259e` + historique Git | — | primaire | complète | élevée | comportement statique ; ne prouve pas l'exécution d'un tick donné |

### Sémantique vérifiée dans le code des colonnes de `basal_adaptive_records.csv`

Chaîne : `applyBasalNeuralLearningAndTraining` → `BasalNeuralLearner.updateLearning` → `logRecord`.

- colonne `bg` = `bg` (BG courant au moment de la décision)
- colonne `eventualBg` = `rT.eventualBG` — **prédiction**, non une glycémie observée
  (`DetermineBasalAIMI2.kt:16674-16678`)
- colonne `basal` = `finalResult.rate` (`DetermineBasalAIMI2.kt:8172-8174`) — **TBR finale
  demandée par le moteur**, c'est-à-dire *suggérée*, **pas** une délivrance confirmée par la pompe.
  Le paramètre est nommé `basalDelivered` mais reçoit `rT.rate` : le nom est trompeur.

Cette distinction est appliquée dans tout l'audit : les statistiques ci-dessous portent sur des
**TBR finales suggérées**, jamais sur une délivrance vérifiée.

## A.3 Le paquet du 31 juillet n'est PAS le même patient

Test décisif sur `baseline_state` (237 ticks) :

| Champ | Valeurs observées dans le paquet | Rapport utilisateur |
|---|---|---|
| `profile_basal_uph` | 0,45 / 0,50 / 0,51 / 0,60 (non plat) | **0,75 U/h plat** |
| `profile_isf_mgdl` | 10,4 → 19,4 (variable) | **74 mg/dL/U** |

→ Ce paquet est un **jeu de contrôle même build / patient différent**. Il est valide pour tester des
propriétés du **moteur**, invalide pour valider les chiffres patient-spécifiques du rapport
(fused ISF vs profil, `Basal×1.48`, `React×0.50`, charge hypo 9,47 %).

> **Correction apportée le 2026-08-02 après réception d'un second paquet
> (`AIMI_Support_Package_1785672196581`, 1 août 14:07 → 2 août 14:00).** Ce paquet du 31 juillet
> n'est pas celui d'un tiers : il provient du **même appareil que le mainteneur** (profil moteur
> identique 0,45/0,50/0,51/0,60 U/h). Il reste bien un patient **différent de l'auteur du rapport**
> (0,75 U/h plat), ce qui était le point important. Second constat de ce recoupement :
> `baseline_state.profile_isf_mgdl` vaut 4,3 → 80,0 alors que le profil statique local est
> `isf = 70 (00:00) / 30 (11:00)` — **ce champ de télémétrie n'est donc PAS l'ISF statique du
> profil**, ce qui renforce le §4.7 du VERDICT : la comparaison « fused ISF vs profil 74 » du
> rapport est mal posée à la source.

## A.4 Vérification de version — établie

| Élément | Revendiqué | Vérifié |
|---|---|---|
| Build `4.0.0.0-dev.AIMI.310726` | oui | **CONFIRMÉ** : `Versions.kt` à `bf8973259e` contient exactement `appVersion = "4.0.0.0-dev.AIMI.310726"` ; et `Diagnostic_Report.txt` du 1 août 00:20 affiche `App Version: 4.0.0.0-dev.AIMI.310726 (1500)` sur un appareil réel |
| Branche `feature/dexcom-oneplus-native` | oui | CONFIRMÉ (branche courante) |
| Commit `bf89732` | « HEAD » | **PARTIELLEMENT** : le commit existe (`bf8973259e`, 1 août 08:32:20 +0200) mais **n'est plus HEAD** ; HEAD = `512f00cf33`. `appVersion` a été porté à `020726` après. |

**Écart de version borné et mesuré :** `git diff bf8973259e..HEAD` = 10 fichiers. Pour le moteur
de décision, `DetermineBasalAIMI2.kt` ne diffère que de **5 insertions / 2 suppressions, toutes
dans un commentaire `consoleLog`** (c58edd0f92). Les autres changements portent sur Dexcom ONE+,
`NotificationId`, `ComposeMainActivity` et `Versions.kt`.

→ **L'analyse du code de l'arbre de travail est valide pour le canal basal du build audité.**
C'est une limitation *levée*, pas subie.

## A.5 Trous de télémétrie — statut

| Affirmation du rapport (§7) | Statut |
|---|---|
| ticks < 90 mg/dL absents du JSONL | **partiellement infirmé** — le mécanisme de censure existe mais est *sélectif* (voir §12 du VERDICT) ; dans le jeu de contrôle même build, 34 ticks < 90 et un minimum à **71,5 mg/dL** sont bien exportés |
| tick 19:10 (mode repas) absent, trou ~35 min | **confirmé structurellement** — les 30 premières minutes de tout mode repas manuel retournent avant l'export |
| `Auditor: STALE (161m)` | observation correcte ; l'inférence « une protection était désactivée » est **infirmée** |
| arrêt sync Nightscout à 22:00 | **non vérifiable** — aucun export Nightscout fourni |

## A.6 Statut des 10 agents demandés

| Agent | Exécutable ? |
|---|---|
| 1 Inventaire / qualité des preuves | **oui** (cette annexe) |
| 2 Reproduction quantitative | **NON sur les données de la fenêtre** — remplacé par un contrôle de cohérence interne des chiffres du rapport + jeux de substitution |
| 3 Traçage du canal basal | **oui** (code) |
| 4 Comparaison protections SMB / basal | **oui** (code) |
| 5 Tick du 16:21 | **partiellement** — valeurs non vérifiables ; *productibilité* et arbre de calcul vérifiés sur le code + analogues réels |
| 6 ISF / dynISF / TDD | **oui côté code** ; observations patient non vérifiables |
| 7 BasalLearner / Unified Reactivity | **oui côté code** ; états annoncés non vérifiables |
| 8 Stack-Aware Guard B | **oui** (code + Git) |
| 9 Causalité E1–E6 | **NON** — aucune donnée d'insuline, de CGM ni de traitement pour la fenêtre |
| 10 Contre-audit | **oui** |
