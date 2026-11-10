# AIMI — Checklist validation device (shadow C1 + intelligence snapshot)

**Objectif :** confirmer que l’architecture snapshot / kinetics / arbre est **active et cohérente** sur 24–48 h **avant** d’activer `key_aimi_prediction_authority_enabled` (C1 prod).

**Prérequis build :** branche avec Phases 0–3 + shadow C1 ; flags par défaut :

| Clé | Défaut attendu |
|-----|----------------|
| `key_aimi_intelligence_snapshot_export` | `true` |
| `key_aimi_intelligence_single_learn_path` | `true` |
| `key_aimi_dia_governor_enabled` | `true` |
| `key_aimi_intelligence_kinetics_profiler` | `true` |
| `key_aimi_prediction_authority_shadow` | `true` |
| `key_aimi_prediction_authority_enabled` | **`false`** |

---

## 1. Fichiers à analyser (noms exacts)

### Fichier principal — décisions loop (obligatoire)

| Fichier | Chemin device | Rôle |
|---------|---------------|------|
| **`AIMI_Decisions.jsonl`** | `Documents/AAPS/AIMI_Decisions.jsonl` | **Source de vérité** — 1 ligne JSON / tick loop |

> Sur Android : stockage externe app → `Android/data/info.nightscout.androidaps/files/../` ou export via **AIMI Profile Advisor** → zip contenant `AIMI_Decisions_Last24h.jsonl`.

**Champs clés à auditer dans chaque ligne :**

```
adjustments.intelligence_snapshot_v1          # snapshot unifié (si export ON)
adjustments.intelligence_snapshot_v1.predictions.shadow_delta_eventual
adjustments.intelligence_snapshot_v1.predictions.shadow_delta_pred_terminal
adjustments.intelligence_snapshot_v1.predictions.authority_applied   # doit être false en shadow
adjustments.intelligence_snapshot_v1.kinetics.effective_dia_h
adjustments.intelligence_snapshot_v1.kinetics.effective_peak_min
adjustments.intelligence_snapshot_v1.kinetics.prediction_uses_learned_kinetics
adjustments.intelligence_snapshot_v1.isf.pkpd_scale
adjustments.physiological_tree.insulin_kinetics_context   # Lot 2 greffé sur l’arbre
adjustments.pred_divergence                               # audit PKPD vs scenario (existant)
adjustments.harmonia_simulation.environment.effective_dia_h   # télémétrie Harmonia
```

### Fichier secondaire — PKPD terrain (recommandé)

| Fichier | Chemin device | Rôle |
|---------|---------------|------|
| **`oapsaimi_pkpd_records.csv`** | `Documents/AAPS/oapsaimi_pkpd_records.csv` | Learn gate, DIA/peak appris, pkpdScale par tick |

### Fichiers de référence / comparaison (optionnel)

| Fichier | Contexte |
|---------|----------|
| `AIMI_Support_Package_<timestamp>.zip` | Export support patient (diag + JSONL 24 h) |
| `AIMI_Decisions_Last24h.jsonl` | Sous-ensemble 24 h depuis l’advisor |
| `AIMI_HORMONITOR_loop_blackbox_v1.jsonl` | Heartbeat / timing seulement — **pas** les décisions dose |

### Fichier historique Benicio (baseline avant merge)

| Fichier | Usage |
|---------|-------|
| `AIMI files/AIMI_Decisions (3).jsonl` | Référence longue durée ; divergence médiane ~48 mg/dL **avant** C1 |

---

## 2. Récupération des fichiers

### Depuis le téléphone (adb)

```bash
adb pull /sdcard/Documents/AAPS/AIMI_Decisions.jsonl ./AIMI_Decisions_validation.jsonl
adb pull /sdcard/Documents/AAPS/oapsaimi_pkpd_records.csv ./oapsaimi_pkpd_records_validation.csv
```

> Ajuster le préfixe `/sdcard/` si le device mappe autrement ; vérifier avec `adb shell ls -la /sdcard/Documents/AAPS/`.

### Depuis l’app

- **AIMI Profile Advisor** → export zip → extraire `AIMI_Decisions_Last24h.jsonl`.

---

## 3. Logcat (complément live)

Filtrer pendant 2–3 h de loop :

```bash
adb logcat -c
adb logcat -s APS:* | grep -E "PRED_AUTH_SHADOW|PRED_AUTHORITY|PRED_AUTHORITY_C1|PKPD_OBS|Fused slow ISF|DIA_GOV|PK/PD prediction kinetics"
```

| Marqueur | Attendu en shadow |
|----------|-------------------|
| `PRED_AUTH_SHADOW: Δev=… ΔpredT=…` | Présent quand divergence > 0,5 mg/dL |
| `PRED_AUTHORITY_C1:` | **Absent** (prod OFF) |
| `PRED_AUTHORITY: src=…` | Résolution authority chaque tick |
| `PKPD_OBS onset=… stage=…` | Observer avec DIA effectif (pas profil 6 h seul) |
| `Fused slow ISF: … pkpdScale=` | Scale **tick courant** (pas figé N-1) |
| `PK/PD prediction kinetics: curves on learned DIA=` | Courbes sur kinetics learned si pref ON |

---

## 4. Analyse JSONL — scripts rapides

### 4.1 Présence snapshot (100 % ticks)

```bash
python3 - <<'PY'
import json, sys
path = "AIMI_Decisions_validation.jsonl"
total = has_snap = 0
for line in open(path, encoding="utf-8"):
    if not line.strip(): continue
    total += 1
    o = json.loads(line)
    adj = o.get("adjustments") or {}
    if "intelligence_snapshot_v1" in adj or adj.get("intelligence_snapshot"):
        has_snap += 1
print(f"ticks={total} with_intelligence_snapshot={has_snap} pct={100*has_snap/max(total,1):.1f}%")
PY
```

**Pass :** ≥ 95 % des ticks loop avec `intelligence_snapshot_v1`.

### 4.2 Shadow C1 — distribution des deltas

```bash
python3 - <<'PY'
import json, statistics
path = "AIMI_Decisions_validation.jsonl"
ev, pred = [], []
for line in open(path, encoding="utf-8"):
    o = json.loads(line)
    snap = (o.get("adjustments") or {}).get("intelligence_snapshot_v1") or {}
    p = snap.get("predictions") or {}
    if p.get("shadow_only") and "shadow_delta_eventual" in p:
        ev.append(abs(p["shadow_delta_eventual"]))
        pred.append(abs(p.get("shadow_delta_pred_terminal") or 0))
def med(a): return statistics.median(a) if a else float("nan")
print(f"shadow_ticks={len(ev)}")
print(f"|Δ eventual| median={med(ev):.1f} mg/dL  p90={sorted(ev)[int(0.9*len(ev))] if ev else 0:.1f}")
print(f"|Δ predT|    median={med(pred):.1f} mg/dL")
PY
```

**Pass (cible Phase 2) :** médiane \|Δ eventual\| **< 15 mg/dL** (baseline Benicio ~48 mg/dL).

**Go prudent :** médiane < 25 mg/dL sur 24 h **hors** ticks `learning_gate_pass=false` massifs.

### 4.3 Kinetics effective vs profil

```bash
python3 - <<'PY'
import json, statistics
path = "AIMI_Decisions_validation.jsonl"
dia_eff, dia_prof, learned = [], [], 0
for line in open(path, encoding="utf-8"):
    o = json.loads(line)
    k = ((o.get("adjustments") or {}).get("intelligence_snapshot_v1") or {}).get("kinetics") or {}
    if "effective_dia_h" in k:
        dia_eff.append(k["effective_dia_h"])
        dia_prof.append(k.get("profile_dia_h", k["effective_dia_h"]))
    if k.get("prediction_uses_learned_kinetics"):
        learned += 1
n = len(dia_eff)
print(f"ticks_with_kinetics={n}")
if n:
    print(f"effective_dia median={statistics.median(dia_eff):.2f}h  profile_dia median={statistics.median(dia_prof):.2f}h")
    print(f"prediction_uses_learned_kinetics={100*learned/n:.1f}%")
PY
```

**Pass :** `effective_dia_h` ≠ `profile_dia_h` sur une fraction significative quand PKPD appris actif ; `prediction_uses_learned_kinetics` > 50 % si pref prediction kinetics ON.

### 4.4 Arbre Lot 2 — `insulin_kinetics_context`

```bash
python3 - <<'PY'
import json
path = "AIMI_Decisions_validation.jsonl"
n = with_ctx = 0
for line in open(path, encoding="utf-8"):
    n += 1
    tree = (json.loads(line).get("adjustments") or {}).get("physiological_tree") or {}
    if "insulin_kinetics_context" in tree:
        with_ctx += 1
print(f"ticks={n} with_insulin_kinetics_context={with_ctx} ({100*with_ctx/max(n,1):.1f}%)")
PY
```

**Pass :** présent sur les mêmes ticks que `intelligence_snapshot_v1` (arbre physio ON).

### 4.5 Authority jamais appliquée en shadow

```bash
python3 - <<'PY'
import json
path = "AIMI_Decisions_validation.jsonl"
applied = 0
for line in open(path, encoding="utf-8"):
    p = (((json.loads(line).get("adjustments") or {}).get("intelligence_snapshot_v1") or {}).get("predictions") or {})
    if p.get("authority_applied") is True:
        applied += 1
print(f"authority_applied_true={applied}  # doit être 0 avant activation prod")
PY
```

**Pass :** `authority_applied_true=0`.

---

## 5. Grille de validation par couche

| Couche | Preuve dans `AIMI_Decisions.jsonl` | Critère |
|--------|-------------------------------------|---------|
| **Snapshot** | `intelligence_snapshot_v1` | ≥ 95 % ticks |
| **Causal / learn** | `kinetics.learning.gate_pass` | Documenter % pass (Benicio souvent 0 %) |
| **Effective kinetics** | `effective_dia_h`, `effective_peak_min` | Cohérent CSV PKPD + TAP-G/TAP-D logs |
| **ISF autorité** | `isf.pkpd_scale`, `isf.fusion_factor` | Varie avec tick ; aligné logcat DynISF |
| **Arbre Lot 2** | `physiological_tree.insulin_kinetics_context` | Présent si arbre exporté |
| **Shadow C1** | `predictions.shadow_delta_*`, `shadow_only=true` | Médiane \|Δev\| < cible |
| **Prod C1** | `authority_applied=true` | **0** avant bascule |
| **Dose inchangée** | `outcome.dosage_u` vs build sans flag | Identique en shadow |

---

## 6. Critères GO / NO-GO pour activer C1 prod

### GO (tous requis)

1. `AIMI_Decisions.jsonl` ≥ **24 h** continues, ≥ 200 ticks.
2. `intelligence_snapshot_v1` ≥ **95 %** ticks.
3. `authority_applied_true` = **0**.
4. Médiane \|shadow_delta_eventual\| **< 25 mg/dL** (cible **< 15**).
5. Aucun tick avec `shadow_delta_eventual` > **80 mg/dL** sauf ticks COB > 15 g ou `learning_gate_pass=false` documentés.
6. Comportement clinique stable (pas de plainte hypo/hyper nouvelle vs période précédente).
7. Compilation release OK sur le build testé.

### NO-GO (un seul suffit)

- Pics répétés \|Δev\| > 50 mg/dL en euglycémie stable (COB ≈ 0, BG 80–180).
- `authority_applied` déjà true sans flag prod (bug).
- Snapshot absent > 10 % ticks.
- Divergence shadow **augmente** vs baseline `AIMI_Decisions (3).jsonl` sur fenêtre comparable.

---

## 7. Activation C1 prod (après GO)

1. Préférences AIMI → activer **`key_aimi_prediction_authority_enabled`**.
2. Laisser **`key_aimi_prediction_authority_shadow`** à `true` (double trace 48 h).
3. Re-analyser **`AIMI_Decisions.jsonl`** :
   - `predictions.authority_applied` = true sur ticks éligibles
   - logcat `PRED_AUTHORITY_C1:` présent
   - comparer `outcome.dosage_u` avant/après sur mêmes conditions (replay ou fenêtre parallèle)

---

## 8. Fiche rapport (à remplir)

| Champ | Valeur |
|-------|--------|
| Date collecte | |
| Fichier analysé | `AIMI_Decisions.jsonl` / `AIMI_Decisions_Last24h.jsonl` |
| Chemin local | |
| Build / version | |
| Durée fenêtre | h |
| Nombre ticks | |
| % snapshot | |
| Médiane \|Δev\| shadow | mg/dL |
| Médiane \|ΔpredT\| shadow | mg/dL |
| % learning_gate_pass | |
| Décision | GO / NO-GO C1 prod |

---

## 9. Documents liés

- [AIMI_INTELLIGENCE_SNAPSHOT_ROADMAP.md](AIMI_INTELLIGENCE_SNAPSHOT_ROADMAP.md)
- [PKPD_KINETICS_ARCHITECTURE.md](PKPD_KINETICS_ARCHITECTURE.md) — §8 validation Benicio
- [AIMI_PREDICTION_DIVERGENCE.md](AIMI_PREDICTION_DIVERGENCE.md)
