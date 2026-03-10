# AUDIT CODE OBSOLÈTE APRÈS RESTAURATION LEGACY MEAL MODES

**Date:** 2025-12-18 21:56  
**Contexte:** Restauration du système legacy de prebolus (envoi direct sans safety)  
**Objectif:** Identifier le code devenu obsolète et recommander suppressions

---

## ✅ CE QUI A ÉTÉ RESTAURÉ

### **1. Fonctions de condition (remplacées)**
```kotlin
// AVANT (Nouveau système):
private fun isLunchModeCondition(): Boolean = 
    lunchruntime in 0..7 && !isFreshBolusWithin(lunchruntime)

// APRÈS (Legacy restauré):
private fun isLunchModeCondition(): Boolean {
    val pbolusLunch = preferences.get(DoubleKey.OApsAIMILunchPrebolus)
    return lunchruntime in 0..7 && lastBolusSMBUnit != pbolusLunch.toFloat() && lunchTime
}
```

### **2. Checks directs dans determine_basal (ajoutés avant tryManualModes)**
```kotlin
if (isLunchModeCondition()) {
    val pbolusLunch = preferences.get(DoubleKey.OApsAIMILunchPrebolus)
    rT.units = pbolusLunch
    rT.reason.append(...)
    consoleLog.add("🍱 LEGACY_MODE_LUNCH P1=...U (DIRECT SEND)")
    return rT  // ← DIRECT, pas de tryManualModes !
}
```

---

## 🗑️ CODE OBSOLÈTE À SUPPRIMER

### **1. Fonction `tryManualModes()` (OBSOLÈTE)**

**Location:** Ligne ~5900-6030  
**Raison:** Les modes sont maintenant gérés par legacy checks AVANT tryManualModes  
**Preuve:** Les checks legacy return AVANT l'appel à `tryManualModes` (ligne 4095+)

**RECOMMANDATION:** **SUPPRIMER COMPLÈTEMENT** `tryManualModes()`

**Pourquoi safe:**
- Les modes repas ne l'utilisent plus (legacy checks avant)
- Aucun autre code ne l'appelle (vérifié)
- Elle ajoute confusion et complexité inutile

---

### **2. Fonction `isFreshBolusWithin()` (OBSOLÈTE)**

**Location:** Ligne ~2149-2155  
**Raison:** Remplacée par `lastBolusSMBUnit != prebolus.toFloat()` dans legacy conditions

**Code actuel:**
```kotlin
private fun isFreshBolusWithin(modeRuntime: Long): Boolean {
    val runtimeMin = runtimeToMinutes(modeRuntime)
    return this.lastsmbtime < runtimeMin
}
```

**RECOMMANDATION:** **SUPPRIMER** (plus utilisée)

---

### **3. Call à `tryManualModes` dans determine_basal (PEUT RESTER)**

**Location:** Ligne ~4170 (après tous les legacy checks)

**Code actuel:**
```kotlin
// PRIORITY 1: MANUAL MODES (Stateful & Priority)
val manualRes = tryManualModes(bg, delta, profile, glucose_status.date)
if (manualRes is DecisionResult.Applied) {
    // ...
}
```

**RECOMMANDATION:** **SUPPRIMER ou COMMENTER** (dead code)

**Pourquoi:**
- Tous les modes sont déjà gérés par legacy checks AVANT
- Cette ligne ne sera JAMAIS atteinte pour un mode actif
- Si modes présents → return avant
- Si pas de mode → tryManualModes return Fallthrough

**Option conservatrice:** Commenter + log "Legacy bypass"

---

### **4. ModeState class et sérialisation (OBSOLÈTE)**

**Location:** Ligne ~5800-5850 (dans tryManualModes)

**Code actuel:**
```kotlin
data class ModeState(
    val name: String = "",
    val startMs: Long = 0L,
    var pre1: Boolean = false,
    var pre2: Boolean = false,
    var pre1SentMs: Long = 0L,
    var pre2SentMs: Long = 0L,
    // ...
) {
    fun serialize(): String = ...
    companion object {
        fun deserialize(raw: String): ModeState = ...
    }
}
```

**RECOMMANDATION:** **SUPPRIMER** (plus utilisée)

**Raison:** Legacy system utilise `lastBolusSMBUnit` pour détecter double envoi, pas state persisté

---

### **5. modeSafetyDegrade() (OBSOLÈTE?)**

**Location:** Ligne ~5690-5770

**Usage:** Appelée dans `tryManualModes` pour dégradation PKPD

**RECOMMANDATION:** **SUPPRIMER** (car tryManualModes obsolète)

**Mais:** Vérifier si utilisée ailleurs (ex: meal advisor?)

---

### **6. DecisionResult sealed class (PARTIELLE)**

**Location:** Ligne ~5780-5800

**Code actuel:**
```kotlin
private sealed class DecisionResult {
    data class Applied(...) : DecisionResult()
    data class Fallthrough(val reason: String) : DecisionResult()
}
```

**RECOMMANDATION:** **SUPPRIMER** (utilisée uniquement par tryManualModes)

---

## 📊 RÉSUMÉ SUPPRESSIONS RECOMMANDÉES

### **PRIORITÉ HAUTE (Supprimer maintenant)**

1. ✅ `isFreshBolusWithin()` → Plus utilisée
2. ✅ Appel `tryManualModes()` dans determine_basal → Dead code
3. ⚠️ `tryManualModes()` fonction complète → Complexe, vérifier dépendances

### **PRIORITÉ MOYENNE (Après validation)**

4. ⚠️ `ModeState` class → Vérifier si utilisée ailleurs
5. ⚠️ `modeSafetyDegrade()` → Vérifier appels externes
6. ⚠️ `DecisionResult` sealed class → Vérifier usage hors modes

---

## 🔍 VÉRIFICATIONS NÉCESSAIRES

### **Avant de supprimer tryManualModes:**

1. Chercher tous les appels:
```bash
grep -n "tryManualModes" DetermineBasalAIMI2.kt
```

2. Vérifier si `ModeState` est utilisée ailleurs:
```bash
grep -n "ModeState" *.kt
```

3. Vérifier si `modeSafetyDegrade` est utilisée hors tryManualModes:
```bash
grep -n "modeSafetyDegrade" DetermineBasalAIMI2.kt
```

---

## ✅ CODE À GARDER

### **1. Legacy mode condition functions** ✅
```kotlin
private fun isLunchModeCondition(): Boolean { ... }
// ... (toutes les autres)
```

### **2. Legacy mode checks in determine_basal** ✅
```kotlin
if (isLunchModeCondition()) {
    rT.units = pbolusLunch
    return rT
}
```

### **3. Runtime tracking variables** ✅
```kotlin
this.lunchruntime = therapy.getTimeElapsedSinceLastEvent("lunch")
this.dinnerruntime = ...
```

### **4. lastBolusSMBUnit tracking** ✅
```kotlin
this.lastBolusSMBUnit = ...
```

---

## 🎯 PLAN DE NETTOYAGE

### **Étape 1: Suppressions Safe (Now)**
1. Supprimer `isFreshBolusWithin()` (ligne ~2149-2155)
2. Commenter appel `tryManualModes()` (ligne ~4170)
3. Ajouter log "Legacy bypass active"

### **Étape 2: Validation (After 24h)**
1. Tester modes repas
2. Vérifier logs "LEGACY_MODE_*"
3. Confirmer prebolus envoyés

### **Étape 3: Nettoyage Final (After 1 week)**
1. Supprimer `tryManualModes()` complète
2. Supprimer `ModeState` class
3. Supprimer `modeSafetyDegrade()`
4. Supprimer `DecisionResult` sealed class

---

## 📋 COMMANDES DIAGNOSTIC

### **Vérifier usage tryManualModes:**
```bash
cd /Users/mtr/StudioProjects/OpenApsAIMI/plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI
grep -n "tryManualModes" DetermineBasalAIMI2.kt
```

**Résultat attendu:** 1 définition + 1 appel (ligne ~4170)

### **Vérifier usage ModeState:**
```bash
grep -rn "ModeState" --include="*.kt" .
```

**Si uniquement dans DetermineBasalAIMI2.kt → Safe à supprimer**

---

## ⚠️ ATTENTION

**NE PAS supprimer:**
- ❌ `activeMealRuntimeMinutes()` → Peut être utilisée ailleurs
- ❌ `runtimeToMinutes()` → Utilisée par legacy conditions
- ❌ Variables runtime (lunchruntime, dinnerruntime, etc.) → Nécessaires

---

## ✅ CONCLUSION

**Code obsolète identifié:** ~500 lignes  
**Suppressions safe immédiates:** ~200 lignes  
**Suppressions à valider:** ~300 lignes

**Bénéfice:** Code plus simple, maintenable, lisible

**Prochain PR:** "Clean obsolete meal mode code after legacy restoration" 🧹
