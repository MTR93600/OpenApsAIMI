🛡️ PKPD Absorption Guard - Fix Surcorrection UAM

## Problème Résolu
Surcorrection lors de montées glycémiques UAM (repas non déclarés) après le fix "Hyper Kicker Early Return".
L'ancien ABS_GUARD était désactivé par `highBgEscape` exactement quand il devait être actif (BG > target+60).

## Solution
Implémentation d'un garde-fou soft basé sur la physiologie de l'absorption d'insuline (PKPD).

**Principe** : "Injecter → Laisser agir → Réévaluer" au lieu de "corriger à chaque tick"

### Modulation Selon Stage Activité Insuline
- **PRE_ONSET** : SMB x0.5, interval +4min (insuline pas encore active)
- **RISING** : SMB x0.6, interval +3min (absorption en cours)
- **PEAK** : SMB x0.7, interval +2min (activité maximale)
- **TAIL (>50%)** : SMB x0.85, interval +1min (encore 50%+ actif)
- **EXHAUSTED** : SMB x1.0, interval +0min (pas de restriction)

### Protection Non-Bloquante
- ✅ Urgency relaxation pour vraies urgences (BG > target+80, delta > 5)
- ✅ Modes repas (prebolus/TBR) non affectés
- ✅ Logs complets (consoleError, consoleLog, rT.reason)

## Fichiers
**Nouveaux** :
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/pkpd/PkpdAbsorptionGuard.kt`

**Modifiés** :
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt` (lignes 72, 5327-5365)

## Validation
```
✅ ./gradlew :plugins:aps:compileFullDebugKotlin - SUCCESS
✅ ./gradlew assembleDebug - SUCCESS (8m18s)
```

## Documentation
- `docs/PKPD_ABSORPTION_GUARD_COMPLETE.md` - Implémentation complète
- `docs/PKPD_ABSORPTION_GUARD_AUDIT.md` - Analyse détaillée

---
**Date** : 2025-12-30  
**Priorité** : CRITIQUE  
**Impact** : Sécurité UAM, prévention surcorrection
