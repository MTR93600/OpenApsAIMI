# 📋 AIMI LEARNER MONITOR - PHASE 1 : ANALYSE PRÉALABLE

## Date: 2025-12-29 18:55

---

## 🎯 OBJECTIF

Créer un écran premium **"AIMI Learner Monitor"** accessible depuis le menu (3 points), juste sous AIMI Advisor, pour :
1. Visualiser l'évolution des learners (UR, ISF, PKPD, Activity, WCycle)
2. Voir l'impact de l'AI Auditor (verdict + modulations)
3. **Premium metrics** : Insulin Pressure Index (IPI 0-100) + Safety Headroom

---

## 1. ARCHITECTURE EXISTANTE

### 1.1 AIMI Advisor (Référence)

**Fichier** : `plugins/aps/.../advisor/AimiProfileAdvisorActivity.kt`

**Type** : `TranslatedDaggerAppCompatActivity`

**Injection Dagger** : ✅ Oui
```kotlin
@Inject lateinit var rh: ResourceHelper
@Inject lateinit var profileFunction: ProfileFunction
@Inject lateinit var persistenceLayer: PersistenceLayer
// etc.
```

**Menu ajouté dans** : `OpenAPSAIMIPlugin.kt`, ligne ~1313-1319
```kotlin
addPreference(AdaptiveIntentPreference(
    ctx = context,
    intentKey = IntentKey.OApsAIMIProfileAdvisor,
    intent = Intent(context, AimiProfileAdvisorActivity::class.java),
    summary = R.string.aimi_advisor_summary
))
```

**Manifest** : Doit déclarer Activity (à vérifier)

**UI Pattern** : 
- ScrollView + LinearLayout programmatique
- Cards (CardView)
- Dark premium theme (#10141C bg, #1E293B cards)
- Coroutines pour loading async

---

### 1.2 Data Source rT

**Location** : Les dernières décisions APS sont dans :
- `DetermineBasalAIMI2.kt` ligne ~6286 : `return finalResult` (type `RT`)
- `finalResult.reason` : Contient lignes concises (learners/auditor)
- `finalResult.learnersInfo` : String summary
- `finalResult.aiAuditor*` : Fields auditor

**Problème** : **Pas de store persistant** des rT !

**Actions à créer** :
1. **Store ring-buffer in-memory** : `AimiLearnerMonitorStore`
2. **Repository observable** : `AimiLearnerMonitorRepository` (Flow/LiveData)
3. **Hook dans DetermineBasalAIMI2** : Appeler `store.append(entry)` avant return

---

## 2. DATA LAYER À CRÉER

### 2.1 Entry Data Class

**Fichier** : `AimiLearnerMonitorEntry.kt`

```kotlin
data class AimiLearnerMonitorEntry(
    val timestamp: Long,
    val bg: Double,
    val delta: Double,
    
    // Decision
    val smbFinalU: Double,
    val tbrUph: Double?,
    val tbrMin: Int?,
    val intervalMin: Double,
    
    // Learners
    val urFactor: Double?,
    val isfProfile: Double,
    val isfUsed: Double,
    val isfScale: Double?,
    val pkpdDiaMin: Int?,
    val pkpdPeakMin: Int?,
    val pkpdTailPct: Int?,
    val pkpdState: String?,  // "PRE_ONSET"/"ONSET"/"PEAK"/"TAIL"
    val activityState: String?,
    val activityScore: Double?,
    val activityRecovery: Boolean?,
    val wcyclePhase: String?,
    val wcycleFactor: Double?,
    
    // Auditor
    val auditorEnabled: Boolean,
    val auditorVerdict: String?,  // "CONFIRM"/"SOFTEN"/"SHIFT_TO_TBR"
    val auditorConfidence: Double?,
    val auditorDegraded: Boolean?,
    val auditorSmbClamp: Double?,
    val auditorIntervalAdd: Int?,
    val auditorPreferTbr: Boolean?,
    val auditorTbrClamp: Double?,
    val auditorRiskFlags: String?,
    
    // Premium Metrics (calculated)
    val ipi: Int,  // 0-100
    val ipiLabel: String,  // "LOW"/"MODERATE"/"HIGH"/"VERY HIGH"
    val safetyHeadroom: Double,  // mg/dL above hypo threshold
    val safetyHeadroomLabel: String,  // "safe"/"watch"/"tight"
    
    // Context (for IPI calculation)
    val iob: Double,
    val maxIOB: Double,
    val maxSMB: Double,
    val smbLast30m: Double,
    val profileBasal: Double,
    val hypoThreshold: Double,
    val predBg: Double?,
    val eventualBg: Double?
)
```

---

### 2.2 Store Ring-Buffer

**Fichier** : `AimiLearnerMonitorStore.kt`

**Spec** :
- In-memory only (pas de persistance DB)
- Ring buffer : 72 entries max (6h @ 5min = 72 points)
- Thread-safe : `synchronized` ou `AtomicReference<List>`
- Exposé via Flow/LiveData

**API** :
```kotlin
object AimiLearnerMonitorStore {
    private val buffer = mutableListOf<AimiLearnerMonitorEntry>()
    private val maxSize = 72  // 6 hours at 5min
    private val flow = MutableStateFlow<List<AimiLearnerMonitorEntry>>(emptyList())
    
    @Synchronized
    fun append(entry: AimiLearnerMonitorEntry) {
        buffer.add(entry)
        if (buffer.size > maxSize) {
            buffer.removeAt(0)  // FIFO
        }
        flow.value = buffer.toList()  // Emit new list
    }
    
    fun getAll(): List<AimiLearnerMonitorEntry> = buffer.toList()
    fun getLast(): AimiLearnerMonitorEntry? = buffer.lastOrNull()
    fun getLast(n: Int): List<AimiLearnerMonitorEntry> = buffer.takeLast(n)
    fun observeFlow(): StateFlow<List<AimiLearnerMonitorEntry>> = flow.asStateFlow()
    fun clear() { buffer.clear(); flow.value = emptyList() }
}
```

---

### 2.3 Repository (Optional Layer)

**Fichier** : `AimiLearnerMonitorRepository.kt`

**Rôle** : Expose Flow, pourrait ajouter filtrage/transformation si besoin.

**Pour MVP** : Optionnel, on peut accéder directement au Store.

---

### 2.4 Hook dans DetermineBasalAIMI2

**Location** : Ligne ~6280, AVANT `return finalResult`

**Code à ajouter** :
```kotlin
// 📊 ================================================================
// LEARNER MONITOR: Store entry for UI
// ================================================================
try {
    val entry = AimiLearnerMonitorEntry(
        timestamp = System.currentTimeMillis(),
        bg = bg,
        delta = delta.toDouble(),
        smbFinalU = finalResult.units ?: 0.0,
        tbrUph = finalResult.rate,
        tbrMin = finalResult.duration,
        intervalMin = intervalsmb.toDouble(),
        urFactor = unifiedReactivityLearner.getCombinedFactor(),
        isfProfile = profile.sens,
        isfUsed = pkpdRuntime?.fusedIsf ?: profile.sens,
        isfScale = pkpdRuntime?.let { it.fusedIsf / profile.sens },
        pkpdDiaMin = pkpdRuntime?.params?.diaHrs?.let { (it * 60).toInt() },
        pkpdPeakMin = pkpdRuntime?.params?.peakMin?.toInt(),
        pkpdTailPct = pkpdRuntime?.tailFraction?.let { (it * 100).toInt() },
        pkpdState = null,  // TODO: Extract from PKPD if available
        activityState = null,  // TODO: Extract from activity context
        activityScore = null,
        activityRecovery = null,
        wcyclePhase = if (wCyclePreferences.enabled()) wCycleFacade.getPhase()?.name else null,
        wcycleFactor = if (wCyclePreferences.enabled()) wCycleFacade.getIcMultiplier() else null,
        auditorEnabled = finalResult.aiAuditorEnabled,
        auditorVerdict = finalResult.aiAuditorVerdict,
        auditorConfidence = finalResult.aiAuditorConfidence,
        auditorDegraded = null,  // TODO: Extract from auditor
        auditorSmbClamp = AuditorVerdictCache.get()?.verdict?.boundedAdjustments?.smbFactorClamp,
        auditorIntervalAdd = AuditorVerdictCache.get()?.verdict?.boundedAdjustments?.intervalAddMin,
        auditorPreferTbr = AuditorVerdictCache.get()?.verdict?.boundedAdjustments?.preferTbr,
        auditorTbrClamp = AuditorVerdictCache.get()?.verdict?.boundedAdjustments?.tbrFactorClamp,
        auditorRiskFlags = finalResult.aiAuditorRiskFlags,
        // Premium metrics (calculated inline or via helper)
        ipi = calculateIPI(...),  // Helper function
        ipiLabel = getIPILabel(...),
        safetyHeadroom = calculateSafetyHeadroom(...),
        safetyHeadroomLabel = getSafetyHeadroomLabel(...),
        // Context
        iob = iob_data_array.firstOrNull()?.iob ?: 0.0,
        maxIOB = profile.max_iob,
        maxSMB = profile.maxSMB,
        smbLast30m = calculateSmbLast30Min(),
        profileBasal = profile.current_basal,
        hypoThreshold = computeHypoThreshold(profile.min_bg, profile.lgsThreshold),
        predBg = predictedBg.toDouble(),
        eventualBg = eventualBG
    )
    
    AimiLearnerMonitorStore.append(entry)
} catch (e: Exception) {
    aapsLogger.error(LTag.APS, "Failed to store learner monitor entry", e)
}
```

---

## 3. PREMIUM METRICS

### 3.1 Insulin Pressure Index (IPI)

**Fichier** : `PremiumMetrics.kt` (helper object)

**Formule** :
```kotlin
fun calculateIPI(
    tbrUph: Double?,
    profileBasal: Double,
    iob: Double,
    maxIOB: Double,
    pkpdActivity: Double?,
    smbLast30m: Double,
    maxSMB: Double
): Int {
    val tbrComponent = tbrUph?.let { 
        (it / max(0.1, profileBasal)).coerceIn(0.0, 2.0) / 2.0 
    } ?: 0.0
    
    val iobComponent = (iob / max(0.1, maxIOB)).coerceIn(0.0, 1.0)
    
    val pkpdComp = pkpdActivity?.coerceIn(0.0, 1.0) ?: 0.5
    
    val smbRecentComp = (smbLast30m / max(0.1, maxSMB * 2)).coerceIn(0.0, 1.0)
    
    val rawIPI = (
        0.35 * tbrComponent +
        0.35 * iobComponent +
        0.20 * pkpdComp +
        0.10 * smbRecentComp
    ).coerceIn(0.0, 1.0)
    
    return (rawIPI * 100).toInt()
}

fun getIPILabel(ipi: Int): String = when (ipi) {
    in 0..25 -> "LOW"
    in 26..50 -> "MODERATE"
    in 51..75 -> "HIGH"
    else -> "VERY HIGH"
}
```

---

### 3.2 Safety Headroom

```kotlin
fun calculateSafetyHeadroom(
    bg: Double,
    predBg: Double?,
    eventualBg: Double?,
    hypoThreshold: Double
): Double {
    val minBg = listOfNotNull(bg, predBg, eventualBg).minOrNull() ?: bg
    return minBg - hypoThreshold
}

fun getSafetyHeadroomLabel(headroom: Double): String = when {
    headroom > 30 -> "safe"
    headroom >= 15 -> "watch"
    else -> "tight"
}
```

---

## 4. UI SCREEN

### 4.1 Activity Class

**Fichier** : `AimiLearnerMonitorActivity.kt`

**Extends** : `TranslatedDaggerAppCompatActivity`

**Injections** :
```kotlin
@Inject lateinit var rh: ResourceHelper
// Pas besoin d'injecter le store (object singleton)
```

**UI Structure** :
1. **Header "Now"** (premium block)
   - IPI gauge (ProgressBar + TextView)
   - Safety headroom pill
   - Auditor status pill
   
2. **Cards "Learners at a glance"** (2 colonnes)
   - UR, ISF, PKPD, Activity, WCycle, Auditor
   
3. **Trends section** (mini timeline RecyclerView)
   - Last 12 points (1h)
   - Display: UR factor, ISF scale, IPI
   
4. **Table "Last Decisions"**
   - RecyclerView, last 10 entries
   
5. **Actions** (bottom buttons)
   - "Copy Debug Summary" → clipboard
   - (Optional) "Export CSV"

---

### 4.2 Layout Strategy

**Programmatic** (like AimiProfileAdvisorActivity) ou **XML** ?

**Choix** : **Programmatic** pour cohérence avec AIMI Advisor.

**Theme** : Même dark premium (#10141C bg, #1E293B cards)

---

## 5. MENU INTEGRATION

### 5.1 Dans OpenAPSAIMIPlugin.kt

**Location** : Après ligne 1319 (après AIMI Advisor)

**Code** :
```kotlin
addPreference(AdaptiveIntentPreference(
    ctx = context,
    intentKey = IntentKey.OApsAIMILearnerMonitor,
    intent = Intent(context, app.aaps.plugins.aps.openAPSAIMI.advisor.AimiLearnerMonitorActivity::class.java),
    summary = R.string.aimi_learner_monitor_summary
))
```

---

### 5.2 IntentKey

**Fichier** : `core/keys/.../IntentKey.kt` (à localiser)

**Ajout** :
```kotlin
OApsAIMILearnerMonitor("oaps_aimi_learner_monitor", R.string.aimi_learner_monitor_title),
```

---

## 6. FICHIERS À CRÉER/MODIFIER

### Nouveaux Fichiers (9)

1. **AimiLearnerMonitorEntry.kt** : Data class
2. **AimiLearnerMonitorStore.kt** : Ring buffer + Flow
3. **PremiumMetrics.kt** : IPI + Safety headroom calculators
4. **AimiLearnerMonitorActivity.kt** : UI principale
5. **AimiLearnerMonitorAdapter.kt** : RecyclerView adapter (trends + table)
6. **strings.xml additions** : Pour UI labels

---

### Fichiers Modifiés (4)

7. **DetermineBasalAIMI2.kt** : Hook store.append() avant return
8. **OpenAPSAIMIPlugin.kt** : Ajout menu entry
9. **IntentKey.kt** : Ajout key
10. **AndroidManifest.xml** : Déclaration Activity (si nécessaire)

---

## 7. VALIDATION CHECKLIST

- [ ] Store vide → "No data yet" displayed
- [ ] Après 2 ticks → "Now" section peuplée
- [ ] Auditor OFF → affiche "OFF"
- [ ] Auditor ON avec verdict → affiche verdict + clamps
- [ ] IPI calculé correctement (0-100)
- [ ] Safety headroom affiche label correct
- [ ] Trends affiche 12 derniers points
- [ ] Table affiche 10 dernières décisions
- [ ] Copy to clipboard fonctionne
- [ ] Build success : `./gradlew assembleDebug`

---

## 8. RISQUES IDENTIFIÉS

### Risque 1 : Store Null après Restart

**Problème** : Store in-memory → vide après restart app.

**Mitigation** : OK, c'est voulu (pas de persistance DB). Afficher "No data yet".

### Risque 2 : Calcul IPI Instable

**Problème** : Divisions par zéro, NaN.

**Mitigation** : `max(0.1, value)` partout + `coerceIn()`.

### Risque 3 : UI Freeze

**Problème** : Store append dans main thread de DetermineBasal.

**Mitigation** : `try/catch` + log error si exception. Append doit rester O(1).

### Risque 4 : Memory Leak avec Flow

**Problème** : Observer Flow sans lifecycle-aware collection.

**Mitigation** : Utiliser `lifecycleScope.launch` + `collectLatest`.

---

## 9. PROCHAINES ÉTAPES

### Phase 2 : Implémentation Data Layer
1. Créer Entry, Store, PremiumMetrics
2. Hook dans DetermineBasalAIMI2
3. Tests unitaires (optional but recommended)

### Phase 3 : UI Activity
4. Créer AimiLearnerMonitorActivity
5. Header premium (IPI gauge)
6. Cards learners
7. Trends section
8. Table last decisions

### Phase 4 : Wiring
9. Menu dans OpenAPSAIMIPlugin
10. IntentKey
11. Manifest
12. Strings resources

### Phase 5 : Build & Validate
13. Compile
14. Test scénarios
15. Polish

---

## STATUS

**Phase 1 (Analyse)** : ✅ COMPLETE

**Prêt pour Phase 2** : ✅

---

**Créé le** : 2025-12-29 18:55  
**Status** : ✅ ANALYSE COMPLETE - READY FOR IMPLEMENTATION
