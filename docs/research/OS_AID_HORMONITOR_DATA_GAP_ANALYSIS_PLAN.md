# OS-AID HORMONITOR - Data Gap Analysis & Exposure Plan

## 1) Contexte et objectif

Le moteur AIMI dispose deja de briques avancees pour:
- l'adaptation PKPD/TAP-G (peak prior/learned/effective, trajectoire, physio, site),
- le contexte cycle feminin (WCycle),
- des modulations liees a des contextes inflammatoires/endometriose.

L'objectif de ce plan est de definir:
- quelles donnees doivent etre exposees pour l'etude HORMONITOR,
- ce qui existe deja vs ce qui manque pour une collecte robuste,
- une strategie claire d'exposition pour produire un dataset clinique exploitable.

---

## 2) Scope de donnees cible pour l'etude

### 2.1 Core obligatoire (indispensable)

- `timestamp_utc`
- `subject_id_pseudo`
- `cgm_glucose_mgdl`
- `cgm_delta_5m`
- `iob_u`
- `cob_g`
- `bolus_u`
- `basal_rate_u_h`
- `temp_basal_rate_u_h`
- `temp_basal_duration_min`
- `target_bg_mgdl`
- `isf_used_mgdl_u`
- `icr_used_g_u`
- `loop_decision_type` (none, smb, tbr_up, tbr_down, suspend)
- `loop_reason_tags`

Raison:
- c'est le socle causal minimal pour relier decision algorithme -> resultat glycemique.

### 2.2 Hormonal/cycle (obligatoire pour HORMONITOR)

- `wcycle_enabled`
- `cycle_tracking_mode`
- `cycle_day`
- `cycle_phase`
- `contraceptive_type`
- `wcycle_basal_multiplier_applied`
- `wcycle_smb_multiplier_applied`
- `wcycle_ic_multiplier_applied`
- `wcycle_applied_flag`
- `wcycle_reason`

Raison:
- permet d'isoler l'impact du contexte hormonal feminin sur les decisions.

### 2.3 PKPD/TAP-G (obligatoire pour validation des decisions AIMI)

- `peak_prior_min`
- `peak_learned_min`
- `peak_effective_min`
- `peak_delta_traj_min`
- `peak_delta_physio_min`
- `peak_delta_site_min`
- `peak_dominant_branch`
- `pkpd_scale`
- `pkpd_window_since_last_dose_min`
- `pkpd_tail_fraction`

Raison:
- c'est la preuve mecanistique de "pourquoi" AIMI ajuste sa dynamique insulinique.

### 2.4 Physiologie (fortement recommande)

- `steps_5min`
- `steps_30min`
- `hr_avg_5min`
- `hr_avg_15min`
- `sleep_quality_score` (si disponible)
- `hrv_proxy` (si disponible)
- `physio_state`
- `physio_confidence`
- `physio_data_age_min`
- `physio_data_source`
- `physio_pipeline_outcome`

Raison:
- indispensable pour separer effet hormonal vs effort/stress/sommeil.
- evite les faux liens cliniques.

### 2.5 Qualite des donnees (obligatoire)

- `data_quality_score_0_1`
- `missingness_flags`
- `cgm_quality_flag`
- `stale_data_flag`
- `veto_flags` (high_cob_veto, exercise_veto, etc.)
- `record_valid_for_primary_analysis`

Raison:
- la validite clinique depend plus de la qualite des donnees que du volume.

---

## 3) Gap analysis: disponible vs partiel vs manquant

## 3.1 Deja disponible dans AIMI

- Variables loop/insuline/CGM standards.
- WCycle (phase, jour, mode, contraception, multiplicateurs, reason).
- TAP-G/PKPD state (prior, learned, effective, dominant branch, log line).
- Donnees locales physio context store.

## 3.2 Partiellement disponible (present mais pas expose en flux etude)

- Contributions detaillees TAP-G par branche sous forme dataset stable.
- Flags de confiance/qualite pour la physio et certains signaux derivees.
- Harmonisation des noms/units pour un export unique.

## 3.3 Manquant pour un dataset etude propre

- Contrat de donnees HORMONITOR versionne (schema officiel).
- Canal d'export etude structure (pas uniquement logs/CSV locaux heterogenes).
- Pseudonymisation et gouvernance d'extraction formalisees.
- Table outcomes journaliers consolidee (TIR/hypo/CV/charge intervention).

---

## 4) Plan detaille de mise a disposition (etapes manquantes)

## Etape 1 - Data contract (Semaine 1)

Livrables:
- dictionnaire de variables (nom, type, unite, frequence, nullable),
- regles d'exclusion/qualite,
- version de schema (`schema_version`).

Definition attendue:
- une structure stable pour toute la duree de l'etude,
- interdiction de changer un champ sans bump de version.

## Etape 2 - Instrumentation AIMI -> event stream (Semaines 1-2)

Livrables:
- emission a chaque tick loop de l'enregistrement etude,
- mapping explicite pour WCycle, TAP-G, physio,
- flags de disponibilite.

Definition attendue:
- pas de "0" quand absent: utiliser `null` + flag d'absence.

## Etape 3 - Outcome builder journalier (Semaine 2)

Livrables:
- generation table quotidienne:
  - `tir_70_180`,
  - `time_below_70`,
  - `mean_glucose`,
  - `cv_percent`,
  - `total_bolus_u`,
  - `total_basal_u`.

Definition attendue:
- deux granularites:
  - `event_stream_5min`,
  - `daily_outcomes`.

## Etape 4 - Export/Uploader etude (Semaines 2-3)

Livrables:
- pipeline d'export clair (JSONL/CSV normalise),
- compression + signature + metadonnees (`dataset_id`, `schema_version`, `generated_at`),
- separation stricte entre export produit standard et export recherche.

Definition attendue:
- auditabilite de chaque extraction.

## Etape 5 - Validation qualite (Semaine 3)

Livrables:
- tests de completude (% champs non nuls),
- tests de coherence unites/plages,
- tests temporels (ordre et latence).

Definition attendue:
- checklist "go/no-go" avant inclusion dans l'etude.

---

## 5) Propositions concretes pour exposer clairement les donnees

## Option A - Fichier local etude (MVP recommande)

Format:
- `JSONL` par tick dans `Documents/AAPS/study_hormonitor_events.jsonl`
- `CSV` quotidien pour outcomes dans `Documents/AAPS/study_hormonitor_daily.csv`

Avantages:
- rapide a deployer,
- simple pour audit local,
- faible impact infra.

Limites:
- gouvernance multi-site moins elegante,
- necessite process de collecte terrain.

## Option B - Upload dedie "study endpoint"

Format:
- payload batches signes, schema versionne.

Avantages:
- meilleure supervision,
- qualite centralisee,
- plus simple pour analyses multicentriques.

Limites:
- necessite infra backend et consent management plus mature.

## Option C - Extension OpenHumans/NS (si contraint par existant)

Format:
- enrichissement des structures deja synchronisees.

Avantages:
- reutilise l'existant.

Limites:
- risque de schema heterogene,
- moins lisible pour analyses cliniques strictes.

Recommendation:
- commencer par **Option A (MVP)** puis migrer vers **Option B** pour la phase de validation multicentrique.

---

## 6) Proposition de schema minimal (exemple)

```json
{
  "schema_version": "hmonitor.v1",
  "timestamp_utc": "2026-04-21T12:35:00Z",
  "subject_id_pseudo": "sub_9f2a...",
  "cgm_glucose_mgdl": 164,
  "cgm_delta_5m": 3.0,
  "iob_u": 1.8,
  "cob_g": 12.0,
  "cycle_phase": "LUTEAL",
  "cycle_day": 22,
  "wcycle_basal_multiplier_applied": 1.18,
  "peak_prior_min": 75.0,
  "peak_learned_min": 79.0,
  "peak_effective_min": 80.0,
  "peak_dominant_branch": "TRAJECTORY",
  "steps_5min": 142,
  "hr_avg_5min": 88,
  "physio_confidence": 0.72,
  "physio_pipeline_outcome": "READY",
  "data_quality_score_0_1": 0.91,
  "record_valid_for_primary_analysis": true
}
```

---

## 7) Criteres de succes pour la phase etude

- >= 90% de completude sur les champs core obligatoires.
- >= 80% de completude sur le bloc hormonal.
- >= 70% de completude sur le bloc physio (si capteurs presents).
- 0 rupture de schema non versionnee.
- Reproductibilite d'une extraction complete sur periode test.

---

## 8) Priorisation pratique (MVP -> V2)

MVP:
1. Core + hormonal + TAP-G + qualite.
2. Export event_stream + daily_outcomes en local.
3. Verification qualite sur 2-4 semaines.

V2:
1. Enrichissement physio complet (sommeil/HRV selon disponibilite).
2. Endpoint central etude + monitoring qualite en continu.
3. Playbook d'analyse clinique standardise.

---

## 9) Comment la donnee physio impacte les decisions AIMI (plan decisionnel)

Objectif:
- transformer chaque donnee physio en action insulinique explicite, tracable et verifiable.

Principe central:
- la physio agit comme un modulateur de securite (agressivite de SMB/basal/ISF),
- jamais comme un contournement des garde-fous du loop.

### 9.1 Pipeline "donnee -> etat -> action"

Etape A - Ingestion qualifiee:
- collecte sleep/HR/HRV/steps + metadonnees source/age/qualite,
- construction d'un score de fiabilite (0-1),
- neutralisation automatique si donnees insuffisantes ou incoherentes.

Etape B - Derivation de contexte:
- extraction de features (duree sommeil, efficience, fragmentation, HRV RMSSD, RHR deviation),
- comparaison a baseline personnelle (percentiles et z-scores),
- classification d'etat:
  - `OPTIMAL`,
  - `RECOVERY_NEEDED`,
  - `STRESS_DETECTED`,
  - `INFECTION_RISK`,
  - `UNKNOWN`.

Etape C - Traduction therapeutique:
- conversion d'etat en multiplicateurs bornes:
  - `isf_factor`,
  - `basal_factor`,
  - `smb_factor`,
  - `reactivity_factor`.
- application obligatoire des caps de securite (hard caps) avant sortie.

Etape D - Gating clinique:
- veto si hypo recente / BG bas / confiance faible / stale data,
- dans ces cas: retour neutre (`1.0`) et journalisation du motif.

### 9.2 Matrice d'impact decisionnel proposee (v1 clinique conservative)

- `OPTIMAL`:
  - action: neutre,
  - impact attendu: aucune modulation.

- `RECOVERY_NEEDED` (mauvaise nuit / dette sommeil / HRV basse legere):
  - action: reduction moderee de l'agressivite,
  - impact attendu: diminution risque hypo nocturne/matinale.

- `STRESS_DETECTED` (RHR haut + HRV basse):
  - action: reduction plus marquee SMB, ISF plus prudent,
  - impact attendu: eviter sur-correction en phase de stress physiologique.

- `INFECTION_RISK` (anomalies convergentes):
  - action: mode protecteur fort mais borne,
  - impact attendu: securite prioritaire sur agressivite glycemique.

- `UNKNOWN` ou fiabilite basse:
  - action: neutral fallback,
  - impact attendu: pas d'effet iatrogene lie a un signal incertain.

### 9.3 Champs a exposer pour expliquer chaque decision

Pour chaque tick loop, exporter:
- `physio_state`,
- `physio_confidence`,
- `physio_data_quality`,
- `physio_signal_age_min`,
- `physio_flags` (poor_sleep, hrv_depressed, rhr_elevated, activity_reduced),
- `physio_recommendation` (reduce_basal, reduce_smb, increase_isf),
- `physio_applied_multipliers` (isf, basal, smb, reactivity),
- `physio_veto_reason` (si neutralisation),
- `final_loop_decision_type`.

Ces champs sont essentiels pour repondre en clinique a:
- "quelle donnee a modifie quoi, et pourquoi ?"

### 9.4 Regles de securite non negociables

- pas de modulation si `physio_confidence` < seuil defini,
- pas de modulation en cas d'hypo recente ou BG sous seuil clinique,
- jamais de depassement des caps globaux du loop,
- fallback systematique a `1.0` en cas d'erreur pipeline.

### 9.5 Plan de validation d'efficacite (scientifique)

Design recommande:
- comparaison intra-sujet ON vs OFF physio, fenetres equivalentes.

Endpoints primaires:
- `TIR_nuit_70_180`,
- `TBR_<70_nuit`,
- frequence hypo matinale,
- variabilite glycemique nocturne.

Endpoints secondaires:
- charge d'intervention (SMB/TBR),
- temps >180 nocturne,
- stabilite post-reveil.

Critere de valeur clinique:
- benefice net si baisse hypo nocturne sans degradation majeure du TIR global.

### 9.6 Decision framework produit (go/no-go)

GO (deploiement elargi):
- amelioration reproductible des endpoints primaires,
- absence de signal de risque sur hypoglycemies severes,
- qualite de donnees stable.

NO-GO / rollback:
- gain non reproductible,
- hausse d'evenements indeses,
- forte sensibilite aux donnees manquantes ou bruit capteur.

---

## 10) Task list implementation (dev checklist)

### Phase 1 - Decision trace fiable (immediat)

1. Definir un score explicite `sleep_quality_score` (0-1) dans les features physio.
2. Integrer ce score dans les regles de detection `poorSleep`.
3. Exposer ce score dans les logs de decision loop.
4. Verifier fallback neutre si qualite absente/faible confiance.

Done criteria:
- la decision n'utilise plus uniquement la duree de sommeil,
- chaque modulation est explicable par des champs exportables.

### Phase 2 - Durcissement clinique

1. Ajouter un score de fiabilite source (device/app/sync age).
2. Ajouter un veto explicite pour donnees stale/incoherentes.
3. Ajouter export decisionnel standard:
   - state, confidence, quality, multipliers, veto_reason.
4. Ajouter monitor de derive (drift) inter-device pour HR/HRV.

Done criteria:
- une decision physio est toujours justifiee ou neutralisee explicitement.

### Phase 3 - Validation HORMONITOR

1. Protocole ON/OFF intra-sujet 2-4 semaines.
2. Table outcomes nuit + matin.
3. Rapport d'efficacite:
   - hypo nuit,
   - TIR nuit,
   - variabilite,
   - charge SMB/TBR.
4. Recommandation GO/NO-GO documentee.

Done criteria:
- preuve d'utilite clinique ou rollback objectivable.

## 11) Execution plan 2 sprints (MVP export etude)

### Sprint 1 - Export decisionnel structure (MVP)

Objectif:
- produire un flux local etude standardise, non bloquant pour la boucle.

Tickets:
1. **HM-EXP-01 - Contrat v1 event stream**
   - definir `schema_version=1.0.0` pour un enregistrement loop:
     - `dataset_id`, `generated_at`, `app_version`, `schema_version`,
     - `event_id`, `timestamp`,
     - `physio_state`, `physio_confidence`, `physio_data_quality`,
     - `isf_factor`, `basal_factor`, `smb_factor`, `reactivity_factor`,
     - `physio_veto_reason`, `final_loop_decision_type`, `source`.
2. **HM-EXP-02 - Writer local JSONL etude**
   - fichier local dedie (separe des logs produit),
   - append tolerant aux erreurs avec fallback app-scoped storage.
3. **HM-EXP-03 - Emission depuis la decision finale**
   - emission d'un enregistrement sur le chemin final loop,
   - conserver les signatures et la logique de decision existantes.
4. **HM-EXP-04 - Qualite/fiabilite minimale**
   - ajouter flags de qualite source: age donnees, stale, confidence.
5. **HM-EXP-05 - Validation technique**
   - verifier absence de `loop=pending` final sur chemins principaux,
   - verifier zero crash/echec silencieux d'ecriture.

Done criteria sprint 1:
- fichier JSONL etude present et rempli,
- schema v1 stable et documente,
- aucune regression sur SMB/TBR/meal modes,
- aucun impact perceptible sur la latence loop.

### Sprint 2 - Outcomes et readiness clinique

Objectif:
- rendre le dataset directement exploitable pour l'analyse HORMONITOR.

Tickets:
1. **HM-OUT-01 - Daily outcomes consolides**
   - produire table journaliere: TIR/TBR/TAR, hypo nocturne, variabilite, charge intervention.
2. **HM-OUT-02 - Liaison exposition -> outcome**
   - relier les expositions physio/hormonales de la nuit aux outcomes du lendemain.
3. **HM-OUT-03 - Pseudonymisation et gouvernance**
   - identifiant patient pseudonymise stable,
   - separation stricte export etude vs logs produit.
4. **HM-OUT-04 - QA dataset**
   - checks de completude, doublons, incoherences temporelles.
5. **HM-OUT-05 - Package de revue clinique**
   - echantillon anonymise + dictionnaire de donnees + exemples de cas.

Done criteria sprint 2:
- dataset nightly + daily outcomes exploitable sans retraitement manuel majeur,
- filtres qualite disponibles pour analyses cliniques,
- package pret pour revue investigateur.

### Gate go/no-go pour demarrage collecte

GO collecte:
- champs obligatoires presents >= 98% des ticks eligibles,
- `final_loop_decision_type` renseigne sur toutes les sorties finales couvertes,
- pipeline ecriture stable (pas d'echec silencieux).

NO-GO collecte:
- schema instable entre builds,
- trous frequents sur champs decisionnels critiques,
- ambiguite sur consentement/pseudonymisation.

