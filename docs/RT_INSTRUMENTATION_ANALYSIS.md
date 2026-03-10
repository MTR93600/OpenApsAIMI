# 📋 PHASE A : ANALYSE PRÉ-MODIFICATION - RT INSTRUMENTATION

## Date: 2025-12-29 18:10

---

## 1. STRUCTURE RT ACTUELLE

### Classe RT (core/interfaces/src/main/kotlin/app/aaps/core/interfaces/aps/RT.kt)

**Type** : `@Serializable data class RT`

**Champs Existants Pertinents** :
```kotlin
var reason: StringBuilder              // Main reason text
var consoleLog: MutableList<String>?   // Detailed logs
var aiAuditorEnabled: Boolean          // Auditor state
var aiAuditorVerdict: String?          // CONFIRM/SOFTEN/SHIFT_TO_TBR
var aiAuditorConfidence: Double?       // 0.0-1.0
var aiAuditorModulation: String?       // Modulation description
var aiAuditorRiskFlags: String?        // Risk flags
var learnersInfo: String?              // ✅ Déjà ajouté !
```

**Extension Possible** : Oui, RT est sérialisable et peut accepter nouveaux champs.

---

## 2. CONSTRUCTION DE `reason` ACTUELLE

### Localisation : `DetermineBasalAIMI2.kt`

**Point de Construction Principal** :
- Ligne ~810 : `determineBasalResultAimi()` construit `rT` initial
- Ligne ~1166+ : `setTempBasal()` modifie `rT.reason`
- Ligne ~6037 : **Learners summary ajouté** (déjà présent)

**Format Actuel** :
```
reason = "IOB élevé (2.5U), réduction x0.85; BG delta +4; [Basal×1.05, ISF:42, React:0.95x]"
```

**Problème** : Le format learners actuel est **DÉJÀ** ajouté mais :
- ❌ Pas de PKPD détails (DIA, Peak, Tail)
- ❌ Pas d'activity state
- ❌ Pas de wcycle phase
- ❌ Auditor pas dans `reason`, seulement dans champs dédiés

---

## 3. LEARNERS : STOCKAGE DES VALEURS

### 3.1 BasalLearner

**Localisation** : Ligne 5939-5952

**Valeurs Exposées** :
```kotlin
basalLearner.shortTermMultiplier   // Double
basalLearner.mediumTermMultiplier  // Double
basalLearner.longTermMultiplier    // Double
basalLearner.getMultiplier()       // Combined factor
```

**Logging Actuel** : ✅ `consoleLog` uniquement

---

### 3.2 UnifiedReactivityLearner

**Localisation** : Ligne 5954-5969

**Valeurs Exposées** :
```kotlin
unifiedReactivityLearner.lastAnalysis?.let { analysis ->
    analysis.globalFactor        // Double
    analysis.shortTermFactor     // Double
    analysis.tir70_180           // Double (%)
    analysis.cv_percent          // Double (%)
    analysis.hypo_count          // Int
    analysis.adjustmentReason    // String
}
unifiedReactivityLearner.getCombinedFactor()  // Final factor
```

**Logging Actuel** : ✅ `consoleLog` uniquement (8 lignes)

---

### 3.3 PKPD Learner

**Localisation** : Ligne 4155-4163

**Valeurs Exposées** :
```kotlin
pkpdRuntime?.let { runtime ->
    runtime.params.diaHrs       // Double (hours)
    runtime.params.peakMin      // Double (minutes)
    runtime.fusedIsf            // Double (mg/dL/U)
    runtime.pkpdScale           // Double
    runtime.profileIsf          // Double (from profile)
    runtime.tddIsf              // Double (from TDD)
    runtime.tailFraction        // Double (0.0-1.0)
}
```

**Logging Actuel** : ✅ `consoleLog` uniquement (5 lignes)

---

### 3.4 Activity Manager

**Recherche** : Pas trouvé d'activity learner explicite.

**Hypothèse** : Possiblement intégré dans `activityContext` ou indirect via PKPD.

**Action** : À vérifier si existe un `ActivityManager` ou équivalent.

---

### 3.5 WCycle Facade

**Recherche** : Présent via `wCycleFacade`

**Valeurs Exposées** (à confirmer) :
```kotlin
wCycleFacade.getPhase()           // CyclePhase?
wCycleFacade.getIcMultiplier()    // Double
wCyclePreferences.enabled()       // Boolean
```

**Logging Actuel** : Pas de logging dédié trouvé dans consoleLog

---

### 3.6 SMB Damping

**Localisation** : Appels à `pkpdRuntime.dampSmb()` ou `dampSmbWithAudit()`

**Valeurs** :
- `tailFraction` : dans pkpdRuntime
- `exercise` : Boolean (probablement dans activity context)
- `suspectedLateFatMeal` : Boolean
- Résultat damping : SMB original → SMB damped

**Logging Actuel** : Pas de trace explicite du damping dans consoleLog

---

## 4. AUDITOR : INTÉGRATION ACTUELLE

### Localisation : Ligne 6043-6195

**État** :
```kotlin
val auditorEnabled = preferences.get(BooleanKey.AimiAuditorEnabled)
finalResult.aiAuditorEnabled = auditorEnabled

if (auditorEnabled) {
    auditorOrchestrator.auditDecision(...) { verdict, modulated ->
        if (modulated.appliedModulation) {
            finalResult.units = modulated.smbU
            finalResult.aiAuditorVerdict = verdict?.verdict?.name
            finalResult.aiAuditorConfidence = verdict?.confidence
            finalResult.aiAuditorModulation = modulated.modulationReason
            finalResult.aiAuditorRiskFlags = verdict?.riskFlags?.joinToString(", ")
        }
    }
}
```

**Problème Critique** :
- ✅ Les champs RT sont peuplés
- ❌ **Pas ajouté à `finalResult.reason`**
- ⚠️ Callback **asynchrone** → verdict peut arriver APRÈS retour de `finalResult`

**Cache Auditor** : Pas de cache détecté. Les verdicts précédents sont perdus.

---

## 5. FORMAT DE LOG ACTUEL

### consoleLog Format

**Exemple Actuel** :
```
📊 BASAL_LEARNER:
  │ shortTerm: 1.050
  │ mediumTerm: 0.980
  │ longTerm: 1.120
  └ combined: 1.050

📊 PKPD_LEARNER:
  │ DIA (learned): 5.83h
  │ Peak (learned): 76min
  │ fusedISF: 51.2 mg/dL/U
  │ pkpdScale: 1.110
  └ adaptiveMode: ACTIVE

📊 REACTIVITY_LEARNER:
  │ globalFactor: 1.120
  │ shortTermFactor: 1.050
  │ combinedFactor: 1.120
  │ TIR 70-180: 78%
  │ CV%: 32%
  │ Hypo count (24h): 2
  │ Reason: TIR improving
  └ Analyzed at: 2025-12-29 18:10:00
```

**Problème** : Très verbeux (20+ lignes), pas concis.

---

## 6. RESOURCES STRINGS

**Recherche** : `context.getString(R.string.xxx)`

**Exemple Trouvés** :
```kotlin
context.getString(R.string.bg_drop_high, dropPerHour)
context.getString(R.string.bg_rapid_rise, delta)
```

**Localisation** : `plugins/aps/src/main/res/values/strings.xml`

**Conclusion** : Strings resources utilisés pour `reason`, mais PAS pour learners logging.

---

## 7. POINT DE SORTIE UNIQUE

### Fonction : `determine_basal()`

**Retour** : `RT` object (alias de `finalResult`)

**Ligne de Retour** : ~6286 `return finalResult`

**Modifications à finalResult** :
1. Ligne ~6004-6011 : `setTempBasal()` crée `finalResult`
2. Ligne ~6027 : Safety clamp basal
3. Ligne ~6032-6041 : Learners info ajouté à `reason`
4. Ligne ~6052+ : Auditor fields peuplés
5. Ligne ~6286 : Return

**Point d'Injection Idéal** : Entre ligne 6041 et 6043 (après learners, avant auditor)

---

## 8. DONNÉES MANQUANTES À COLLECTER

### À Ajouter dans Learners Line

**PKPD** :
- ✅ fusedISF (déjà dans learnersSummary)
- ❌ DIA (hours)
- ❌ Peak (minutes)
- ❌ Tail fraction (%)
- ❌ Insulin activity state (PRE_ONSET/ONSET/PEAK/TAIL)

**Activity** :
- ❌ Activity state (REST/MODERATE/HIGH)
- ❌ Activity score
- ❌ Recovery mode

**WCycle** :
- ❌ Enabled
- ❌ Phase (Follicular/Luteal/etc.)
- ❌ Factor applied

**SMB Damping** :
- ❌ Tail damping factor
- ❌ Exercise damping
- ❌ Late fat meal damping
- ❌ Final result (original → damped)

---

## 9. AUDITOR : CACHE REQUIS

### Problème Async

L'auditor tourne en async. Quand `finalResult` est retourné, le verdict peut ne pas être disponible.

**Solution Requise** :
1. Créer un **cache simple** `AuditorVerdictCache`
2. Stocker le dernier verdict avec timestamp
3. Au moment de construire `reason`, lire le cache
4. Si age > 5min → `"Auditor: STALE"`
5. Si null → `"Auditor: OFFLINE"`

**Implémentation** :
```kotlin
object AuditorVerdictCache {
    @Volatile private var lastVerdict: CachedVerdict? = null
    
    data class CachedVerdict(
        val verdict: AuditorVerdict,
        val timestamp: Long
    )
    
    fun update(verdict: AuditorVerdict) {
        lastVerdict = CachedVerdict(verdict, System.currentTimeMillis())
    }
    
    fun get(maxAgeMs: Long = 300_000): CachedVerdict? {
        val cached = lastVerdict ?: return null
        if (System.currentTimeMillis() - cached.timestamp > maxAgeMs) return null
        return cached
    }
}
```

---

## 10. FORMAT CIBLE

### Ligne 1 : Learners (≤ 80 chars)

```
Learners: UR×1.12 ISF 46→51(×1.11) PKPD DIA 350m Pk 76m Tail 91% Act MOD(4.2)
```

**Breakdown** :
- `UR×1.12` : UnifiedReactivity factor
- `ISF 46→51(×1.11)` : Profile ISF → Fused ISF (scale factor)
- `PKPD DIA 350m` : Learned DIA in minutes
- `Pk 76m` : Peak time minutes
- `Tail 91%` : Tail fraction %
- `Act MOD(4.2)` : Activity state + score

### Ligne 2 : WCycle (optionnelle, ≤ 60 chars)

```
Wcycle: Luteal ×1.08 (thyroid:on verneuil:off)
```

### Ligne 3 : Auditor (≤ 80 chars)

**Si OFF** :
```
Auditor: OFF
```

**Si ON + verdict récent** :
```
Auditor: SOFTEN conf=0.78 smb×0.65 +3m preferTBR [stacking,hypo]
```

**Si ONLINE mais pas de signal** :
```
Auditor: STALE (5m old)
```

---

## 11. HELPERS À CRÉER

### Dans DetermineBasalAIMI2.kt (private functions)

```kotlin
private fun buildLearnersDebugLine(
    unifiedReactivityFactor: Double?,
    profileIsf: Double,
    fusedIsf: Double,
    isfScale: Double?,
    pkpdDiaMin: Int?,
    pkpdPeakMin: Int?,
    pkpdTailPct: Int?,
    activityState: String?,
    activityScore: Double?
): String {
    // Build concise line, handle nulls
}

private fun buildWCycleLine(
    enabled: Boolean,
    phase: String?,
    factor: Double?
): String? {
    if (!enabled) return null
    // Build concise line
}

private fun buildAuditorLine(
    enabled: Boolean,
    verdict: CachedAuditorVerdict?
): String {
    if (!enabled) return "Auditor: OFF"
    // Build concise line
}

private fun safeFmt(value: Double?, format: String, fallback: String = "n/a"): String {
    if (value == null || value.isNaN() || value.isInfinite()) return fallback
    return format.format(Locale.US, value)
}

private fun safeInt(value: Double?): String {
    if (value == null || value.isNaN() || value.isInfinite()) return "n/a"
    return value.toInt().toString()
}
```

---

## 12. PLAN D'IMPLÉMENTATION

### Étape 1 : Créer AuditorVerdictCache

**Fichier** : `plugins/aps/.../advisor/auditor/AuditorVerdictCache.kt`

### Étape 2 : Modifier AuditorOrchestrator

Dans callback, ajouter :
```kotlin
AuditorVerdictCache.update(verdict, modulated)
```

### Étape 3 : Créer Helpers

Dans `DetermineBasalAIMI2.kt`, ajouter les fonctions private.

### Étape 4 : Construire les Lignes

Après ligne 6041 (learners summary), ajouter :
```kotlin
// Build detailed learners line
val learnersLine = buildLearnersDebugLine(...)
finalResult.reason.append("\n").append(learnersLine)

// Build wcycle line if applicable
val wcycleLine = buildWCycleLine(...)
if (wcycleLine != null) {
    finalResult.reason.append("\n").append(wcycleLine)
}

// Build auditor line
val auditorLine = buildAuditorLine(...)
finalResult.reason.append("\n").append(auditorLine)
```

### Étape 5 : Tests

Créer unit tests pour helpers (null handling, NaN, format).

---

## 13. VALIDATION

### Checklist

- [ ] Build compile : `./gradlew assembleDebug`
- [ ] `reason` contient 2-3 lignes max
- [ ] Null/NaN handled gracefully
- [ ] Auditor OFF → `"Auditor: OFF"`
- [ ] Auditor STALE → `"Auditor: STALE (Xm)"`
- [ ] Format respecté (≤ 80 chars/ligne)
- [ ] consoleLog preservé (verbose logs)
- [ ] RT serialize OK

---

## 14. FICHIERS À MODIFIER

### Nouveaux Fichiers

1. **AuditorVerdictCache.kt** :
   - `plugins/aps/.../advisor/auditor/AuditorVerdictCache.kt`

### Fichiers Modifiés

1. **AuditorOrchestrator.kt** :
   - Ajouter `AuditorVerdictCache.update()` dans callback

2. **DetermineBasalAIMI2.kt** :
   - Ajouter helpers (private functions)
   - Construire et append les lignes à `finalResult.reason`
   - Collecter valeurs manquantes (activity, wcycle, pkpd details)

3. **RT.kt** (optionnel) :
   - Si besoin d'ajouter champs structurés supplémentaires

---

## 15. RISQUES IDENTIFIÉS

### Risque 1 : Auditor Async

**Problème** : Verdict peut arriver après return de `finalResult`.

**Mitigation** : Cache + affichage du verdict PRÉCÉDENT (acceptable).

### Risque 2 : Null/NaN

**Problème** : Learners peuvent retourner null ou NaN.

**Mitigation** : Helpers avec safe formatting + fallback `"n/a"`.

### Risque 3 : consoleLog Pollution

**Problème** : Si helpers loggent, consoleLog devient énorme.

**Mitigation** : Helpers NE loggent PAS. Logs verbeux restent où ils sont.

### Risque 4 : Length Overflow

**Problème** : Ligne > 80 chars illisible sur mobile.

**Mitigation** : Truncate + ellipsis si besoin.

---

## CONCLUSION PHASE A

**État Actuel** :
- ✅ RT structure OK, extensible
- ✅ Learners values accessibles
- ✅ consoleLog logs verbeux présents
- ⚠️ `reason` partiellement instrumenté (learners summary basique)
- ❌ Auditor pas dans `reason`
- ❌ PKPD details absents de `reason`
- ❌ Activity/WCycle absents de `reason`
- ❌ Pas de cache auditor

**Prêt pour Phase B : Implémentation** ✅

---

**Créé le** : 2025-12-29 18:10  
**Status** : ✅ ANALYSE COMPLÈTE - PRÊT POUR IMPLÉMENTATION
