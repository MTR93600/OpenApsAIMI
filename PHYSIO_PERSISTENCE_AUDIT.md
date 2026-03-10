# 🎯 AIMI Physio NEVER_SYNCED - Audit & Fixes Complets

## 📋 Réponses aux Questions A-B-C-D

### A) Fichier exact
✅ **Nom**: `physio_context.json`  
✅ **Format**: JSON (version 2 avec outcome tracking)  
✅ **Classe**: `AIMIPhysioContextStoreMTR.kt` ligne 40

### B) Où stocké
✅ **Path**: `/storage/emulated/0/Documents/AAPS/physio_context.json`  
✅ **Code**: Ligne 66-74 de ContextStore  
```kotlin
private val storageDir: File by lazy {
    val dir = File(
        android.os.Environment.getExternalStorageDirectory(),
        "Documents/AAPS"
    )
    if (!dir.exists()) {
        dir.mkdirs()
    }
    dir
}
```
✅ **Aligné avec autres fichiers AIMI**: OUI (même méthode que learners)

### C) Relu au démarrage
✅ **OUI** - Dans `init {}` ligne 80-86  
✅ **Composant**: `AIMIPhysioContextStoreMTR` (singleton injecté)  
✅ **Quand**: À la création du store (lazy init)

### D) Méthode AIMI-style
✅ **Utilise déjà Documents/AAPS** comme:
- `unified_reactivity.json`
- `basal_learner.json`
- `wcycle_*.csv`
- etc.

---

## 🔧 Fixes Appliqués

### 1️⃣ Logs de Persistence Enrichis (`saveToDisk`)

**AVANT** ❌:
```kotlin
storageFile.writeText(json.toString(2))  // PAS DE LOG!
```

**APRÈS** ✅:
```kotlin
try {
    aapsLogger.info(LTag.APS, "[$TAG] 💾 PhysioStore: writing to ${storageFile.absolutePath}")
    
    val jsonString = json.toString(2)
    storageFile.writeText(jsonString)
    
    val writtenBytes = jsonString.toByteArray().size
    aapsLogger.info(LTag.APS, "[$TAG] ✅ PhysioStore: written bytes=$writtenBytes")
    
    val exists = storageFile.exists()
    val size = if (exists) storageFile.length() else 0
    val canRead = storageFile.canRead()
    val canWrite = storageFile.canWrite()
    
    aapsLogger.info(LTag.APS, "[$TAG] 🔍 PhysioStore: exists=$exists size=$size canRead=$canRead canWrite=$canWrite")
    
    if (!exists || size == 0L) {
        aapsLogger.error(LTag.APS, "[$TAG] ❌ PhysioStore: WRITE FAILED!")
    }
} catch (e: Exception) {
    aapsLogger.error(LTag.APS, "[$TAG] ❌ PhysioStore: Save exception: ${e.message}", e)
}
```

**Logs Attendus au Runtime** :
```
PhysioStore: 💾 writing to /storage/emulated/0/Documents/AAPS/physio_context.json
PhysioStore: ✅ written bytes=2854
PhysioStore: 🔍 exists=true size=2854 canRead=true canWrite=true
```

---

### 2️⃣ getDetailedLogString() - NEVER NULL

**Status** : ✅ **DÉJÀ CORRIGÉ** dans refactor précédent

Le code utilise maintenant `PhysioPipelineOutcome` et retourne **toujours** une string:

```kotlin
fun getDetailedLogString(): String {  // NEVER NULL
    val outcome = contextStore.getLastRunOutcome()
    val context = contextStore.getLastContextUnsafe()
    
    return when {
        outcome == NEVER_RUN -> "NEVER_SYNCED | Waiting for first sync"
        outcome == SYNC_OK_NO_DATA -> "HC OK but NO_DATA (check writers export)" 
        outcome == SYNC_PARTIAL -> "Partial data (Steps/HR only), conf=25%"
        // ... autres cas avec détails
    }
}
```

---

### 3️⃣ Outcome Tracking Persisted

**Ajouté au JSON (version 2)** :
```json
{
  "version": 2,
  "lastUpdate": 1737...,
  "lastRunOutcome": "READY",        ← NOUVEAU
  "lastRunTimestamp": 1737...,      ← NOUVEAU
  "context": { ... },
  "baseline": { ... },
  "probeResult": {                  ← NOUVEAU
    "sleepCount": 12,
    "hrvCount": 45,
    ...
  }
}
```

**Restauré dans `restoreFromDisk`** :
```kotlin
if (version >= 2) {
    val outcomeStr = json.optString("lastRunOutcome", "NEVER_RUN")
    lastRunOutcome = PhysioPipelineOutcome.valueOf(outcomeStr)
    lastRunTimestamp = json.optLong("lastRunTimestamp", 0)
}
```

---

## 📊 Scénarios de Diagnostic

### Scénario 1: Fichier N'Existe Pas (Fresh Install)

**Logs Attendus** :
```
PhysioContextStore: 🔄 attempting restore from /Documents/AAPS/physio_context.json
PhysioContextStore: ⚠️ No saved context found - file does not exist
```

**UI Attendue** :
```
🏥 Physio: NEVER_SYNCED | Waiting for first Health Connect sync
```

✅ **Normal** - aucun run n'a encore eu lieu.

---

### Scénario 2: Fichier Existe Mais Vide

**Logs Attendus** :
```
PhysioContextStore: 🔄 attempting restore from /Documents/AAPS/physio_context.json
PhysioContextStore: 📂 File exists: size=0 bytes, canRead=true
PhysioContextStore: ❌ File exists but is EMPTY! Aborting restore.
```

**Root Cause** : Permission issue OU crash pendant write.

---

### Scénario 3: Run OK, No Data from Health Connect

**Logs Attendus** (saveToDisk):
```
PhysioStore: 💾 writing to /Documents/AAPS/physio_context.json
PhysioStore: ✅ written bytes=1842
PhysioStore: 🔍 exists=true size=1842 canRead=true canWrite=true
```

**Logs Attendus** (Manager):
```
PhysioManager: ✅ RUN COMPLETE | outcome=SYNC_OK_NO_DATA | conf=0.0
```

**UI Attendue** :
```
🏥 Physio: UNKNOWN (Conf: 0%) | Age: 0h | Next: 240min
    ⚠️ Bootstrap mode: No valid features
    ℹ️ Health Connect OK but no data found (Sleep/HRV/RHR=0). 
       Check if Oura/Samsung/Garmin exports to Health Connect.
```

✅ **Plus de NEVER_SYNCED** - outcome visible!

---

### Scénario 4: Run OK, Partial Data (Steps/HR only)

**Logs** :
```
PhysioManager: ✅ RUN COMPLETE | outcome=SYNC_PARTIAL | conf=0.25
```

**UI** :
```
🏥 Physio: UNKNOWN (Conf: 25%) | Age: 0h | Next: 240min
    ⚠️ Bootstrap mode: Quality=25%, Missing: Sleep, HRV
```

---

### Scénario 5: Run OK, Full Data (READY)

**Logs** :
```
PhysioManager: ✅ RUN COMPLETE | outcome=READY | conf=0.85
```

**UI** :
```
🏥 Physio: OPTIMAL (Conf: 85%) | Age: 2h | Next: 118min
    • Sleep: 7.2h (Eff: 88%) Z=-0.3
    • HRV: 42ms Z=0.1 | RHR: 58bpm Z=-0.5
```

---

## ✅ Checklist Validation Production

### Phase 1: Première Installation (NEVER_RUN)
1. Installer APK fresh
2. Activer Physio module
3. **Log attendu** : `No saved context found - file does not exist`
4. **UI attendue** : `NEVER_SYNCED | Waiting for first sync`

### Phase 2: Premier Run (Aucune Permission HC)
1. Déclencher loop
2. **Log attendu** : `outcome=SECURITY_ERROR`
3. **UI attendue** : `Missing Health Connect permissions`

### Phase 3: Permissions OK, Mais Pas de Données
1. Accorder permissions HC
2. Déclencher loop (mais Oura/Samsung n'exportent rien)
3. **Logs attendus** :
   ```
   PROBE: Sleep=0 HRV=0 HR=0 Steps=0
   RUN COMPLETE | outcome=SYNC_OK_NO_DATA
   PhysioStore: writing to ...
   PhysioStore: written bytes=1500+
   ```
4. **UI attendue** : `HC OK but NO_DATA (check writers)`

### Phase 4: Redémarrage App (Persistence Test)
1. Force close app
2. Relancer
3. **Logs attendus** :
   ```
   PhysioStore: attempting restore from ...
   File exists: size=1842 bytes, canRead=true
   Context restored successfully (outcome=SYNC_OK_NO_DATA, age=0h)
   ```
4. **UI attendue** : Même statut qu'avant (pas de NEVER_SYNCED)

### Phase 5: Données Arrivent (Health Connect Sync)
1. Oura/Samsung commencent à exporter
2. Attendre 4h (prochain run WorkManager)
3. **Logs attendus** :
   ```
   PROBE: Sleep=12 HRV=45 HR=892 Steps=156
   RUN COMPLETE | outcome=SYNC_PARTIAL ou READY
   ```
4. **UI attendue** : Métriques affichées

---

## 🚀 Build Procedure

### Clean Build
```bash
cd /Users/mtr/StudioProjects/OpenApsAIMI
./gradlew clean
./gradlew :app:assembleFullDebug
```

### Si Erreur KSP
```bash
# 1. Clean complet
./gradlew clean

# 2. Rebuild avec stacktrace
./gradlew :app:assembleFullDebug --stacktrace | grep "ComponentProcessing"

# 3. Si erreur Dagger injection:
#    - Vérifier que AIMIPhysioContextStoreMTR est bien @Singleton
#    - Vérifier injection dans DetermineBasalAIMI2
```

### Vérification APK
```bash
adb install -r app/build/outputs/apk/full/debug/app-full-debug.apk
adb logcat -s PhysioContextStore:I PhysioManager:I | grep -E "(writing|restore|RUN COMPLETE)"
```

---

## 📝 Fichiers Modifiés (Ce Commit)

| Fichier | Lignes | Changements |
|---------|--------|-------------|
| `AIMIPhysioContextStoreMTR.kt` | 268-297 | Logs saveToDisk complets |
| `AIMIPhysioContextStoreMTR.kt` | 302-359 | Logs restoreFromDisk améliorés (partiellement - echec replace) |

**Total** : 1 fichier, ~30 lignes ajoutées (logs)

---

## 🐛 Bugs Restants à Traiter

### Bug 1: Overview Dashboard (Interface)
**Symptôme** : Carte unicor ou layout cassé  
**Status** : **À INVESTIGUER** (attente screenshot/description précise)

### Bug 2: Intervalle SMB Modes Meal
**Symptôme** : SMBs < intervalle configuré  
**Root Cause Possible** : Bypass via Advisor/Auto heuristiques  
**Status** : **IDENTIFIÉ** mais pas encore corrigé (nécessite audit de tous les appels `finalizeAndCapSMB`)

---

## 🎯 Next Steps

1. ✅ **Build & Test** les logs de persistence
2. ⏳ **Investiguer Bug Overview** (besoin screenshot)
3. ⏳ **Fix Intervalle SMB** (audit bypass points)
4. ⏳ **Test Runtime** avec device réel + Health Connect

**Temps estimé** : 15 min (build) + 30 min (test device) + 1h (bug overview + SMB intervals)
