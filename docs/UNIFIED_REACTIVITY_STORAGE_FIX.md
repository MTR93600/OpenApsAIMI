# 🛡️ UnifiedReactivityLearner - Stratégie de Stockage Robuste

**Date:** 2025-12-23  
**Objectif:** Éviter les crashs EACCES sur Android 11+ tout en gardant la cohérence avec les autres composants AIMI

---

## 📊 Problème Initial

### Symptômes
```
FileNotFoundException: /storage/emulated/0/Documents/AAPS/aimi_unified_reactivity.json
open failed: EACCES (Permission denied)
```

**Crash au démarrage** de `UnifiedReactivityLearner` lors de l'initialisation d'AIMI.

### Cause Racine
- Android 11+ bloque l'accès à `/Documents/AAPS` sans permission `MANAGE_EXTERNAL_STORAGE`
- La méthode `load()` crashait si le fichier était inaccessible
- Aucun fallback en cas d'erreur de permissions

---

## ✅ Solution Implémentée

### Stratégie Hybride en 3 Niveaux

```kotlin
private fun getStorageDirectory(): File {
    // 1️⃣ PRÉFÉRÉ: Documents/AAPS (cohérence avec autres AIMI components)
    try {
        val docsDir = File(Environment.getExternalStorageDirectory(), "Documents/AAPS")
        if (docsDir.exists() && docsDir.canWrite()) {
            return docsDir  // ✅ Permissions OK
        }
    } catch (e: Exception) {
        // Permission refusée
    }
    
    // 2️⃣ FALLBACK: App-scoped storage (pas de permissions requises)
    try {
        val appDataDir = context.getExternalFilesDir(null)
        if (appDataDir != null && appDataDir.exists()) {
            return appDataDir  // ✅ Toujours accessible
        }
    } catch (e: Exception) { }
    
    // 3️⃣ DERNIER RECOURS: Stockage interne
    return context.filesDir  // ✅ Toujours disponible
}
```

### Fonction load() Robuste

```kotlin
private fun load() {
    runCatching {
        if (!file.exists()) {
            log.info("No saved state, starting with defaults (factor=1.0)")
            return
        }
        
        if (!file.canRead()) {
            log.warn("File exists but cannot be read, using defaults")
            return
        }
        
        val json = JSONObject(file.readText())
        globalFactor = json.optDouble("globalFactor", 1.0).coerceIn(0.7, 6.0)
        shortTermFactor = json.optDouble("shortTermFactor", 1.0).coerceIn(0.7, 2.0)
        // ...
        
    }.onFailure { e ->
        // ⚠️ En cas d'erreur : logger mais CONTINUER avec valeurs par défaut
        log.error("Load failed (${e.message}), using defaults")
        globalFactor = 1.0
        shortTermFactor = 1.0
    }
}
```

**Garantie** : **NE CRASHE JAMAIS**, même si:
- Permissions manquantes
- Fichier corrompu
- Stockage plein
- Erreur JSON
- Etc.

---

## 🎯 Comportement par Scénario

| Scénario | Emplacement utilisé | Commentaire |
|----------|---------------------|-------------|
| ✅ Permissions OK | `/Documents/AAPS/` | Préféré, cohérent avec autres AIMI |
| ⚠️ Permissions refusées | `/Android/data/.../files/` | Fallback app-scoped |
| ❌ Stockage externe inaccessible | `/data/data/.../files/` | Dernier recours interne |
| 🔥 Toute erreur de lecture | Valeurs par défaut | `globalFactor=1.0` |

---

## 📝 Logs de Débogage

### Cas Nominal (Documents/AAPS accessible)
```
INFO  UnifiedReactivityLearner: 📁 Using Documents/AAPS (preferred)
INFO  UnifiedReactivityLearner: State file → /storage/emulated/0/Documents/AAPS/aimi_unified_reactivity.json
INFO  UnifiedReactivityLearner: ✅ Loaded from /storage/emulated/0/Documents/AAPS/aimi_unified_reactivity.json
  → globalFactor=1.234, shortTerm=1.567
```

### Cas Fallback (Permissions manquantes)
```
WARN  UnifiedReactivityLearner: ⚠️ Documents/AAPS exists but not writable (permission issue?)
INFO  UnifiedReactivityLearner: 📁 Using app-scoped external storage (fallback)
INFO  UnifiedReactivityLearner: State file → /Android/data/info.nightscout.androidaps/files/aimi_unified_reactivity.json
INFO  UnifiedReactivityLearner: No saved state, starting with defaults (factor=1.0)
```

### Cas Erreur Lecture
```
ERROR UnifiedReactivityLearner: Load failed (FileNotFoundException: Permission denied), using defaults
INFO  UnifiedReactivityLearner:   → Attempted path: /storage/emulated/0/Documents/AAPS/aimi_unified_reactivity.json
INFO  UnifiedReactivityLearner:   → Using fallback: globalFactor=1.0, shortTerm=1.0
```

---

## 🔍 Vérifications Utilisateur

### 1. Vérifier l'emplacement utilisé
Regarder les logs au démarrage d'AAPS :
```
adb logcat | grep "UnifiedReactivityLearner"
```

### 2. Vérifier les permissions (si Documents/AAPS souhaité)
**Android 11+** :
- Paramètres → Applications → AAPS → Autorisations
- "Accès spécial" → "Tous les fichiers"
- Activer pour AAPS

**OU** configurer le répertoire AAPS :
- AAPS → Maintenance → "AAPS Directory"
- Vérifier que pointe vers `Documents/AAPS`

### 3. Migration des données (si nécessaire)
Si l'utilisateur avait des données dans Documents/AAPS mais les permissions sont refusées :

1. Copier manuellement :
```bash
adb pull /sdcard/Documents/AAPS/aimi_unified_reactivity.json
adb push aimi_unified_reactivity.json /sdcard/Android/data/info.nightscout.androidaps/files/
```

2. Ou donner les permissions et redémarrer AAPS

---

## ⚙️ Autres Composants à Migrer (TODO futur)

Les composants suivants utilisent ENCORE `/Documents/AAPS` sans fallback :
- ❌ `AimiSmbComparator.kt`
- ❌ `PkPdCsvLogger.kt`
- ❌ `WCycleCsvLogger.kt`
- ❌ `WCycleLearner.kt`
- ❌ `BasalLearner.kt`
- ❌ `AimiModelHandler.kt`
- ❌ `DetermineBasalAIMI2.kt`

**Ils peuvent crasher** si permissions manquantes !

**Plan futur** : Appliquer la même stratégie hybride à tous ces composants.

---

## 🎓 Leçons Apprises

### ✅ Bonnes Pratiques
1. **Toujours utiliser `runCatching`** pour les opérations fichiers
2. **Toujours avoir un fallback** pour le stockage
3. **Logger clairement** le chemin utilisé
4. **Jamais crasher au démarrage** - utiliser valeurs par défaut

### ⚠️ À Éviter
1. ❌ Utiliser `Environment.getExternalStorageDirectory()` directement
2. ❌ Supposer que `mkdirs()` réussit toujours
3. ❌ Ne pas tester `canWrite()` avant d'écrire
4. ❌ Bloquer l'initialisation sur un fichier potentiellement inaccessible

---

## 📚 Références

- [Android Storage Best Practices](https://developer.android.com/training/data-storage)
- [Scoped Storage (Android 11+)](https://developer.android.com/about/versions/11/privacy/storage)
- [App-specific storage](https://developer.android.com/training/data-storage/app-specific)
