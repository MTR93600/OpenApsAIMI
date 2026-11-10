# 🔍 Analyse: Visibilité des données UnifiedReactivityLearner dans rT

**Date:** 2025-12-24  
**Problème:** Les fichiers rT des learners ne sont pas visibles dans Documents/AAPS

---

## 📊 État Actuel

### ✅ Ce qui fonctionne

1. **`UnifiedReactivityLearner` utilise `AimiStorageHelper`** ✓
   - Fichier JSON: `aimi_unified_reactivity.json`
   - Fichier CSV: `aimi_reactivity_analysis.csv`
   - Gestion robuste des permissions (fallback)
   
2. **Sauvegarde de l'état** ✓
   ```kotlin
   private fun save() {
       val json = JSONObject()
       json.put("globalFactor", globalFactor)
       json.put("shortTermFactor", shortTermFactor)
       json.put("lastAnalysisTime", lastAnalysisTime)
       json.put("lastShortAnalysisTime", lastShortAnalysisTime)
       storageHelper.saveFileSafe(file, json.toString())
   }
   ```

3. **Export CSV des analyses** ✓
   ```kotlin
   private fun exportToCSV(perf: GlycemicPerformance, reasonsStr: String) {
       // Exporte: Timestamp, TIR, CV%, Hypo_Count, GlobalFactor, etc.
       FileWriter(csvFile, true).use { it.append(line) }
   }
   ```

4. **Snapshot d'analyse disponible** ✓
   ```kotlin
   data class AnalysisSnapshot(
       val timestamp: Long,
       val tir70_180: Double,
       val cv_percent: Double,
       val hypo_count: Int,
       val globalFactor: Double,
       val shortTermFactor: Double,
       val previousFactor: Double,
       val adjustmentReason: String
   )
   
   var lastAnalysis: AnalysisSnapshot? = null
   ```

### ❌ Ce qui manque

**Les données du learner ne sont PAS exposées dans le rT (Returned Treatment)** ❌

#### Conséquences
1. Les fichiers JSON/CSV sont bien sauvegardés dans Documents/AAPS
2. MAIS les données du learner **ne sont pas disponibles dans le rT** qui est  retourné par `determine_basal`
3. Le rT est ce qui est sauvegardé dans la base de données et visible dans l'historique AAPS
4. **Les utilisateurs ne peuvent pas voir l'état du learner dans Documents/AAPS via les rT**

---

## 🔎 Analyse du Code

### Flux de données actuel

1. **`DetermineBasalAIMI2.determine_basal()` appelle:**
   ```kotlin
   unifiedReactivityLearner.processIfNeeded()  // Ligne 5939
   ```

2. **`processIfNeeded()` met à jour:**
   - `globalFactor`
   - `shortTermFactor`
   - `lastAnalysis` (snapshot)
   - Sauvegarde JSON + CSV

3. **Le rT est retourné SANS ces données**
   - Le rT contient: `insulinReq`, `rate`, `duration`, `reason`, etc.
   - Le rT NE contient PAS: `lastAnalysis` du learner

### Pourquoi c'est problématique

Les utilisateurs s'attendent à voir:
- ✓ Le fichier `aimi_unified_reactivity.json` ✓ (sauvegardé)
- ✓ Le fichier `aimi_reactivity_analysis.csv` ✓ (sauvegardé)
- ❌ **Les données du learner dans les rT stockés dans Documents/AAPS** ❌ (MANQUANT)

---

## 💡 Solution Proposée

### Option 1: Ajouter au `reason` du rT (Simple)

Avantages:
- ✅ Pas besoin de modifier la structure RT
- ✅ Immédiatement visible dans les logs

Inconvénients:
- ❌ Les données sont noyées dans le texte
- ❌ Difficile à parser programmatiquement

```kotlin
// Dans DetermineBasalAIMI2.determine_basal(), après ligne 5939:
unifiedReactivityLearner.processIfNeeded()

// Ajouter au rT.reason:
unifiedReactivityLearner.lastAnalysis?.let { analysis ->
    rT.reason.append("\n📊 Reactivity: ")
    rT.reason.append("Factor=${"%3f".format(analysis.globalFactor)} ")
    rT.reason.append("TIR=${analysis.tir70_180.toInt()}% ")
    rT.reason.append("CV=${analysis.cv_percent.toInt()}% ")
    rT.reason.append("Hypo=${analysis.hypo_count}")
}
```

### Option 2: Ajouter au `consoleLog` du rT (Recommandé) ✅

Avantages:
- ✅ Structure dédiée pour les logs
- ✅ Facile à parser
- ✅ Pas de modification de la structure RT

Inconvénients:
- ⚠️ Nécessite que consoleLog soit initialisé

```kotlin
// Dans DetermineBasalAIMI2.determine_basal(), après ligne 5939:
unifiedReactivityLearner.processIfNeeded()

// Ajouter au consoleLog:
unifiedReactivityLearner.lastAnalysis?.let { analysis ->
    consoleLog.add("📊 REACTIVITY_LEARNER:")
    consoleLog.add("  - globalFactor: ${"%.3f".format(analysis.globalFactor)}")
    consoleLog.add("  - shortTermFactor: ${"%.3f".format(analysis.shortTermFactor)}")
    consoleLog.add("  - TIR 70-180: ${analysis.tir70_180.toInt()}%")
    consoleLog.add("  - CV%: ${analysis.cv_percent.toInt()}%")
    consoleLog.add("  - Hypo count (24h): ${analysis.hypo_count}")
    consoleLog.add("  - Reason: ${analysis.adjustmentReason}")
    consoleLog.add("  - Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(analysis.timestamp))}")
}
```

### Option 3: Étendre la structure RT (Complexe)

Avantages:
- ✅ Données structurées
- ✅ Type-safe

Inconvénients:
- ❌ Nécessite modification de l'interface RT
- ❌ Impact sur toute la codebase
- ❌ Complexité élevée

---

## 🎯 Recommandation

### Implémentation Immédiate: Option 2 (consoleLog)

**Avantages:**
1. Immédiatement disponible dans les rT
2. Visible dans Documents/AAPS (si rT sont sauvegardés là)
3. Pas de modification de structure
4. Cohérent avec le reste du code

### Implémentation Complémentaire

**Pour BasalLearner:**
- Ajouter aussi ses données au consoleLog
- Format: 
  ```
  📊 BASAL_LEARNER:
    - shortTermMultiplier: X.XX
    - mediumTermMultiplier: X.XX
    - longTermMultiplier: X.XX
    - combinedMultiplier: X.XX
  ```

**Pour WCycleLearner:**
- Ajouter phase et multipliers appris
- Format:
  ```
  📊 WCYCLE_LEARNER:
    - phase: FOLLICULAR
    - learnedBasalMultiplier: X.XX
    - learnedSmbMultiplier: X.XX
  ```

---

## 📝 Fichiers à Modifier

### 1. `DetermineBasalAIMI2.kt`

**Ligne ~5940** (après `unifiedReactivityLearner.processIfNeeded()`):

```kotlin
// 🎯 Process UnifiedReactivityLearner (old learner removed)
unifiedReactivityLearner.processIfNeeded()

// 📊 NOUVEAU: Expose learner state in rT for visibility
unifiedReactivityLearner.lastAnalysis?.let { analysis ->
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    consoleLog.add("📊 REACTIVITY_LEARNER:")
    consoleLog.add("  │ globalFactor: ${"%.3f".format(analysis.globalFactor)}")
    consoleLog.add("  │ shortTermFactor: ${"%.3f".format(analysis.shortTermFactor)}")
    consoleLog.add("  │ TIR 70-180: ${analysis.tir70_180.toInt()}%")
    consoleLog.add("  │ CV%: ${analysis.cv_percent.toInt()}%")
    consoleLog.add("  │ Hypo count (24h): ${analysis.hypo_count}")
    consoleLog.add("  │ Reason: ${analysis.adjustmentReason}")
    consoleLog.add("  └ Analyzed at: ${sdf.format(Date(analysis.timestamp))}")
}
```

**Ligne ~5935** (après `basalLearner.process()`):

```kotlin
basalLearner.process(
    currentBg = bg,
    currentDelta = delta.toDouble(),
    tdd7Days = tdd7Days,
    tdd30Days = tdd7Days,
    isFastingTime = isNight && !anyMealActive
)

// 📊 NOUVEAU: Expose BasalLearner state
consoleLog.add("📊 BASAL_LEARNER:")
consoleLog.add("  │ shortTerm: ${"%.3f".format(basalLearner.shortTermMultiplier)}")
consoleLog.add("  │ mediumTerm: ${"%.3f".format(basalLearner.mediumTermMultiplier)}")
consoleLog.add("  │ longTerm: ${"%.3f".format(basalLearner.longTermMultiplier)}")
consoleLog.add("  └ combined: ${"%.3f".format(basalLearner.getMultiplier())}")
```

### 2. `BasalLearner.kt`

**Ajouter des propriétés publiques** pour exposer les multipliers:

```kotlin
// Actuel (privé):
private var shortTermMultiplier = 1.0
private var mediumTermMultiplier = 1.0
private var longTermMultiplier = 1.0

// Nouveau (public read-only):
var shortTermMultiplier = 1.0
    private set
var mediumTermMultiplier = 1.0
    private set
var longTermMultiplier = 1.0
    private set
```

---

## 🧪 Tests de Validation

### 1. Vérifier la présence dans consoleLog

```bash
adb logcat | grep "REACTIVITY_LEARNER"
adb logcat | grep "BASAL_LEARNER"
```

### 2. Vérifier les fichiers sauvegardés

```bash
adb shell ls -la /sdcard/Documents/AAPS/aimi_*.json
adb shell ls -la /sdcard/Documents/AAPS/aimi_*.csv
```

### 3. Vérifier le rT

Depuis AAPS → OpenAPS → voir le JSON du dernier rT retourné:
- Chercher `"consoleLog"` dans le JSON
- Vérifier la présence de `"📊 REACTIVITY_LEARNER"`

---

## ✅ Checklist d'Implémentation

- [ ] Modifier `DetermineBasalAIMI2.kt` ligne ~5940 (UnifiedReactivityLearner)
- [ ] Modifier `DetermineBasalAIMI2.kt` ligne ~5935 (BasalLearner)
- [ ] Ajouter propriétés publiques dans `BasalLearner.kt`
- [ ] Tester build
- [ ] Vérifier logs dans logcat
- [ ] Vérifier consoleLog dans rT JSON
- [ ] Documenter dans CHANGELOG

---

## 📚 Références

- `UnifiedReactivityLearner.kt`: Lines 47-60 (AnalysisSnapshot)
- `DetermineBasalAIMI2.kt`: Line 5939 (processIfNeeded call)
- `RT.kt`: Line 49 (consoleLog field)
- `AimiStorageHelper.kt`: Gestion robuste du stockage

---

**Conclusion:** Les fichiers sont bien sauvegardés, mais les données ne sont pas exposées dans le rT. L'ajout au `consoleLog` est la solution la plus simple et la plus cohérente.
