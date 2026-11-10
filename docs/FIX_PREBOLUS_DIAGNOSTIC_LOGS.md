# FIX BLOCAGE PREBOLUS — DIAGNOSTIC & LOGS AJOUTÉS

**Date:** 2025-12-18 21:05  
**Problème:** P1/P2 ne partent pas pour modes repas  
**Solution:** Logs de diagnostic ajoutés pour identifier le blocage exact

---

## 🔍 CE QUI A ÉTÉ AJOUTÉ

### **1. Log d'entrée dans tryManualModes (ligne 5901)**
```kotlin
consoleLog.add("🔍 MODES_DETECT dinner=${dinnerruntime} lunch=${lunchruntime} bfast=${bfastruntime} meal=${mealruntime} snack=${snackrunTime} hc=${highCarbrunTime}")
```

**But:** Voir si `tryManualModes` est appelé et quelle est la valeur des runtimes

---

### **2. Log de détection mode (ligne 5946)**
```kotlin
if (activeName.isEmpty()) {
    consoleLog.add("❌ MODES_DETECT No active mode detected → Fallthrough")
    return DecisionResult.Fallthrough("No Active Mode")
} else {
    consoleLog.add("✅ MODES_DETECT Active: $activeName runtime=${activeRuntimeMin}m pre1=${pre1Config} pre2=${pre2Config}")
}
```

**But:** Voir si le mode est détecté ET si la config prebolus est chargée

---

### **3. Log refractory bypass (déjà ajouté ligne 1476)**
```kotlin
if (refractoryBlocked) {
    consoleLog.add("⏸️ REFRACTORY_BLOCK ...")
} else if (sinceBolus < refractoryWindow && isExplicitUserAction) {
    consoleLog.add("✅ REFRACTORY_BYPASS ...")
}
```

---

## 📊 LOGS ATTENDUS (Après Rebuild)

### **Scénario 1: Mode Détecté & Prebolus Envoyé** ✅
```
🔍 MODES_DETECT dinner=5 lunch=-1 bfast=-1 meal=-1 snack=-1 hc=-1
✅ MODES_DETECT Active: Dinner runtime=5m pre1=6.0 pre2=2.0
MODE_DEBUG mode=Dinner rt=5 state.pre1=false p1Cfg=6.0 p2Cfg=2.0
MODE_DEBUG_P1 entered=true basePre1=6.0
MODE_DEBUG_P1 decision=SEND bolus=6.0 factor=1.0
✅ REFRACTORY_BYPASS sinceBolus=2.0m window=5.0m (Meal mode override)
MODE_ACTIVE source=ManualMode_Dinner bolus=6.0
🍱 MODE_ACTIVE mode=Dinner phase=P1 bolus=6.00 tbr=12.00 reason=Normal (meal mode active)
```

---

### **Scénario 2: Mode NON Détecté** ❌
```
🔍 MODES_DETECT dinner=-1 lunch=-1 bfast=-1 meal=-1 snack=-1 hc=-1
❌ MODES_DETECT No active mode detected → Fallthrough
```

**→ CAUSE:** `dinnerruntime = -1` signifie que `therapy.getTimeElapsedSinceLastEvent("dinner")` retourne -1  
**→ FIX:** Vérifier événement therapy "dinner" créé correctement

---

### **Scénario 3: Mode Détecté MAIS Config = 0** ❌
```
🔍 MODES_DETECT dinner=5 lunch=-1 bfast=-1 meal=-1 snack=-1 hc=-1
✅ MODES_DETECT Active: Dinner runtime=5m pre1=0.0 pre2=0.0
MODE_DEBUG mode=Dinner rt=5 state.pre1=false p1Cfg=0.0
MODE_DEBUG_P1 decision=SKIP reason=basePre1_is_zero
```

**→ CAUSE:** `OApsAIMIDinnerPrebolus` = 0 dans les préférences  
**→ FIX:** Configurer prebolus dans les settings

---

### **Scénario 4: État Persisté** ❌
```
🔍 MODES_DETECT dinner=5 lunch=-1 bfast=-1 meal=-1 snack=-1 hc=-1
✅ MODES_DETECT Active: Dinner runtime=5m pre1=6.0 pre2=2.0
MODE_DEBUG mode=Dinner rt=5 state.pre1=true p1Cfg=6.0 p2Cfg=2.0
MODE_DEBUG_P1 entered=false pre1=true rt=5
```

**→ CAUSE:** `state.pre1 = true` d' activation précédente  
**→ FIX:** Reset state si nouveau mode ou gap >5min

---

### **Scénario 5: Refractory Block (BUG)** ❌
```
🔍 MODES_DETECT dinner=5 lunch=-1 bfast=-1 meal=-1 snack=-1 hc=-1
✅ MODES_DETECT Active: Dinner runtime=5m pre1=6.0 pre2=2.0
MODE_DEBUG_P1 entered=true basePre1=6.0
MODE_DEBUG_P1 decision=SEND bolus=6.0 factor=1.0
⏸️ REFRACTORY_BLOCK sinceBolus=2.0m window=5.0m (SMB blocked)
MODE_ACTIVE source=ManualMode_Dinner bolus=0.0
```

**→ CAUSE:** Bug, `isExplicitUserAction` pas respecté  
**→ FIX:** Vérifier que `isExplicitUserAction=true` est passé

---

## 🎯 ACTIONS REQUISES

### **Étape 1: Rebuilder l'App**
```bash
./gradlew :app:assembleFullDebug
```

### **Étape 2: Activer Mode Dinner**
1. Créer événement "Dinner" dans AAPS
2. Attendre 2-5 minutes
3. Laisser loop tourner

### **Étape 3: Collecter Logs**
Chercher dans les logs :
- `🔍 MODES_DETECT` → Voir runtimes
- `✅ MODES_DETECT` ou `❌ MODES_DETECT` → Voir détection
- `MODE_DEBUG` → Voir état/config
- `MODE_DEBUG_P1` → Voir décision P1
- `REFRACTORY` → Voir si bloqué

### **Étape 4: Partager Résultat**
Envoyer les 30 premières lignes contenant un de ces mots :
- MODES_DETECT
- MODE_DEBUG
- MODE_ACTIVE
- REFRACTORY

---

## 🔧 HYPOTHÈSES & FIXES POTENTIELS

### **Hypothèse #1: dinnerruntime = -1**
**Cause:** Événement therapy pas créé  
**Fix:** Vérifier dans UI AAPS que l'événement "Dinner" existe  
**Vérification:** Log `🔍 MODES_DETECT dinner=-1`

### **Hypothèse #2: pre1Config = 0**
**Cause:** Preference non configurée  
**Fix:** Settings AAPS → AIMI → Mode Dinner → Prebolus 1 > 0  
**Vérification:** Log `pre1=0.0`

### **Hypothèse #3: state.pre1 = true**
**Cause:** État persisté  
**Fix:** Code à ajouter - reset state si gap > 5min  
**Vérification:** Log `state.pre1=true`

### **Hypothèse #4: tryManualModes jamais appelé**
**Cause:** Crash avant ligne 4095  
**Fix:** Check logs pour erreur/crash  
**Vérification:** Absence complète de `🔍 MODES_DETECT`

---

## ✅ BUILD STATUS

```bash
BUILD SUCCESSFUL in 7s
```

**Erreurs:** 0 ✅  
**Warnings:** 1 (unchecked cast, pre-existant)

---

## 🚀 PROCHAINE ÉTAPE

**Avec ces nouveaux logs, on va ENFIN savoir exactement pourquoi le prebolus ne part pas !**

Rebuilder → Tester → Partager les logs → Je corrigerai précisément le bug identifié. 🔍
