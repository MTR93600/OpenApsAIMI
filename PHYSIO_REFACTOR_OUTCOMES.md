# AIMI Physio Refactor - Outcome Tracking System

## Problème Identifié

L'utilisateur a Health Connect fonctionnel avec permissions accordées, mais le système affiche "NEVER_SYNCED" alors que des données existent (via Oura, Samsung Health, etc.). 

**Root Cause** : Le code confondait "NO_DATA" (query réussie, 0 records) avec "ERROR" (exception). Résultat : confidence=0 → isValid() échoue → getDetailedLogString() retourne null → UI affiche "Waiting...".

## Solution Implémentée

### 1. Nouveaux Types (`AIMIPhysioOutcomes.kt`)

**FetchOutcome** - Distingue les résultats de fetch:
- `SUCCESS` - Données récupérées
- `NO_DATA` - Query OK mais 0 records (PAS une erreur!)  
- `SECURITY_ERROR` - Permission denied
- `ERROR` - Exception générale
- `UNAVAILABLE` - Client HC indispo

**PhysioPipelineOutcome** - Résultat global du pipeline:
- `NEVER_RUN` - Jamais exécuté
- `SYNC_OK_NO_DATA` - HC synchro OK mais aucune donnée sur 7j
- `SYNC_PARTIAL` - Données partielles (ex: Steps/HR mais pas Sleep/HRV)
- `READY` - Données complètes, context calculé
- `SECURITY_ERROR` - Problème permissions
- `ERROR` - Erreur pipeline

**ProbeResult** - Diagnostic HC:
- Counts par type (Sleep, HRV, HR, Steps)
- Writers détectés (Oura, Samsung, etc.)
- Status SDK
- Permissions accordées

### 2. ContextStore Refactor

**Avant**:
- `currentContext` - null si confidence < seuil → UI aveugle
- Pas de distinction entre "jamais run" et "run mais pas de data"

**Après**:
```kotlin
// Tracking outcome
lastRunOutcome: PhysioPipelineOutcome
lastRunTimestamp: Long
lastProbeResult: ProbeResult?

// Context storage
lastContextUnsafe: PhysioContextMTR?  // Toujours dispo si run réussi
```

**Nouvelles méthodes**:
- `getLastContextUnsafe()` - Pour UI/logging (jamais null après un run)
- `getEffectiveContext(minConf)` - Pour appliquer multipliers (threshold)
- `getLastRunOutcome()` - Savoir ce qui s'est passé
- `getLastProbeResult()` - Voir ce que HC contient

### 3. Repository - Probe Diagnostique

```kotlin
suspend fun probeHealthConnect(windowDays: 7): ProbeResult
```

Compte les records par type + liste les writers. Log

 exemple:
```
✅ PROBE: Sleep=12 HRV=45 HR=892 Steps=156 | Writers=com.ouraring.oura,com.sec.android.app.shealth
```

### 4. Manager - Pipeline avec Outcomes

Le `performUpdate()` maintenant:
1. **Probe** Health Connect (diagnostic complet)
2. **Fetch** avec distinction NO_DATA vs ERROR
3. **Determine outcome** basé sur ce qui est dispo
4. **Store** context + outcome + probe
5. **Log structuré** avec counts réels

### 5. Adapter - getDetailedLogString() Never Null

**Avant**: Retournait null si confidence < 0.3 → "Waiting..."

**Après** : Retourne toujours une string basée sur outcome:
- `NEVER_RUN` → "NEVER_SYNCED | Waiting for first sync"
- `SYNC_OK_NO_DATA` → "HC OK but NO_DATA (Sleep/HRV/RHR=0). Check writers export."
- `SYNC_PARTIAL` → "Partial (Steps/HR only), conf=25%"  
- `READY` → Affichage complet avec métriques

### 6. DetermineBasal2 - Plus de fallback aveugle

```kotlin
// Avant
val log = adapter.getDetailedLogString()
if (log != null) consoleError.add(log)
else consoleError.add("Waiting...")  // ← Aveugle!

// Après  
val log = adapter.getDetailedLogString() // Never null
consoleError.add(log) // Toujours informatif
```

## Logs Production Attendus

**Startup**:
```
🚀 Starting AIMI Physiological Manager (WorkManager)
✅ Periodic work scheduled (4h interval)
🚀 Bootstrap update scheduled (5s delay)
```

**Bootstrap Run**:
```
🔄 Pipeline Start (Window: 7 days)
✅ PROBE: Sleep=12 HRV=45 HR=892 Steps=156 | Writers=com.ouraring.oura
PROBE: Granted perms=4, SDK=SDK_AVAILABLE
✅ Fetch completed in 342ms
✅ RUN COMPLETE | outcome=READY | state=OPTIMAL | conf=85% | counts: Sleep=Yes, HRV=45, RHR=12, Steps=Yes | Timings: Fetch=342ms, Extr=45ms, Analysis=12ms (Total: 412ms)
```

**Cas NO_DATA**:
```
✅ PROBE: Sleep=0 HRV=0 HR=0 Steps=0 | Writers=[]
PROBE: Granted perms=4, SDK=SDK_AVAILABLE
⚠️ No physiological data available
✅ RUN COMPLETE | outcome=SYNC_OK_NO_DATA | state=UNKNOWN | conf=0%
```

**UI Loop** :
```
🏥 Physio: SYNC_OK_NO_DATA (Conf: 0%) | Age: 0h | Next: 240min
    ⚠️ Bootstrap mode: No valid features
    ℹ️ Health Connect OK but no data found. Check if Oura/Samsung/Garmin exports to Health Connect.
```

## Checklist Validation Prod

1. ✅ Permissions incluses (READ_STEPS ajouté)
2. ✅ Compile sans erreurs
3. ✅ WorkManager scheduled (4h + bootstrap)
4. ✅ Probe log visible au start
5. ✅ Outcome != NEVER_RUN après premier run
6. ✅ getDetailedLogString() jamais null
7. ✅ UI montre vraiment l'état (pas juste "Waiting")
8. ✅ Context stocké même si confidence faible
9. ✅ Multipliers appliqués SEULEMENT si effectiveContext != null
10. ✅ Persistence fonctionne après redémarrages
