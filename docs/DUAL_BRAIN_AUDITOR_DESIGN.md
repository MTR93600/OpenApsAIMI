# 🧠🧠 DUAL-BRAIN AUDITOR - ULTRA-PREMIUM ARCHITECTURE

## Date: 2025-12-31
## Status: 🔄 EN COURS D'IMPLÉMENTATION

---

## 🎯 VISION

**Système de contrôle en 2 niveaux pour AIMI** :
1. **Local Sentinel** (offline, gratuit, toujours actif) - Premier filtre
2. **External Auditor** (API optionnelle) - Second avis expert sur cas complexes

**Avantages** :
- ✅ **Robuste** : Sentinel local fonctionne même offline/sans API
- ✅ **Économique** : API appelée uniquement si Sentinel dit "HIGH_VALUE" 
- ✅ **Pertinent** : L'API ne voit que les cas vraiment complexes
- ✅ **Transparent** : Logs RT ultra-détaillés, traçabilité complète
- ✅ **Safe** : Soft influence only, jamais de blocage SMB/basal, respect LGS

---

## 🏗️ ARCHITECTURE

```
┌─────────────────────────────────────────────────────────────┐
│  AIMI DECISION (Modes, Autodrive, ML, PKPD, etc.)         │
└──────────────────┬──────────────────────────────────────────┘
                   │
                   ▼
      ┌────────────────────────────┐
      │  LOCAL SENTINEL (Offline)  │ ← TOUJOURS ACTIF
      │  ───────────────────────   │
      │  • Détecte drift           │
      │  • Détecte stacking        │
      │  • Détecte contradictions  │
      │  • Score 0-100             │
      │  • Tier: NONE/LOW/MED/HIGH │
      └────────────┬───────────────┘
                   │
                   ├─ Tier NONE/LOW ────┐
                   │                    │
                   ├─ Tier MEDIUM ──────┤ → Apply Sentinel Advice
                   │                    │   (soft, local, gratuit)
                   │                    │
                   └─ Tier HIGH ────────┼→ Check External Auditor
                                        │   ├─ Disabled ─────┐
                                        │   ├─ No API Key ──┤
                                        │   ├─ Cooldown ────┤
                                        │   │               │
                                        │   └─ ELIGIBLE ────▼
                                        │       ┌──────────────────────────┐
                                        │       │ EXTERNAL AUDITOR (API)   │
                                        │       │ ──────────────────────   │
                                        │       │ • Prompt structuré       │
                                        │       │ • Analyse profonde       │
                                        │       │ • Retour JSON strict     │
                                        │       │ • Timeout 30s max        │
                                        │       └────────────┬─────────────┘
                                        │                    │
                                        ▼                    ▼
                              ┌──────────────────────────────────┐
                              │  DECISION COMBINER               │
                              │  ─────────────────               │
                              │  Sentinel + External (si dispo)  │
                              │  → Most conservative wins        │
                              └───────────┬──────────────────────┘
                                          │
                                          ▼
                              ┌──────────────────────────────────┐
                              │  APPLY GUARD (Point Unique)      │
                              │  ────────────────────────         │
                              │  • finalizeAndCapSMB()           │
                              │  • SMB factor 0.0-1.0            │
                              │  • Extra interval 0-20min        │
                              │  • Prefer basal flag             │
                              │  • NEVER increase dose           │
                              │  • NEVER bypass LGS              │
                              └───────────┬──────────────────────┘
                                          │
                                          ▼
                              ┌──────────────────────────────────┐
                              │  FINAL DECISION                  │
                              │  • SMB (U)                       │
                              │  • TBR (U/h, min)                │
                              │  • Interval (min)                │
                              └──────────────────────────────────┘
```

---

## 📊 LOCAL SENTINEL (Tier 1)

### Rôle
**Premier filtre offline, gratuit, toujours actif**

Détecte :
1. **Drift persistant** : BG > target+30 depuis >20min, delta lent +0.5..+3
2. **Stacking risk** : IOB élevé, PKPD PEAK/RISING, SMB chain (≥3 en 30min)
3. **Variabilité** : Oscillations, sign flips dans historique BG
4. **Contradictions** : PKPD PRE_ONSET + gros SMB proposé, Autodrive stuck
5. **Dégradation** : Prediction missing, noise élevé, pump unreachable

### Calcul Score (0-100)

| Signaux | Points | Condition |
|---------|--------|-----------|
| **Drift persistant** | +30 | BG > target+30, delta +0.5..+3, age>20min |
| **Plateau haut** | +20 | BG>140, peu d'action, delta>0.5, age>30min |
| **High variability** | +25 | std(BG 30min) > 30 |
| **Oscillations** | +20 | ≥2 sign flips dans deltas |
| **Stacking IOB/PKPD** | +35 | IOB>2.0 OU PKPD PEAK/RISING + activity>0.4 |
| **SMB chain** | +30 | ≥3 SMB en 30min OU total 60min > 3.0U |
| **Recent bolus stacking** | +15 | lastBolus<15min + delta>0.5 + SMBprop>0.5 |
| **Prediction missing** | +40 | predBg/eventualBg null OU predBGs vide |
| **PKPD contradiction** | +25 | PRE_ONSET + IOB>1.0 + SMBprop>0.8 |
| **Autodrive stuck** | +20 | Active mais SMB<0.05, IOB<0.5, delta>1.0 |
| **High noise** | +15 | noise ≥ 3 |
| **Stale data** | +25 | isStale = true |
| **Pump unreachable** | +30 | pumpUnreachable = true |

### Tiers

| Score | Tier | Action |
|-------|------|--------|
| 0-19 | NONE | Aucune intervention |
| 20-39 | LOW | Monitoring seulement |
| 40-69 | MEDIUM | Sentinel advice appliqué (local) |
| 70-100 | HIGH | Sentinel + External Auditor si dispo |

### Recommandations

| Recommendation | smbFactor | extraInterval | preferBasal | Cas |
|----------------|-----------|---------------|-------------|-----|
| **CONFIRM** | 1.0 | 0 | false | Normal, tout OK |
| **REDUCE_SMB** | 0.7-0.8 | 3-4min | false | Variabilité, prediction missing |
| **INCREASE_INTERVAL** | 0.8-0.9 | 3-4min | false | Contradiction PKPD, recent bolus |
| **PREFER_BASAL** | 0.8-0.9 | 2min | true | Drift lent, autodrive stuck |
| **HOLD_SOFT** | 0.6 | 6min | false | Stacking risk, SMB chain |

---

## 🌐 EXTERNAL AUDITOR (Tier 2)

### Rôle
**API optionnelle pour cas complexes (tier HIGH uniquement)**

### Conditions de Déclenchement

**TOUS ces critères doivent être vrais** :
1. ✅ `aiAuditorEnabled = true` (préférence)
2. ✅ API key présente pour provider sélectionné
3. ✅ Sentinel tier == **HIGH** (ou MEDIUM si mode AGGRESSIVE)
4. ✅ Pas en cooldown (5min HIGH, 10min MEDIUM)
5. ✅ Budget OK (6 appels/h max, rolling window)
6. ✅ Pas de noise extrême / stale sévère

**Exception bypass** : predictionMissing + stackingRisk → 1 bypass /15min autorisé

### Cadence/Budget

| Tier | Cooldown | Budget/h | Exception |
|------|----------|----------|-----------|
| HIGH | 5 min | 6 appels | +1 bypass/15min si critical |
| MEDIUM (aggressive) | 10 min | 3 appels | Aucun |

### Prompt Structure

**Compact, stable, orienté audit** :

```json
{
  "window30min": {
    "bg_series": [...],
    "delta_series": [...],
    "iob_series": [...],
    "smb_delivered": [...],
    "tbr_series": [...]
  },
  "pkpd": {
    "stage": "PEAK",
    "diaMin": 240,
    "peakMin": 75,
    "activity": 0.65
  },
  "predictions": {
    "eventualBg": 145.0,
    "predictedBg": 138.0,
    "predBgsSize": 12,
    "lastPred": 142.0
  },
  "states": {
    "autodrive": "ACTIVE_MODERATE",
    "mode": "LUNCH_P2",
    "mealadvisor": "INACTIVE"
  },
  "clamps": {
    "maxIOB": 5.0,
    "maxSMB": 2.0,
    "maxSMBHB": 3.0,
    "intervalMin": 5,
    "lgsThreshold": 70
  },
  "firstBrainDecision": {
    "smbU": 1.2,
    "tbrUph": null,
    "tbrMin": null,
    "reason": "UAM rise detection, SMB proposed"
  },
  "sentinelAdvice": {
    "score": 78,
    "tier": "HIGH",
    "reason": "STACKING_RISK",
    "recommendation": "HOLD_SOFT",
    "smbFactor": 0.6,
    "extraIntervalMin": 6
  }
}
```

### Réponse Attendue (JSON Strict)

```json
{
  "status": "OK",
  "confidence": 0.78,
  "recommendation": "REDUCE_SMB",
  "smb_factor": 0.65,
  "extra_interval_min": 4,
  "prefer_basal": false,
  "notes": "High IOB + PKPD PEAK detected. Reduce SMB to avoid stacking."
}
```

**Champs** :
- `status` : "OK", "SKIP", "ERROR"
- `confidence` : 0.0-1.0
- `recommendation` : "CONFIRM", "REDUCE_SMB", "INCREASE_INTERVAL", "PREFER_BASAL", "HOLD_SOFT"
- `smb_factor` : 0.0-1.0
- `extra_interval_min` : 0-20
- `prefer_basal` : boolean
- `notes` : texte explicatif

### Application Réponse

**Règles** :
- Si `confidence < 0.6` → Appliquer seulement `+interval` ou `prefer_basal`, pas de grosse réduction
- Jamais augmenter SMB au-delà de first brain
- Jamais réduire interval sous minimum hard
- Toujours logger : `"AUDITOR applied=true/false reason=..."`
- Combiner avec Sentinel : **Most conservative wins**

---

## 🔗 INTÉGRATION PIPELINE (Point Unique)

### Pipeline Strict (Ordre Impératif)

```
1. Compute AIMI Core Decision
   ├─ Modes (breakfast, lunch, dinner, etc.)
   ├─ Autodrive
   ├─ ML / Neurones
   ├─ Meal Advisor
   └─ → SMB proposé, TBR proposé, interval proposé

2. Compute PKPD Stage & Safety Baseline
   ├─ PkPdIntegration.computeRuntime()
   ├─ PKPD Absorption Guard
   └─ LGS / noise / stale checks

3. Compute Local Sentinel Advice
   ├─ LocalSentinel.computeAdvice(...)
   └─ → score, tier, recommendation, smbFactor, extraInterval

4. Optionally Call External Auditor (NON-BLOQUANT)
   ├─ IF tier >= HIGH (ou MEDIUM si aggressive)
   ├─ ET enabled + apikey + budget OK
   ├─ Async call avec timeout 30s
   └─ → verdict externe OU null

5. Apply Guards (POINT UNIQUE)
   ├─ Combiner Sentinel + External (most conservative)
   ├─ Appliquer smbFactor, extraInterval, preferBasal
   ├─ finalizeAndCapSMB(...)
   └─ setTempBasal(...)

6. Final Decision
   └─ Return RT avec logs complets
```

### Decision Combiner (Most Conservative Wins)

```kotlin
fun combineAdvice(
    sentinel: SentinelAdvice,
    external: ExternalVerdict?
): CombinedAdvice {
    
    // Si External null ou confidence faible, utiliser Sentinel seul
    if (external == null || external.confidence < 0.6) {
        return CombinedAdvice.fromSentinel(sentinel)
    }
    
    // Sinon, prendre le plus conservateur
    val finalSmbFactor = min(sentinel.smbFactor, external.smbFactor)
    val finalExtraInterval = max(sentinel.extraIntervalMin, external.extraIntervalMin)
    val finalPreferBasal = sentinel.preferBasal || external.preferBasal
    
    return CombinedAdvice(
        smbFactor = finalSmbFactor,
        extraIntervalMin = finalExtraInterval,
        preferBasal = finalPreferBasal,
        appliedSentinel = true,
        appliedExternal = external != null,
        reason = buildCombinedReason(sentinel, external)
    )
}
```

---

## 📋 LOGS RT ULTRA-PREMIUM

### Format rT.reason

```
SMB: 1.2U → 0.72U (×0.6) | Interval: 5min +6min = 11min | Prefer: BASAL
SENTINEL: score=78 tier=HIGH reason=STACKING_RISK rec=HOLD_SOFT

AUDITOR: eligible=true cooldown=OK budget=4/6 provider=GEMINI
AUDITOR: status=OK confidence=0.71 rec=REDUCE_SMB applied=true
COMBINED: smb×0.60 +6m preferBasal=true (Sentinel+External, most conservative)
```

### Format consoleLog

```
🔍 SENTINEL: score=78 tier=HIGH reason=STACKING_RISK
  └─ Details: ["STACKING: IOB=2.4 stage=PEAK activity=0.68", "SMB_CHAIN: count30=3 total60=3.2"]
  └─ Recommendation: HOLD_SOFT smb×0.6 +6m preferBasal=false

🌐 AUDITOR: tier=HIGH → External eligible
  └─ Provider: GEMINI, Cooldown: OK, Budget: 4/6
  └─ Prompt sent (352 chars)
  └─ Response: OK confidence=0.71 rec=REDUCE_SMB smb×0.65 +4m

✅ COMBINED: Sentinel(0.6,+6) + External(0.65,+4) → Final(0.6,+6) [most conservative]

🛡️ APPLIED_GUARD: SMB 1.20U → 0.72U (×0.60) | Interval +6min | Prefer basal: true
```

---

## 🧪 SCÉNARIOS DE TEST

### Scénario 1: Drift Lent
**Input** :
- BG: 165 mg/dL (target 100)
- Delta: +1.2 stable 30min
- IOB: 0.8U, SMB proposé: 1.0U

**Attendu** :
- Sentinel: tier=MEDIUM, rec=PREFER_BASAL
- External: Non appelé (tier < HIGH)
- Applied: smb×0.8, +2min, preferBasal=true

### Scénario 2: SMB Chain + IOB High
**Input** :
- BG: 155, IOB: 2.4U
- PKPD stage: PEAK, activity: 0.68
- SMB 30min: 3 (chain), total 60min: 3.2U

**Attendu** :
- Sentinel: tier=HIGH score=78, rec=HOLD_SOFT
- External: Appelé si enabled
- Applied: smb×0.6, +6min

### Scénario 3: Prediction Missing
**Input** :
- predBg: null, eventualBg: null
- BG: 140, delta: +2.5
- SMB proposé: 1.5U

**Attendu** :
- Sentinel: tier=HIGH score=80, rec=REDUCE_SMB (degraded mode)
- External: Appelé
- Applied: smb×0.7, +4min

### Scénario 4: BG <120 + Delta Positif
**Input** :
- BG: 115, delta: +1.8
- Target: 100
- SMB proposé: 0.8U

**Attendu** :
- Sentinel: tier=LOW, rec=CONFIRM (limiter variabilité pour éviter hypo)
- Applied: smb×0.9, +1min (clamp variabilité)

### Scénario 5: Autodrive Stuck
**Input** :
- Autodrive: ACTIVE
- SMB proposé: 0.02U (quasiment 0)
- IOB: 0.3U, delta: +2.0, age > 30min

**Attendu** :
- Sentinel: tier=MEDIUM, rec=PREFER_BASAL (contradiction)
- Applied: smb×0.9, +2min, preferBasal=true

### Scénario 6: Normal Stable In-Range
**Input** :
- BG: 105, target: 100
- Delta: -0.2, IOB: 0.6U
- Tout stable

**Attendu** :
- Sentinel: tier=NONE score=5, rec=CONFIRM
- External: Non appelé
- Applied: Aucune modulation (smb×1.0, +0min)

---

## ⚙️ PRÉFÉRENCES UTILISATEUR

### aiAuditorMode

| Mode | Seuil External | Budget/h External | Sentinel Tier Threshold |
|------|----------------|-------------------|-------------------------|
| **CONSERVATIVE** | HIGH only | 6 | ≥70 |
| **BALANCED** (défaut) | HIGH + MEDIUM critique | 8 | ≥60 |
| **AGGRESSIVE** | MEDIUM + HIGH | 10 | ≥40 |

### aiAuditorProvider
- GEMINI (défaut)
- OPENAI
- DEEPSEEK
- CLAUDE

### aiAuditorTimeoutSeconds
- Défaut: 30s
- Min: 15s, Max: 60s

### aiAuditorMaxPerHour
- Défaut: 6 (CONSERVATIVE)
- BALANCED: 8
- AGGRESSIVE: 10

---

## 🚀 BUILD & VALIDATION

### Compilation
```bash
./gradlew :plugins:aps:compileFullDebugKotlin
./gradlew assembleDebug
```

### Tests
Chaque scénario doit être testé avec :
1. Sentinel seul (External disabled)
2. Sentinel + External (External enabled, tier HIGH)
3. Logs rT vérifiés

---

## 📊 MÉTRIQUES ATTENDUES

### Post-Déploiement (1ère semaine)

**Sentinel** :
- Activations tier MEDIUM+ : ~15-25% des décisions
- Activations tier HIGH : ~5-10% des décisions
- Score moyen (quand tier > NONE) : 40-65

**External Auditor** (si enabled) :
- Appels API : ~3-6 / heure (selon mode)
- Taux succès : >85%
- Timeout : <10%
- Confidence moyenne : >0.65

**Impact** :
- Réduction hypoglycémies post-UAM : 30-50%
- TIR maintenu ou amélioré
- Pas d'hypers prolongées (>250 durant >3h)

---

## 📝 PROCHAINES ÉTAPES

### Phase 1: Implémentation Core ✅
- [x] LocalSentinel.kt créé
- [ ] AuditorOrchestrator.kt modifié (intégration 2-tier)
- [ ] DetermineBasalAIMI2.kt modifié (pipeline unique)
- [ ] RtInstrumentationHelpers.kt modifié (logs premium)

### Phase 2: Tests
- [ ] 6 scénarios testés
- [ ] Build validé
- [ ] Logs RT vérifiés

### Phase 3: Documentation
- [ ] README utilisateur
- [ ] Guide tuning
- [ ] FAQ troubleshooting

---

**Date**: 2025-12-31  
**Status**: 🔄 IMPLEMENTATION EN COURS  
**Priorité**: 🔴 ULTRA-PREMIUM
