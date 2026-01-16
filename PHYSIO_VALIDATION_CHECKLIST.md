# ✅ AIMI Physio - Checklist de Validation Production

## Modifications Apportées (Résumé Exécutif)

### 🔧 Problème Résolu
**Symptôme**: "NEVER_SYNCED" alors que Health Connect a des permissions ET des données  
**Root Cause**: Confusion NO_DATA (0 records) vs ERROR → confidence=0 → isValid() fail → UI aveugle  
**Solution**: Système d'Outcome Tracking séparant état pipeline ET qualité données

---

## 📋 Checklist QA (5 Minutes)

### ✅ Phase 1: Installation & Permissions (2 min)

**1.1 Install APK**
```bash
adb uninstall info.nightscout.androidaps  # Clean install
adb install app-full-debug.apk
```

**1.2 Grant Permissions**
- Ouvrir AAPS → Settings → AIMI → Physio Assistant
- Toggle "Enable" → Oui
- Clic "Grant Health Connect Permissions"
- **VÉRIFICATION CRITIQUE**: TOUTES ces permissions doivent apparaître:
  - ✅ Sleep Sessions
  - ✅ Heart Rate Variability
  - ✅ Heart Rate  
  - ✅ **Steps** ← NOUVEAU (résout SecurityException)
- Accorder TOUTES

---

### ✅ Phase 2: Vérification Startup (1 min)

**2.1 Logcat PhysioManager**
```bash
adb logcat -s PhysioManager:I | head -20
```

**Logs AT

TENDUS** :
```
🚀 Starting AIMI Physiological Manager (WorkManager)
✅ Periodic work scheduled (4h interval)
Data is stale/never synced - triggering bootstrap
🚀 Bootstrap update scheduled (5s delay)
```

**2.2 WorkManager Inspection** (Android Studio)
```
Tools → App Inspection → Background Task Inspector
```
Vérifier:
- ✅ Tâche `AIMI_PHYSIO_4H` → State: ENQUEUED (récurrente)
- ✅ Tâche `AIMI_PHYSIO_BOOTSTRAP` → State: RUNNING ou SUCCEEDED

---

### ✅ Phase 3: Pipeline Bootstrap (1 min)

**3.1 Logs Complets** (10-15s après startup)
```bash
adb logcat -s PhysioManager:I PhysioRepository:I | head -50
```

**Scénario A: Données Disponibles** (Oura/Samsung/Garmin synchro HC)
```
✅ PROBE: Sleep=12 HRV=45 HR=892 Steps=156 | Writers=com.ouraring.oura,com.sec.android.app.shealth
PROBE: Granted perms=4, SDK=SDK_AVAILABLE
✅ Fetch completed in 342ms
✅ RUN COMPLETE | outcome=READY | state=OPTIMAL | conf=85% | Qual=92% | Counts: Sleep=Yes, HRV=45, RHR=12, Steps=Yes | Timings: Fetch=342ms, Extr=45ms, Analysis=12ms (Total: 412ms)
```
→ **SUCCÈS** : Outcome=READY, confidence > 0%

**Scénario B: HC OK mais Pas de Données**
```
✅ PROBE: Sleep=0 HRV=0 HR=0 Steps=0 | Writers=[]
PROBE: Granted perms=4, SDK=SDK_AVAILABLE
⚠️ No physiological data available
✅ RUN COMPLETE | outcome=SYNC_OK_NO_DATA | state=UNKNOWN | conf=0%
```
→ **SUCCÈS PARTIEL** : Outcome=SYNC_OK_NO_DATA (pas NEVER_RUN!), log clair

**Scénario C: Permissions Manquantes**
```
❌ PROBE: Sleep count failed - Permission denied
...
✅ RUN COMPLETE | outcome=SECURITY_ERROR | ...
```
→ **ÉCHEC** : Retourner à Phase 1

---

### ✅ Phase 4: UI Loop Visibility (1 min)

**4.1 Onglet AIMI → Section "Loop Status"**

Rechercher ligne commençant par `🏥 Physio:`

**Cas READY** (données OK):
```
🏥 Physio: OPTIMAL (Conf: 85%) | Age: 0h | Next: 240min
    • Sleep: 7.2h (Eff: 88%) Z=-0.3
    • HRV: 42ms Z=0.1 | RHR: 58bpm Z=-0.5
```
→ ✅ **PARFAIT**

**Cas SYNC_OK_NO_DATA** (HC vide):
```
🏥 Physio: UNKNOWN (Conf: 0%) | Age: 0h | Next: 240min
    ⚠️ Bootstrap mode: No valid features
    ℹ️ Health Connect OK but no data found (Sleep/HRV/RHR=0). Check if Oura/Samsung exports to Health Connect.
```
→ ✅ **BON** (plus de "Waiting..." aveugle!)

**Cas SYNC_PARTIAL** (Steps/HR seulement):
```
🏥 Physio: UNKNOWN (Conf: 25%) | Age: 0h | Next: 240min
    ⚠️ Bootstrap mode: Quality=25%, Missing: Sleep, HRV
```
→ ✅ **BON** (informatif)

**❌ ÉCHEC si visible** :
```
🏥 Physio: NEVER_SYNCED | Waiting for first Health Connect sync (check permissions)
```
→ Outcome resté à NEVER_RUN → Vérifier logs Phase 3

---

### ✅ Phase 5: Persistence (30 sec)

**5.1 Force Stop + Restart**
```bash
adb shell am force-stop info.nightscout.androidaps
# Attendre 5s
adb shell am start -n info.nightscout.androidaps/.MainActivity
```

**5.2 Logcat Restore**
```bash
adb logcat -s PhysioContextStore:I | head -5
```

**Attendu**:
```
✅ Context restored (outcome=READY, state=OPTIMAL, age=0h)
```

→ ✅ Si outcome ET state restaurés → Persistence OK  
→ ❌ Si "No saved context found" → Storage fail (vérifier permissions /Documents/AAPS)

---

## 🚨 Points Critiques de Débogage

### Si "NEVER_SYNCED" Persiste

**Diagnostic rapide** :
```bash
adb shell dumpsys package info.nightscout.androidaps | grep -A5 "requested permissions"
```
Vérifier présence de:
- `android.permission.health.READ_SLEEP`
- `android.permission.health.READ_HEART_RATE`
- `android.permission.health.READ_HEART_RATE_VARIABILITY`
- `android.permission.health.READ_STEPS` ← **CRITIQUE**

Si manquant → Réinstall complète

### Si "SYNC_OK_NO_DATA" mais Oura/Samsung Actif

**Vérifier Export Health Connect** :
1. Ouvrir Health Connect app
2. Data and access → [App source] (ex: Oura)
3. S'assurer que Sleep/HR/HRV sont cochés "Share with Health Connect"
4. Forcer synchro dans l'app source
5. Attendre 5 min
6. Déclencher manual update Physio (ou attendre 4h)

### Si Crash au Startup

**Logcat complet** :
```bash
adb logcat | grep -E "(PhysioManager|FATAL|AndroidRuntime)"
```

Erreurs typiques:
- `OutOfMemoryError` → probe trop de données (réduire window à 3j temporairement)
- `SecurityException` → Permissions mal déclarées (vérifier Manifest)
- `FileNotFoundException` → Storage /Documents inaccessible (fallback internal)

---

## 📊 Métriques de Réussite

| Critère | Cible | Comment Vérifier |
|---------|-------|------------------|
| Permissions Steps incluses | ✅ | Phase 1.2 - Liste permissions |
| Bootstrap s'exécute | ✅ | Phase 2.1 - Log "Bootstrap scheduled" |
| Probe log visible | ✅ | Phase 3.1 - Log "PROBE: Sleep=..." |
| Outcome != NEVER_RUN | ✅ | Phase 3.1 - Log "outcome=READY/SYNC_OK_NO_DATA/..."  |
| UI jamais "Waiting..." | ✅ | Phase 4.1 - Voir outcome précis |
| getDetailedLogString() jamais null | ✅ | Phase 4.1 - Toujours une string affichée |
| Persistence fonctionne | ✅ | Phase 5.2 - Restore après restart |
| WorkManager 4h actif | ✅ | Phase 2.2 - Task ENQUEUED |

**Seuil PASS** : 7/8 critères ✅ = Production Ready  
**Seuil FAIL** : < 6/8 = Investigation approfondie requise

---

## 🎯 Next Steps (Si PASS)

1. **Monitor 24h** : Vérifier que le pipeline tourne bien toutes les 4h
2. **Vérifier Multipliers** : Si confidence > 50%, vérifier que ISF/Basal/SMB sont modifiés dans les logs loop
3. **Test States** : Simuler nuit courte / stress → vérifier détection RECOVERY_NEEDED / STRESS_DETECTED
4. **LLM Analysis** (optionnel) : Activer Physio LLM si API key configurée

---

## 📞 Support Debug

Si blocage sur un scénario non couvert, fournir :
1. `adb logcat -d > full_logcat.txt` (dernier boot complet)
2. Screenshot UI "Physio Status"
3. Fichier `/sdcard/Documents/AAPS/physio_context.json` (si existe)
4. Health Connect app → Data sources → Screenshot liste apps

**Temps estimé validation complète** : 5-7 minutes
