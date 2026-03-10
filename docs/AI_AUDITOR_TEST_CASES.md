# 🧪 AI Decision Auditor - Cas de Test & Exemples

## Exemples de Prompts Générés et Réponses Attendues

Ce document présente des cas concrets de situations glycémiques et les verdicts attendus de l'AI Auditor.

---

## Cas 1 : Stacking Risk (IOB Activity Élevée)

### Contexte

```json
{
  "snapshot": {
    "bg": 180.0,
    "delta": 4.0,
    "shortAvgDelta": 3.5,
    "longAvgDelta": 2.8,
    "unit": "mg/dL",
    "timestamp": 1703001600000,
    "cgmAgeMin": 2,
    "noise": "CLEAN",
    "iob": 2.5,
    "iobActivity": 0.75,
    "cob": 15.0,
    "isfProfile": 50.0,
    "isfUsed": 48.0,
    "ic": 10.0,
    "target": 100.0,
    "pkpd": {
      "diaMin": 300,
      "peakMin": 60,
      "tailFrac": 0.25,
      "onsetConfirmed": true,
      "residualEffect": 0.75
    },
    "activity": {
      "steps5min": 12,
      "steps30min": 85,
      "hrAvg5": 72,
      "hrAvg15": 75
    },
    "states": {
      "modeType": null,
      "modeRuntimeMin": null,
      "autodriveState": "OFF",
      "wcyclePhase": null,
      "wcycleFactor": null
    },
    "limits": {
      "maxSMB": 1.5,
      "maxSMBHB": 2.0,
      "maxIOB": 4.0,
      "maxBasal": 2.0,
      "tbrMaxMode": null,
      "tbrMaxAutoDrive": null
    },
    "decisionAimi": {
      "smbU": 0.8,
      "tbrUph": null,
      "tbrMin": null,
      "intervalMin": 3.0,
      "reasonTags": ["rising_bg", "above_target", "cob_active"]
    },
    "lastDelivery": {
      "lastBolusU": null,
      "lastBolusTime": null,
      "lastSmbU": 0.6,
      "lastSmbTime": 1703001120000,
      "lastTbrRate": null,
      "lastTbrTime": null
    }
  },
  "history": {
    "bgSeries": [175, 172, 168, 165, 162, 160, 158, 156, 154, 152, 150, 148],
    "deltaSeries": [3.0, 4.0, 3.0, 3.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0],
    "iobSeries": [2.5, 2.3, 2.1, 2.0, 1.8, 1.7, 1.5, 1.4, 1.2, 1.1, 1.0, 0.9],
    "tbrSeries": [null, null, null, null, null, null, null, null, null, null, null, null],
    "smbSeries": [0.6, 0.0, 0.0, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
    "hrSeries": [72, 73, 74, 75, 76, 75, 74, 73, 72, 72, 71, 70],
    "stepsSeries": [10, 15, 8, 12, 20, 18, 15, 10, 8, 5, 3, 2]
  },
  "stats": {
    "tir": 68.5,
    "hypoPct": 3.2,
    "hyperPct": 28.3,
    "meanBG": 145.0,
    "cv": 32.5,
    "tdd7dAvg": 42.0,
    "basalPct": 48.0,
    "bolusPct": 52.0
  }
}
```

### Verdict Attendu

```json
{
  "verdict": "SOFTEN",
  "confidence": 0.88,
  "degradedMode": false,
  "riskFlags": ["stacking_risk"],
  "evidence": [
    "IOB activity at peak (75%), last SMB 8min ago, proposed 0.8U risks stacking",
    "BG rising steadily (+4 mg/dL/5min) but already 2.5U IOB on board",
    "Recommend reducing SMB to 50% and increasing interval by 3min"
  ],
  "boundedAdjustments": {
    "smbFactorClamp": 0.5,
    "intervalAddMin": 3,
    "preferTbr": false,
    "tbrFactorClamp": 1.0
  },
  "debugChecks": [
    "check_pkpd_used_in_smb_throttle",
    "check_iob_activity_visible_in_UI"
  ]
}
```

**Résultat :** SMB 0.8 → 0.4U, interval 3 → 6 min

---

## Cas 2 : Montée Persistante, IOB Activity Faible

### Contexte

```json
{
  "snapshot": {
    "bg": 200.0,
    "delta": 5.0,
    "shortAvgDelta": 4.5,
    "longAvgDelta": 4.0,
    "iob": 1.0,
    "iobActivity": 0.15,
    "cob": 30.0,
    "decisionAimi": {
      "smbU": 1.0,
      "intervalMin": 3.0,
      "reasonTags": ["persistent_rise", "above_target"]
    },
    "pkpd": {
      "residualEffect": 0.15
    }
  },
  "history": {
    "bgSeries": [195, 190, 185, 180, 175, 170, 165, 160, 155, 150, 145, 140],
    "deltaSeries": [5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0],
    "smbSeries": [0.0, 0.0, 0.0, 0.4, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]
  }
}
```

### Verdict Attendu

```json
{
  "verdict": "CONFIRM",
  "confidence": 0.92,
  "degradedMode": false,
  "riskFlags": [],
  "evidence": [
    "BG rising persistently +5 mg/dL/5min for 60+ minutes",
    "Low IOB activity (15%), insulin in tail phase",
    "SMB 1.0U is reasonable given low residual effect and strong rise"
  ],
  "boundedAdjustments": {
    "smbFactorClamp": 1.0,
    "intervalAddMin": 0,
    "preferTbr": false,
    "tbrFactorClamp": 1.0
  },
  "debugChecks": []
}
```

**Résultat :** Aucune modulation, décision AIMI approuvée

---

## Cas 3 : Prédiction Absente (Mode Dégradé)

### Contexte

```json
{
  "snapshot": {
    "bg": 160.0,
    "delta": 3.0,
    "shortAvgDelta": 2.5,
    "iob": 1.8,
    "iobActivity": null,
    "cob": null,
    "decisionAimi": {
      "smbU": 0.7,
      "intervalMin": 3.0,
      "reasonTags": ["prediction_absent", "above_target"]
    },
    "pkpd": {
      "residualEffect": null
    }
  }
}
```

### Verdict Attendu

```json
{
  "verdict": "SOFTEN",
  "confidence": 0.72,
  "degradedMode": true,
  "riskFlags": ["prediction_missing"],
  "evidence": [
    "Prediction absent, entering degraded mode",
    "Without prediction, recommend conservative approach",
    "Increase interval +3min and prefer TBR over aggressive SMB"
  ],
  "boundedAdjustments": {
    "smbFactorClamp": 0.7,
    "intervalAddMin": 3,
    "preferTbr": true,
    "tbrFactorClamp": 1.0
  },
  "debugChecks": [
    "check_prediction_visible_in_UI",
    "check_cgm_sensor_age"
  ]
}
```

**Résultat :** SMB 0.7 → 0.49U, interval 3 → 6 min, preferTBR activé

---

## Cas 4 : BG Bas + SMB Proposé

### Contexte

```json
{
  "snapshot": {
    "bg": 105.0,
    "delta": -1.0,
    "shortAvgDelta": -0.5,
    "iob": 0.8,
    "iobActivity": 0.35,
    "target": 100.0,
    "decisionAimi": {
      "smbU": 0.3,
      "intervalMin": 3.0,
      "reasonTags": ["slight_rise_expected"]
    }
  }
}
```

### Verdict Attendu

```json
{
  "verdict": "SOFTEN",
  "confidence": 0.80,
  "degradedMode": false,
  "riskFlags": ["hypo_risk"],
  "evidence": [
    "BG near target (105 mg/dL) with negative delta",
    "Proposing SMB at this level risks hypo",
    "Recommend reducing SMB to 30% and observing"
  ],
  "boundedAdjustments": {
    "smbFactorClamp": 0.3,
    "intervalAddMin": 2,
    "preferTbr": false,
    "tbrFactorClamp": 1.0
  },
  "debugChecks": []
}
```

**Résultat :** SMB 0.3 → 0.09U, interval 3 → 5 min

---

## Cas 5 : Mode Repas (Prebolus P1)

### Contexte

```json
{
  "snapshot": {
    "bg": 140.0,
    "delta": 2.0,
    "iob": 1.5,
    "states": {
      "modeType": "breakfast",
      "modeRuntimeMin": 2
    },
    "decisionAimi": {
      "smbU": 1.2,
      "intervalMin": 3.0,
      "reasonTags": ["breakfast_P1"]
    }
  }
}
```

### Verdict Attendu

```json
{
  "verdict": "CONFIRM",
  "confidence": 0.95,
  "degradedMode": false,
  "riskFlags": [],
  "evidence": [
    "Breakfast mode active, runtime 2min (P1 window)",
    "P1 prebolus should be delivered as planned",
    "NEVER reduce P1/P2 phases"
  ],
  "boundedAdjustments": {
    "smbFactorClamp": 1.0,
    "intervalAddMin": 0,
    "preferTbr": false,
    "tbrFactorClamp": 1.0
  },
  "debugChecks": [
    "check_mode_phase_executed"
  ]
}
```

**Résultat :** Aucune modulation (respect P1/P2)

---

## Cas 6 : Shift to TBR (High IOB + Prédiction Absente)

### Contexte

```json
{
  "snapshot": {
    "bg": 210.0,
    "delta": 2.0,
    "iob": 3.8,
    "iobActivity": 0.85,
    "decisionAimi": {
      "smbU": 1.0,
      "tbrUph": 1.5,
      "tbrMin": 30,
      "intervalMin": 3.0
    },
    "pkpd": {
      "residualEffect": null
    }
  }
}
```

### Verdict Attendu

```json
{
  "verdict": "SHIFT_TO_TBR",
  "confidence": 0.88,
  "degradedMode": true,
  "riskFlags": ["prediction_missing", "stacking_risk"],
  "evidence": [
    "Very high IOB (3.8U) at peak activity (85%)",
    "No prediction available, degraded mode",
    "Shift to TBR-based approach with minimal SMB"
  ],
  "boundedAdjustments": {
    "smbFactorClamp": 0.2,
    "intervalAddMin": 0,
    "preferTbr": true,
    "tbrFactorClamp": 1.1
  },
  "debugChecks": [
    "check_prediction_visible_in_UI",
    "check_pkpd_used_in_smb_throttle"
  ]
}
```

**Résultat :** SMB 1.0 → 0.2U, preferTBR + TBR ajusté

---

## Cas 7 : Autodrive Active + Montée Ignorée

### Contexte

```json
{
  "snapshot": {
    "bg": 130.0,
    "delta": 6.0,
    "shortAvgDelta": 5.5,
    "iob": 0.5,
    "states": {
      "autodriveState": "CONFIRMED"
    },
    "decisionAimi": {
      "smbU": 0.2,
      "intervalMin": 5.0,
      "reasonTags": ["autodrive_conservative"]
    }
  },
  "history": {
    "deltaSeries": [6.0, 6.0, 5.5, 5.0, 5.0, 4.5, 4.0, 4.0, 3.5, 3.0, 3.0, 2.5]
  }
}
```

### Verdict Attendu

```json
{
  "verdict": "SOFTEN",
  "confidence": 0.78,
  "degradedMode": false,
  "riskFlags": ["rapid_rise_ignored"],
  "evidence": [
    "BG rising rapidly +6 mg/dL/5min consistently for 60+ minutes",
    "Autodrive CONFIRMED but SMB only 0.2U seems insufficient",
    "However, respect autodrive conservative approach, slight increase only"
  ],
  "boundedAdjustments": {
    "smbFactorClamp": 1.0,
    "intervalAddMin": -2,
    "preferTbr": false,
    "tbrFactorClamp": 1.0
  },
  "debugChecks": [
    "check_autodrive_not_sticky"
  ]
}
```

**Note :** Dans ce cas, le LLM suggère de garder SMB mais réduire interval (impossible avec notre encoding actuel qui ne supporte que +0 à +6). On pourrait ajuster le prompt pour permettre des ajustements bidirectionnels, ou accepter que dans ce cas rare le verdict soit CONFIRM.

---

## Cas 8 : WCycle Phase Lutéale + Résistance

### Contexte

```json
{
  "snapshot": {
    "bg": 170.0,
    "delta": 3.0,
    "iob": 2.0,
    "states": {
      "wcyclePhase": "LUTEAL",
      "wcycleFactor": 1.25
    },
    "decisionAimi": {
      "smbU": 0.8,
      "intervalMin": 3.0,
      "reasonTags": ["wcycle_resistance"]
    }
  }
}
```

### Verdict Attendu

```json
{
  "verdict": "CONFIRM",
  "confidence": 0.85,
  "degradedMode": false,
  "riskFlags": [],
  "evidence": [
    "WCycle luteal phase active (factor 1.25)",
    "Increased insulin resistance expected",
    "SMB 0.8U is appropriate given wcycle adjustment"
  ],
  "boundedAdjustments": {
    "smbFactorClamp": 1.0,
    "intervalAddMin": 0,
    "preferTbr": false,
    "tbrFactorClamp": 1.0
  },
  "debugChecks": []
}
```

---

## Pattern Recognition Examples

### Pattern 1 : "Rafale SMB Sans Effet"

**Historique :**
- SMB series : [0.5, 0.0, 0.5, 0.0, 0.6, 0.0, 0.5]
- BG series : [180, 182, 185, 187, 190, 192, 195]

**Evidence attendue :**
> "Multiple SMBs delivered (0.5-0.6U) over 35 minutes with no BG impact. Possible absorption issue, site problem, or insulin degradation. Recommend shifting to TBR."

**Verdict :** SHIFT_TO_TBR

---

### Pattern 2 : "Inversion de Pente"

**Historique :**
- BG series : [150, 155, 160, 165, 168, 168, 165, 162]
- Delta series : [5.0, 5.0, 5.0, 3.0, 0.0, -3.0, -3.0]

**Evidence attendue :**
> "BG trend reversing: was rising +5 mg/dL/5min, now falling -3 mg/dL/5min. Insulin is 'taking'. Reduce proposed SMB to avoid overcorrection."

**Verdict :** SOFTEN (SMB factor 0.4-0.6)

---

### Pattern 3 : "PKPD Onset Tardif"

**Contexte :**
- Last SMB : 1.2U il y a 45 min
- BG : Rising +3 mg/dL/5min
- PKPD onset : non confirmé

**Evidence attendue :**
> "Large SMB (1.2U) delivered 45 min ago but PKPD onset not confirmed yet. Effect may arrive suddenly. Exercise caution with additional SMB."

**Verdict :** SOFTEN

---

## Anti-Patterns (LLM Trop Prudent)

### ❌ Mauvaise Réponse

```json
{
  "verdict": "SOFTEN",
  "evidence": ["BG < 200, recommend caution"],
  "boundedAdjustments": {
    "smbFactorClamp": 0.1
  }
}
```

**Problème :** Trop conservateur sans raison clinique.

### ✅ Bonne Réponse

```json
{
  "verdict": "CONFIRM",
  "evidence": [
    "BG 180 rising +4 mg/dL/5min with low IOB (0.8U)",
    "SMB 0.8U is appropriate"
  ]
}
```

---

## Résumé

Ces exemples montrent comment l'auditeur :

1. ✅ **Détecte les risques** (stacking, hypo, absorption issues)
2. ✅ **Respecte les contraintes** (P1/P2, modes repas, autodrive)
3. ✅ **Utilise le contexte PKPD** (activity, onset, tail)
4. ✅ **Reconnaît les patterns** (inversion pente, rafale SMB, etc.)
5. ✅ **Mode dégradé** (prediction absente → conservative)
6. ✅ **Modulation bornée** (jamais de dosage libre)

Le prompt est conçu pour éviter le "LLM trop prudent" en donnant des règles cliniques explicites et en demandant une analyse **critique** basée sur les données.

---

## Testing

Pour tester, créer des JSON de test avec différents scénarios et observer les verdicts dans les logs.

Adapter le prompt selon les retours terrain.

🧪 **La science devient art quand les patterns émergent.**
