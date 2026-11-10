# 🛡️ AIMI Storage Security - État des lieux

**Date:** 2025-12-23  
**Status:** ✅ PARTIELLEMENT COMPLÉTÉ (2/7 composants sécurisés)

---

##  ✅ Composants Sécurisés (avec AimiStorageHelper)

| Composant | Fichier | Status | Notes |
|-----------|---------|--------|-------|
| ✅ **UnifiedReactivityLearner** | `learning/UnifiedReactivityLearner.kt` | ✅ SÉCURISÉ | Utilise AimiStorageHelper |
| ✅ **BasalLearner** | `learning/BasalLearner.kt` | ✅ SÉCURISÉ | Utilise AimiStorageHelper |

---

## ⚠️ Composants NON sécurisés (TODO)

| Composant | Fichier | Status | Risque |
|-----------|---------|--------|--------|
| ❌ **WCycleLearner** | `wcycle/WCycleLearner.kt` | ⚠️ VULNÉRABLE | Crash EACCES possible |
| ❌ **WCycleCsvLogger** | `wcycle/WCycleCsvLogger.kt` | ⚠️ VULNÉRABLE | Crash EACCES possible |
| ❌ **PkPdCsvLogger** | `pkpd/PkPdCsvLogger.kt` | ⚠️ VULNÉRABLE | Crash EACCES possible |
| ❌ **AimiSmbComparator** | `comparison/AimiSmbComparator.kt` | ⚠️ VULNÉRABLE | Crash EACCES possible |
| ❌ **AimiModelHandler** | `AimiModelHandler.kt` | ⚠️ VULNÉRABLE | Crash EACCES possible |

---

## 📊 Log de Santé Stockage (À intégrer)

### Emplacement recommandé
Ajouter dans `DetermineBasalAIMI2.kt`, fonction `determine()`, au début :

```kotlin
// === 🛡️ STORAGE HEALTH CHECK ===
private fun logStorageHealth() {
    val storageReport = storageHelper.getHealthReport()
    log.info(LTag.APS, "═══════════════════════════════════════════════")
    log.info(LTag.APS, "📦 AIMI Storage Health")
    log.info(LTag.APS, "  $storageReport")
    log.info(LTag.APS, "═══════════════════════════════════════════════")
}
```

Appeler au début de `determine()` :
```kotlin
fun determine(...): DetermineBasalResultAIMI2 {
    logStorageHealth()  // 🛡️ Log storage health
    
    // ... reste du code
}
```

### Résultat attendu (logs)

**Cas nominal** (Documents/AAPS accessible) :
```
═══════════════════════════════════════════════
📦 AIMI Storage Health
  ✅ Storage: Documents/AAPS
═══════════════════════════════════════════════
```

**Cas dégradé** (fallback app-scoped) :
```
═══════════════════════════════════════════════
📦 AIMI Storage Health
  ⚠️ Storage: App-scoped (fallback) - Reason: Documents/AAPS not writable (permission issue?)
═══════════════════════════════════════════════
```

---

## 🔧 Migration TODO

### Étapes pour sécuriser les 5 composants restants

#### 1. WCycleLearner (PRIORITÉ HAUTE)
```kotlin
// AVANT
private val dir by lazy { 
    File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS")
}
private val learnedFile by lazy { File(dir, "oapsaimi_wcycle_learned.json") }

// APRÈS
@Inject lateinit var storageHelper: AimiStorageHelper
private val learnedFile by lazy { storageHelper.getAimiFile("oapsaimi_wcycle_learned.json") }
```

**Modifications nécessaires** :
- Ajouter `storageHelper: AimiStorageHelper` au constructeur
- Remplacer `dir.mkdirs()` par utilisation directe de `storageHelper`
- Modifier `persistToDisk()` pour utiliser `storageHelper.save FileSafe()`

#### 2. WCycleCsvLogger (PRIORITÉ HAUTE)
```kotlin
// AVANT
private val dir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS")

// APRÈS
@Inject lateinit var storageHelper: AimiStorageHelper
private fun getLogFile() = storageHelper.getAimiFile("oapsaimi_wcycle_log.csv")
```

#### 3. PkPdCsvLogger (PRIORITÉ MOYENNE)
```kotlin
// AVANT
private val externalDir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS")

// APRÈS
@Inject lateinit var storageHelper: AimiStorageHelper
private fun getLogFile() = storageHelper.getAimiFile("pkpd", "pkpd_log.csv")
```

#### 4. AimiSmbComparator (PRIORITÉ MOYENNE)
```kotlin
// AVANT
val dir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS")

// APRÈS
val csvFile = storageHelper.getAimiFile("aimi_smb_comparison.csv")
```

#### 5. AimiModelHandler (PRIORITÉ BASSE - ML Model)
```kotlin
// AVANT
private val externalDir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS")
val modelFile = File(externalDir, "ml/modelUAM.tflite")

// APRÈS
val modelFile = storageHelper.getAimiFile("ml", "modelUAM.tflite")
```

---

## 🎯 Bénéfices de la migration complète

### Avantages
1. ✅ **Aucun crash** même si permissions manquantes
2. ✅ **Cohérence** : un seul point de gestion du stockage
3. ✅ **Logs centralisés** pour debug
4. ✅ **Fallback automatique** Documents → app-scoped → internal
5. ✅ **Monitoring** via `getStorageStatus()` et logs de santé

### Évolution future possible
- Migration complète vers app-scoped (option 1 du plan original)
- Ajout d'un export manuel vers Documents/AAPS si l'utilisateur veut
- Synchronisation cloud des learners (Dropbox, GDrive, etc.)

---

## 📚 Documentation créée

- ✅ `AimiStorageHelper.kt` - Helper centralisé robuste
- ✅ `UNIFIED_REACTIVITY_STORAGE_FIX.md` - Documentation détaillée du problème et solution
- ✅ `AIMI_STORAGE_SECURITY_STATUS.md` - Ce fichier (état des lieux)

---

## 🚀 Prochaines étapes recommandées

1. **[PRIORITÉ 1]** Ajouter les logs de santé dans `DetermineBasalAIMI2.kt`
2. **[PRIORITÉ 2]** Migrer `WCycleLearner` et `WCycleCsvLogger` (utilisés fréquemment)
3. **[PRIORITÉ 3]** Migrer `PkPdCsvLogger` et `AimiSmbComparator`
4. **[PRIORITÉ 4]** Migrer `AimiModelHandler` (moins critique car ML model rarement écrit)
5. **[OPTIONNEL]** Ajouter notification utilisateur si fallback app-scoped utilisé

---

## ✅ Tests effectués

- [x] Compilation `UnifiedReactivityLearner` ✅
- [x] Compilation `BasalLearner` ✅
- [x] Compilation `AimiStorageHelper` ✅
- [x] Build complet du projet ✅
- [ ] Tests runtime avec permissions Documents/AAPS
- [ ] Tests runtime sans permissions (fallback app-scoped)
- [ ] Tests runtime stockage interne only

**Recommandation** : Tester sur appareil Android 11+ avec et sans permissions pour confirmer le comportement.
