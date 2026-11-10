# 📊 PKPD ABSORPTION GUARD - GUIDE DE MONITORING

## Date: 2025-12-30

---

## 🎯 OBJECTIF

Ce guide vous aide à surveiller l'efficacité du PKPD Absorption Guard après déploiement et à identifier si des ajustements sont nécessaires.

---

## 📱 LOGS À SURVEILLER DANS L'APP

### Dans les Détails de la Décision APS

**Recherchez dans `rT.reason`** :
```
| PRE_ONSET x0.50
| RISING x0.60
| PEAK x0.70
| TAIL_HIGH x0.85
| TAIL_MED x0.92
| PEAK_URGENCY_RELAXED x0.95
| RISING_STABLE x0.70
```

### Dans les Logs Détaillés (Debugging)

**consoleError** :
```
PKPD_GUARD stage=PEAK factor=0.70 +2m reason=PEAK
```

**consoleLog** :
```
SMB_GUARDED: 1.20U → 0.84U
INTERVAL_ADJUSTED: +2m → 7m total
```

---

## 🔍 SCÉNARIOS À OBSERVER

### 1. UAM (Unannounced Meal) - CAS PRINCIPAL

**Situation** :
- Repas non déclaré (pas de COB saisi)
- BG commence à monter (ex: 140 → 160 → 180)
- Delta positif modéré (+2 à +5 mg/dL/5min)

**Ce Qu'il Faut Voir** :
✅ **SUCCÈS** :
- SMB réduits dans les 0-75 premières minutes après dose
- Exemple : `SMB_GUARDED: 1.20U → 0.60U` (RISING x0.60)
- Intervalle augmenté : `INTERVAL_ADJUSTED: +3m`
- Pas de rafales de SMB (espacés de 7-10min au lieu de 5min)
- BG monte mais se stabilise progressivement SANS hypoglycémie ultérieure

❌ **ÉCHEC** (nécessite ajustement) :
- SMB toujours full dose malgré insuline récente active
- Aucun log `PKPD_GUARD` visible
- Hypoglycémie 2-3h après UAM (surcorrection)

### 2. Hyper Sévère (BG > 250) - URGENCE

**Situation** :
- BG très élevé (ex: 270 mg/dL)
- Delta fort (+8 mg/dL/5min)
- predBg élevé (ex: 310 mg/dL)

**Ce Qu'il Faut Voir** :
✅ **SUCCÈS** :
- Guard relâché par urgency : `PEAK_URGENCY_RELAXED x0.95`
- SMB presque complet (95% au lieu de 70%)
- BG redescend efficacement SANS blocage excessif

❌ **ÉCHEC** :
- Guard trop restrictif même en urgence (factor 0.70 maintenu)
- BG reste en hyper prolongée
- → **Action** : Augmenter seuil urgency ou boost factor

### 3. Mode Repas (Prebolus) - NON AFFECTÉ

**Situation** :
- Mode breakfast/lunch/dinner actif
- Prebolus1 ou Prebolus2 dû

**Ce Qu'il Faut Voir** :
✅ **SUCCÈS** :
- Aucun log `PKPD_GUARD` (guard neutre)
- Prebolus envoyé normalement
- TBR mode repas non réduit

❌ **ÉCHEC** :
- Guard actif pendant mode repas
- Prebolus réduit (ex: `RISING x0.60` pendant breakfast)
- → **BUG** : Vérifier `anyMealModeForGuard` detection

### 4. Stable/Falling BG - MODULATION

**Situation** :
- BG stable (delta < 1.0) ou baisse légère
- Insuline encore active (TAIL stage)

**Ce Qu'il Faut Voir** :
✅ **SUCCÈS** :
- Guard assoupli : `TAIL_MED_STABLE x0.92` (au lieu de TAIL_MED x0.92)
- Factor augmenté de +0.10 grâce à stable detection

---

## 📈 MÉTRIQUES À TRACKER

### Quotidien (Première Semaine)

**1. Fréquence Activation Guard**
- Comptez combien de fois par jour le guard apparaît dans logs
- **Cible** : 30-50% des décisions SMB en UAM

**2. Distribution Stages**
```
PRE_ONSET:  ~10-15%  (rare, juste après SMB)
RISING:     ~25-30%  (fréquent, 0-peak)
PEAK:       ~20-25%  (fréquent, autour du pic)
TAIL_HIGH:  ~15-20%  (après pic)
TAIL_MED:   ~10-15%  (fin de queue)
EXHAUSTED:  ~10-15%  (pas de restriction)
```

**3. Réduction SMB Moyenne**
- Calculez moyenne de SMB avant/après guard
- **Cible** : Réduction 20-40% en moyenne (factor moyen ~0.65-0.75)

**4. Hypoglycémies Post-UAM**
- Comptez hypoglycémies (<70 mg/dL) 2-4h après UAM
- **Cible** : Réduction 50-70% vs ancien comportement

### Hebdomadaire

**5. Time in Range (TIR)**
- Comparez TIR semaine avant/après déploiement
- **Cible** : Maintien ou amélioration TIR

**6. Incidents Hyper Prolongés**
- Comptez hypers >200 mg/dL durant >2h
- **Cible** : Pas d'augmentation vs baseline

---

## 🔧 AJUSTEMENTS POSSIBLES

### Si Surcorrection Persiste

**Symptôme** : Hypoglycémies toujours présentes après UAM

**Actions** :
1. Vérifier que guard est bien actif (logs présents)
2. Si oui, réduire factors dans `PkpdAbsorptionGuard.kt` :
   ```kotlin
   RISING:  0.6 → 0.5
   PEAK:    0.7 → 0.6
   ```
3. Augmenter intervalAddMin :
   ```kotlin
   RISING:  +3min → +4min
   PEAK:    +2min → +3min
   ```

### Si Hypers Prolongées

**Symptôme** : BG reste élevé trop longtemps après UAM

**Actions** :
1. Vérifier si urgency relaxation fonctionne (logs `_URGENCY_RELAXED`)
2. Si non activée assez, ajuster seuils dans `PkpdAbsorptionGuard.kt` :
   ```kotlin
   // Ligne ~101
   val isUrgency = bg > targetBg + 80 && delta > 5.0 && (predBg ?: bg) > bg + 30
   // → Changer à:
   val isUrgency = bg > targetBg + 60 && delta > 4.0 && (predBg ?: bg) > bg + 20
   ```
3. Augmenter boost urgency :
   ```kotlin
   // Ligne ~104
   val relaxedFactor = (baseGuard.factor + 0.25).coerceAtMost(1.0)
   // → Changer à:
   val relaxedFactor = (baseGuard.factor + 0.30).coerceAtMost(1.0)
   ```

### Si Modes Repas Affectés

**Symptôme** : Prebolus réduits ou TBR modes bridés

**Actions** :
1. Vérifier logs : Guard doit être `PKPD_ABSENT_OR_MEAL_MODE`
2. Si guard actif pendant mode repas, ajouter mode manquant :
   ```kotlin
   // Ligne ~5333 DetermineBasalAIMI2.kt
   val anyMealModeForGuard = mealTime || bfastTime || lunchTime || 
                             dinnerTime || highCarbTime || snackTime ||
                             nouveauMode  // ← Ajouter ici
   ```

---

## 📋 CHECKLIST POST-DÉPLOIEMENT

### Jour 1
- [ ] Vérifier que logs `PKPD_GUARD` apparaissent
- [ ] Observer premier UAM : guard actif ?
- [ ] Observer mode repas : guard inactif ?
- [ ] Observer urgence (si BG > 250) : relaxation active ?

### Semaine 1
- [ ] Compter activations guard par jour
- [ ] Noter distribution stages (PRE_ONSET, RISING, PEAK, TAIL)
- [ ] Tracker hypoglycémies post-UAM
- [ ] Comparer TIR vs semaine précédente

### Mois 1
- [ ] Calculer réduction moyenne SMB
- [ ] Analyser incidents (hypo ET hyper)
- [ ] Décider si ajustements nécessaires
- [ ] Documenter learnings pour tune factors

---

## 🚨 ALERTES CRITIQUES

### ALERTE 1 : Guard Jamais Actif
**Symptôme** : Aucun log `PKPD_GUARD` sur 24h

**Causes Possibles** :
1. PKPD runtime null (check logs `PKPD_LEARNER`)
2. Toujours en mode repas (check `anyMealModeForGuard`)
3. Code non déployé (rebuild nécessaire)

**Action** : Debugging urgent

### ALERTE 2 : Hypo Sévères Augmentées
**Symptôme** : BG < 55 mg/dL plusieurs fois/jour

**Causes Possibles** :
1. Factors trop restrictifs (double damping ?)
2. Cumul avec autre safety (vérifier interactions)

**Action** : Rollback temporaire + analyse

### ALERTE 3 : Hypers Chroniques
**Symptôme** : BG > 200 durant >4h régulièrement

**Causes Possibles** :
1. Urgency relaxation insuffisante
2. Meal modes bloqués par erreur
3. Seuils trop conservateurs

**Action** : Tune factors vers permissivité

---

## 📞 SUPPORT

En cas de problème critique :
1. Documenter logs complets (rT, consoleLog, consoleError)
2. Noter scénario exact (UAM ? Mode repas ? Hyper ?)
3. Fournir BG profile 4h avant/après incident
4. Vérifier PKPD runtime disponible

---

**Créé** : 2025-12-30  
**Mise à jour** : Après chaque tuning  
**Version** : 1.0
