# 🔧 Guide d'Intégration - AI Decision Auditor

## Point d'Injection dans DetermineBasalAIMI2.kt

L'auditeur doit être appelé **après** le calcul de la décision AIMI, mais **avant** la finalisation et l'envoi à la pompe.

---

## Étape 1 : Injection de Dépendances

Ajouter l'orchestrateur comme dépendance injectée dans `DetermineBasalAIMI2` :

```kotlin
@Singleton
class DetermineBasalaimiSMB2 @Inject constructor(
    private val profileUtil: ProfileUtil,
    private val fabricPrivacy: FabricPrivacy,
    private val preferences: Preferences,
    // ... autres dépendances ...
    private val auditorOrchestrator: AuditorOrchestrator,  // 🧠 NEW
    context: Context
) {
    // ...
}
```

---

## Étape 2 : Point d'Appel (après calcul SMB/TBR)

Trouver le point où la décision finale est calculée (après `SmbInstructionExecutor`, après damping, etc.).

**Exemple** (pseudo-code, à adapter selon votre flow exact) :

```kotlin
fun determineBasal(
    profile: OapsProfileAimi,
    iobTotal: IobTotal,
    glucoseStatus: GlucoseStatusAIMI?,
    currentTemp: CurrentTemp,
    mealData: MealData,
    autosensDataRatio: Double,
    isSaveCpuPower: Boolean,
    lastRun: RT?,
    predictions: Predictions?
): RT {
    
    // ... votre logique AIMI existante ...
    
    // ==========================================
    // CALCUL DE LA DÉCISION AIMI
    // ==========================================
    
    // Exemple : après le calcul du SMB final
    val smbProposed = finalSmbValue  // Votre SMB calculé
    val tbrRate = finalTbrRate       // Votre TBR calculé (U/h)
    val tbrDuration = finalTbrDuration // Durée TBR (min)
    val intervalMin = intervalCalculated // Interval calculé
    
    // Récupérer autres paramètres nécessaires
    val reasonTags = mutableListOf<String>()
    // Remplir reasonTags avec vos raisons de décision
    
    // Détecter si en mode repas
    val inMealMode = mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime || snackTime
    val modeType = when {
        bfastTime -> "breakfast"
        lunchTime -> "lunch"
        dinnerTime -> "dinner"
        highCarbTime -> "highCarb"
        snackTime -> "snack"
        mealTime -> "meal"
        else -> null
    }
    val modeRuntimeMin = when {
        bfastTime -> ((now - bfastruntime) / 60000).toInt()
        lunchTime -> ((now - lunchruntime) / 60000).toInt()
        dinnerTime -> ((now - dinnerruntime) / 60000).toInt()
        highCarbTime -> ((now - highCarbrunTime) / 60000).toInt()
        snackTime -> ((now - snackrunTime) / 60000).toInt()
        mealTime -> ((now - mealruntime) / 60000).toInt()
        else -> null
    }
    
    // État autodrive
    val autodriveState = when (lastAutodriveState) {
        AutodriveState.IDLE -> "OFF"
        AutodriveState.EARLY_DETECTION -> "EARLY"
        AutodriveState.CONFIRMED -> "CONFIRMED"
    }
    
    // WCycle (si actif)
    val wcyclePhase = wCycleInfoForRun?.phase?.name
    val wcycleFactor = wCycleInfoForRun?.factor
    
    // Détecter si en fenêtre prebolus (P1/P2)
    val inPrebolusWindow = false  // TODO: adapter selon votre logique P1/P2
    
    // Calculer SMB cumulé 30min (pour trigger)
    val smb30min = calculateSmbLast30Min()  // TODO: implémenter
    
    // Prédiction disponible ?
    val predictionAvailable = predictions != null && 
        (predictions.IOB?.isNotEmpty() == true || predictions.COB?.isNotEmpty() == true)
    
    // ==========================================
    // 🧠 APPEL AI AUDITOR
    // ==========================================
    
    // Variable pour stocker la décision modulée
    var finalSmb = smbProposed
    var finalTbrRate = tbrRate
    var finalTbrDuration = tbrDuration
    var finalInterval = intervalMin
    var preferTbrFlag = false
    
    // Appel async de l'auditeur
    auditorOrchestrator.auditDecision(
        bg = bg,
        delta = delta.toDouble(),
        shortAvgDelta = shortAvgDelta.toDouble(),
        longAvgDelta = longAvgDelta.toDouble(),
        glucoseStatus = glucoseStatus,
        iob = iobTotal,
        cob = cob.toDouble(),
        profile = profile,
        pkpdRuntime = pkpdRuntime,  // Votre runtime PKPD
        isfUsed = isfUsedValue,     // ISF fusionné utilisé
        smbProposed = smbProposed,
        tbrRate = tbrRate,
        tbrDuration = tbrDuration,
        intervalMin = intervalMin,
        maxSMB = maxSMB,
        maxSMBHB = maxSMBHB,
        maxIOB = maxIob,
        maxBasal = profile.max_basal,
        reasonTags = reasonTags,
        modeType = modeType,
        modeRuntimeMin = modeRuntimeMin,
        autodriveState = autodriveState,
        wcyclePhase = wcyclePhase,
        wcycleFactor = wcycleFactor,
        tbrMaxMode = yourTbrMaxMode,  // Si vous avez des max TBR spécifiques
        tbrMaxAutoDrive = yourTbrMaxAutoDrive,
        smb30min = smb30min,
        predictionAvailable = predictionAvailable,
        inPrebolusWindow = inPrebolusWindow
    ) { verdict, modulated ->
        
        // Callback appelé quand l'audit est terminé
        
        if (modulated.appliedModulation) {
            // ✅ Modulation appliquée
            
            // Log dans consoleLog
            consoleLog.add(sanitizeForJson("🧠 AI Auditor: ${modulated.modulationReason}"))
            
            if (verdict != null) {
                consoleLog.add(sanitizeForJson("   Verdict: ${verdict.verdict}, Confidence: ${String.format("%.2f", verdict.confidence)}"))
                consoleLog.add(sanitizeForJson("   Evidence: ${verdict.evidence.take(2).joinToString(" | ")}"))
                
                if (verdict.riskFlags.isNotEmpty()) {
                    consoleLog.add(sanitizeForJson("   ⚠️ Risk Flags: ${verdict.riskFlags.joinToString(", ")}"))
                }
            }
            
            // Appliquer la décision modulée
            finalSmb = modulated.smbU
            finalTbrRate = modulated.tbrRate
            finalTbrDuration = modulated.tbrMin
            finalInterval = modulated.intervalMin
            preferTbrFlag = modulated.preferTbr
            
            // Log des changements
            if (abs(finalSmb - smbProposed) > 0.01) {
                consoleLog.add(sanitizeForJson("   SMB modulated: ${smbProposed.asRounded(2)} → ${finalSmb.asRounded(2)} U"))
            }
            if (abs(finalInterval - intervalMin) > 0.1) {
                consoleLog.add(sanitizeForJson("   Interval modulated: ${intervalMin.roundToInt()} → ${finalInterval.roundToInt()} min"))
            }
            if (preferTbrFlag) {
                consoleLog.add(sanitizeForJson("   Prefer TBR enabled"))
            }
            
        } else {
            // ℹ️ Pas de modulation (audit only, confidence trop basse, etc.)
            
            if (verdict != null) {
                // Log le verdict même si pas de modulation
                consoleLog.add(sanitizeForJson("🧠 AI Auditor: ${modulated.modulationReason}"))
                consoleLog.add(sanitizeForJson("   AIMI decision confirmed (Verdict: ${verdict.verdict}, Conf: ${String.format("%.2f", verdict.confidence)})"))
            }
        }
    }
    
    // ==========================================
    // FINALISATION & RETURN
    // ==========================================
    
    // Note: L'audit est async, donc on retourne la décision originale
    // Si vous voulez attendre le verdict, utilisez runBlocking (mais non recommandé)
    
    // Construire le RT final avec les valeurs (potentiellement modulées si sync)
    val finalResult = RT(
        timestamp = now,
        bg = bg,
        //... autres champs ...
        consoleLog = consoleLog,
        consoleError = consoleError
    )
    
    // Si SMB > 0, créer l'instruction
    if (finalSmb > 0.0) {
        finalResult.deliverAt = now
        finalResult.units = finalSmb
        finalResult.reason = reasonTags.joinToString(", ")
    }
    
    // Si TBR recommandé
    if (finalTbrRate != null && finalTbrDuration != null) {
        finalResult.rate = finalTbrRate
        finalResult.duration = finalTbrDuration
    }
    
    return finalResult
}
```

---

## Étape 3 : Fonction Helper pour SMB 30min

Ajouter une fonction pour calculer le SMB cumulé sur 30 minutes :

```kotlin
private fun calculateSmbLast30Min(): Double {
    val now = dateUtil.now()
    val lookback30min = now - 30 * 60 * 1000L
    
    return try {
        val boluses = persistenceLayer
            .getBolusesAfterTimestamp(lookback30min, ascending = false)
            .blockingGet()
            .filter { it.type == app.aaps.core.data.model.BS.Type.SMB }
        
        boluses.sumOf { it.amount }
    } catch (e: Exception) {
        0.0
    }
}
```

---

## Étape 4 : Mode Async vs Sync

### Mode Async (Recommandé)

Le callback est appelé de manière asynchrone. La décision AIMI originale est retournée immédiatement, et la modulation s'applique au **prochain cycle**.

**Avantage :** Pas de blocage du loop
**Inconvénient :** Modulation avec 1 cycle de décalage

### Mode Sync (Non Recommandé)

Pour appliquer immédiatement :

```kotlin
import kotlinx.coroutines.runBlocking

// Dans determineBasal :
val (verdict, modulated) = runBlocking {
    // Attendre le verdict (bloque le thread)
    var result: Pair<AuditorVerdict?, DecisionModulator.ModulatedDecision>? = null
    
    auditorOrchestrator.auditDecision(
        // ... paramètres ...
    ) { v, m ->
        result = Pair(v, m)
    }
    
    // Attendre max 2 secondes
    var waited = 0
    while (result == null && waited < 2000) {
        Thread.sleep(100)
        waited += 100
    }
    
    result ?: Pair(null, DecisionModulator.ModulatedDecision(...))
}

// Utiliser verdict et modulated directement
if (modulated.appliedModulation) {
    finalSmb = modulated.smbU
    // ...
}
```

**⚠️ Attention :** Bloque le loop pendant max 2 secondes. Pas recommandé.

---

## Étape 5 : Logging & Debugging

Pour debugger, ajouter des logs spécifiques :

```kotlin
// Dans consoleLog, différencier les logs auditeur
consoleLog.add("═══════ AI AUDITOR START ═══════")
// ... logs auditeur ...
consoleLog.add("═══════ AI AUDITOR END ═══════")
```

Regarder dans `rT.consoleLog` pour les traces de l'auditeur.

---

## Étape 6 : Préférences UI

Ajouter une section dans les préférences AIMI pour configurer l'auditeur :

```xml
<!-- Dans res/xml/pref_aimi.xml -->

<PreferenceCategory
    android:key="aimi_ai_auditor_category"
    android:title="AI Decision Auditor (🧠 Second Brain)">
    
    <SwitchPreference
        android:key="aimi_auditor_enabled"
        android:title="Enable AI Decision Auditor"
        android:summary="Challenge AIMI decisions with AI-powered bounded modulation"
        android:defaultValue="false" />
    
    <ListPreference
        android:key="aimi_auditor_mode"
        android:title="Modulation Mode"
        android:dependency="aimi_auditor_enabled"
        android:entries="@array/aimi_auditor_mode_entries"
        android:entryValues="@array/aimi_auditor_mode_values"
        android:defaultValue="AUDIT_ONLY" />
    
    <SeekBarPreference
        android:key="aimi_auditor_max_per_hour"
        android:title="Max Audits per Hour"
        android:dependency="aimi_auditor_enabled"
        android:min="1"
        android:max="30"
        android:defaultValue="12" />
    
    <SeekBarPreference
        android:key="aimi_auditor_timeout_seconds"
        android:title="API Timeout (seconds)"
        android:dependency="aimi_auditor_enabled"
        android:min="30"
        android:max="300"
        android:defaultValue="120" />
    
    <SeekBarPreference
        android:key="aimi_auditor_min_confidence"
        android:title="Min Confidence %"
        android:dependency="aimi_auditor_enabled"
        android:summary="Minimum confidence to apply modulation"
        android:min="50"
        android:max="95"
        android:defaultValue="65" />

</PreferenceCategory>
```

**Arrays pour ListPreference :**

```xml
<!-- Dans res/values/arrays.xml -->

<string-array name="aimi_auditor_mode_entries">
    <item>Audit Only (Log only)</item>
    <item>Soft Modulation (Apply if confident)</item>
    <item>High Risk Only (Apply only if risk detected)</item>
</string-array>

<string-array name="aimi_auditor_mode_values">
    <item>AUDIT_ONLY</item>
    <item>SOFT_MODULATION</item>
    <item>HIGH_RISK_ONLY</item>
</string-array>
```

---

## Étape 7 : Gestion des Erreurs

Ajouter des try-catch pour gérer les erreurs silencieusement :

```kotlin
try {
    auditorOrchestrator.auditDecision(...)
} catch (e: Exception) {
    consoleLog.add("⚠️ AI Auditor error: ${e.message}")
    aapsLogger.error(LTag.APS, "AI Auditor exception", e)
    // Continue avec décision originale
}
```

---

## Résumé

1. ✅ **Injecter** `AuditorOrchestrator` dans `DetermineBasalAIMI2`
2. ✅ **Appeler** `auditDecision()` après calcul SMB/TBR
3. ✅ **Mode Async** (recommandé) ou Sync (si besoin d'appliquer immédiatement)
4. ✅ **Logger** verdicts et modulations dans `consoleLog`
5. ✅ **Préférences** UI pour configuration
6. ✅ **Gestion erreurs** robuste

---

## Test

Pour tester :

1. **Activer** l'auditeur dans préférences (mode AUDIT_ONLY)
2. **Observer** les logs dans rT pour voir les verdicts
3. **Passer** en SOFT_MODULATION après validation
4. **Monitorer** l'impact sur la glycémie

---

## Support

Documentation complète : `/docs/AI_DECISION_AUDITOR.md`

Profitez du "Second Cerveau" ! 🧠
