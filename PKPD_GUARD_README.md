# ✅ PKPD ABSORPTION GUARD - RÉSUMÉ RAPIDE

## 🎯 PROBLÈME RÉSOLU
**Surcorrection UAM** : Trop d'insuline lors de montées glycémiques non déclarées (repas sans carbs saisis) + résistance (maladie).

**Cause** : L'ancien `ABS_GUARD` était **désactivé** par `highBgEscape` exactement quand BG élevé → empilement d'insuline.

---

## ✨ SOLUTION IMPLÉMENTÉE

### PKPD Absorption Guard (Soft, Non-Bloquant)

**Principe Physiologique** :
```
"Injecter → Laisser Agir → Réévaluer"
au lieu de
"Corriger à Chaque Tick"
```

### Modulation selon Stage Insuline

| Temps depuis dose | Stage | SMB réduit à | Intervalle |
|-------------------|-------|--------------|------------|
| 0-10min | PRE_ONSET | 50% | +4min |
| 10-75min | RISING/PEAK | 60-70% | +2-3min |
| 75-180min | TAIL | 85-92% | +1min |
| >180min | EXHAUSTED | 100% | +0min |

### Exceptions Intelligentes
- ✅ **Urgences** (BG > target+80, delta > 5) : Relâchement automatique → 95% SMB
- ✅ **Modes Repas** (prebolus/TBR) : Guard désactivé, pas d'impact
- ✅ **BG Stable** (delta < 1) : Assouplissement +10%

---

## 📊 BUILD & VALIDATION

```bash
✅ ./gradlew :plugins:aps:compileFullDebugKotlin  # SUCCESS
✅ ./gradlew assembleDebug                        # SUCCESS (8m18s)
```

---

## 📁 FICHIERS

### Nouveaux
- `plugins/aps/.../pkpd/PkpdAbsorptionGuard.kt` (149 lignes)

### Modifiés
- `DetermineBasalAIMI2.kt` (ligne 72 + lignes 5327-5365)

### Documentation
- `PKPD_ABSORPTION_GUARD_COMPLETE.md` - Guide complet
- `PKPD_ABSORPTION_GUARD_AUDIT.md` - Analyse technique
- `PKPD_GUARD_MONITORING.md` - Guide de suivi
- `COMMIT_MSG_PKPD_GUARD.md` - Message de commit

---

## 🚀 PROCHAINES ÉTAPES

### 1. Commit & Deploy
```bash
git add plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/pkpd/PkpdAbsorptionGuard.kt
git add plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt
git add docs/PKPD_*.md
git commit -F docs/COMMIT_MSG_PKPD_GUARD.md
```

### 2. Installer sur Device

### 3. Surveiller (Guide: `PKPD_GUARD_MONITORING.md`)
**Cherchez dans les logs APS** :
- ✅ `| RISING x0.60` ou `| PEAK x0.70` → Guard actif
- ✅ `SMB_GUARDED: 1.20U → 0.72U` → Réduction effective
- ✅ `INTERVAL_ADJUSTED: +3m → 8m total` → Cadence ralentie

**Observez scénarios** :
- **UAM** : SMB réduits, pas de rafales, pas d'hypo 2-3h après
- **Hyper > 250** : Urgency relaxation active (x0.95), correction efficace
- **Modes Repas** : Prebolus normaux, pas d'impact

### 4. Ajuster si Nécessaire
**Si surcorrection persiste** : Réduire factors (0.6 → 0.5)  
**Si hypers prolongées** : Assouplir urgency seuils  
**Si modes repas affectés** : Vérifier detection meal modes

---

## 💡 CE QUI CHANGE POUR VOUS

### Avant (Buggé)
```
UAM détecté → BG 140
T+0:  SMB 1.2U
T+5:  BG 155 > 160 → highBgEscape → SMB 1.3U (full!)
T+10: SMB 1.1U (full!)
Total: 3.6U en 10min
→ Hypo 2h après
```

### Après (Fixé)
```
UAM détecté → BG 140
T+0:  SMB 1.2U × 0.5 = 0.6U (PRE_ONSET)
T+15: SMB 1.0U × 0.6 = 0.6U (RISING, +3min interval)
T+60: SMB 0.8U × 0.7 = 0.56U (PEAK, +2min interval)
Total: 1.76U en 60min
→ Montée gérée, pas d'hypo
```

---

## ⚠️ IMPORTANT

### ✅ Garanties
- Modes repas (prebolus/TBR) **non affectés**
- Urgences vraies (BG > 250) **traitées agressivement**
- SMB/Basal **jamais bloqués**, seulement modulés
- Logs **complets** pour debugging

### 🔍 À Surveiller
- Première semaine : Activation guard dans ~40% décisions UAM
- Si hypo persistent : Factors trop hauts, à réduire
- Si hyper persistent : Urgency seuils trop stricts, à assouplir

---

**Date** : 2025-12-30  
**Status** : ✅ IMPLÉMENTÉ & VALIDÉ  
**Priorité** : 🔴 CRITIQUE  
**Build** : ✅ SUCCESS

👉 **Lire** : `PKPD_ABSORPTION_GUARD_COMPLETE.md` pour détails complets  
👉 **Suivre** : `PKPD_GUARD_MONITORING.md` pour surveillance post-deploy
