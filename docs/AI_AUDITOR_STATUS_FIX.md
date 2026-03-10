# 🔧 AI AUDITOR STATUS FIX - IMPLÉMENTATION

## Date: 2025-12-31 08:50

---

## 🐛 PROBLÈME IDENTIFIÉ

### Comportement Actuel (Buggé)
Dans les logs RT, on voit **simultanément** :
- `aiAuditorEnabled: true`
- `Auditor: OFFLINE`

**Danger** : L'utilisateur pense que l'auditeur fonctionne mais il ne fait RIEN !

### Root Cause Identifiée

**Fichier** : `RtInstrumentationHelpers.kt` ligne 116

```kotlin
fun buildAuditorLine(enabled: Boolean): String {
    if (!enabled) return "Auditor: OFF"
    
    val cached = AuditorVerdictCache.get(maxAgeMs = 300_000)
    
    if (cached == null) {
        val ageMs = AuditorVerdictCache.getAgeMs()
        return if (ageMs != null) {
            val ageMin = (ageMs / 60_000).toInt()
            "Auditor: STALE (${ageMin}m old)"
        } else {
            "Auditor: OFFLINE"  // ← PROBLÈME: Trop vague !
        }
    }
    // ...
}
```

**Pourquoi "OFFLINE" sans raison ?**

Le cache `AuditorVerdictCache` est `null` quand :
1. ❌ **Pas d'API key** → Pas de requête envoyée
2. ❌ **Pas de réseau** → Timeout/erreur réseau
3. ❌ **Pas eligible** (BG trop bas, delta négatif, etc.) → `shouldTriggerAudit()` retourne `false`
4. ❌ **Rate limited** → Cooldown actif (5min entre appels)
5. ❌ **Timeout AI** → Requête envoyée mais pas de réponse
6. ❌ **Parse error** → Réponse reçue mais JSON invalide
7. ❌ **Exception** → Crash quelconque

**Actuellement** : Tous ces cas = "OFFLINE" → IMPOSSIBLE à debugger !

---

## ✅ SOLUTION IMPLÉMENTÉE

### 1. Nouveau Fichier : `AuditorStatusTracker.kt`

**Status Machine Explicite** :

| Catégorie | Status | Signification |
|-----------|--------|---------------|
| **OFF** | `OFF` | Préférence désactivée |
| **SKIPPED** | `SKIPPED_NO_TRIGGER` | Enabled mais conditions non remplies (BG bas, delta négatif...) |
|  | `SKIPPED_RATE_LIMITED` | Enabled mais cooldown actif (5min) |
|  | `SKIPPED_PREBOLUS_WINDOW` | En fenêtre prebolus (P1/P2) |
|  | `SKIPPED_COOLDOWN` | Custom cooldown |
| **OFFLINE** | `OFFLINE_NO_APIKEY` | Pas de clé API configurée |
|  | `OFFLINE_NO_NETWORK` | Pas de connexion réseau |
|  | `OFFLINE_NO_ENDPOINT` | Endpoint AI non configuré |
|  | `OFFLINE_DNS_FAIL` | Échec résolution DNS |
| **ERROR** | `ERROR_TIMEOUT` | Req envoyée mais timeout (>30s) |
|  | `ERROR_PARSE` | Réponse reçue mais JSON invalide |
|  | `ERROR_HTTP` | Erreur HTTP (4xx, 5xx) |
|  | `ERROR_EXCEPTION` | Exception inattendue |
| **OK** | `OK_CONFIRM` | Verdict reçu : CONFIRM (pas de changement) |
|  | `OK_SOFTEN` | Verdict reçu : SOFTEN (réduction modérée) |
|  | `OK_REDUCE` | Verdict reçu : REDUCE (réduction forte) |
|  | `OK_INCREASE_INTERVAL` | Verdict reçu : Augmente intervalle SMB |
|  | `OK_PREFER_TBR` | Verdict reçu : Préfère TBR au lieu de SMB |
| **STALE** | `STALE` | Verdict trop ancien (>5min) |

### 2. Intégration dans `AuditorOrchestrator.kt`

**Avant** :
```kotlin
if (!isAuditorEnabled()) {
    aapsLogger.debug(LTag.APS, "AI Auditor: Disabled")
    callback?.invoke(...)
    return
}
```

**Après** :
```kotlin
if (!isAuditorEnabled()) {
    aapsLogger.debug(LTag.APS, "AI Auditor: Disabled")
    AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.OFF)  // ← Track !
    callback?.invoke(...)
    return
}
```

**Points de tracking** :
1. ✅ Ligne 135 : `Status.OFF` (disabled)
2. ✅ Ligne 155 : `Status.SKIPPED_NO_TRIGGER` (pas de conditions)
3. ✅ Ligne 163 : `Status.SKIPPED_RATE_LIMITED` (cooldown)
4. ✅ Ligne 236 : `Status.OK_*` (verdict reçu, déterminé par verdict.verdict)
5. ✅ Ligne 240 : `Status.ERROR_TIMEOUT` (pas de verdict)
6. ✅ Ligne 245 : `Status.ERROR_EXCEPTION` (exception)

### 3. Update `AuditorAIService.kt` (à faire)

Ajouter tracking pour erreurs réseau/API :
- `OFFLINE_NO_APIKEY` : Avant d'envoyer requête, check API key
- `OFFLINE_NO_NETWORK` : Catch `UnknownHostException`, `IOException`
- `ERROR_HTTP` : Status code != 200
- `ERROR_PARSE` : JSON parse exception

### 4. Update `RtInstrumentationHelpers.kt`

**Avant** :
```kotlin
fun buildAuditorLine(enabled: Boolean): String {
    if (!enabled) return "Auditor: OFF"
    
    val cached = AuditorVerdictCache.get()
    if (cached == null) {
        return "Auditor: OFFLINE"  // ← Vague !
    }
    // ...
}
```

**Après** :
```kotlin
fun buildAuditorLine(enabled: Boolean): String {
    // Use AuditorStatusTracker for detailed status
    val (status, ageMs) = AuditorStatusTracker.getStatus()
    
    when {
        status == AuditorStatusTracker.Status.OFF -> 
            return "Auditor: OFF"
        
        status.isOffline() -> 
            return "Auditor: ${status.message}"  // Ex: "OFFLINE_NO_APIKEY"
        
        status.isError() -> 
            return "Auditor: ${status.message}"  // Ex: "ERROR_TIMEOUT"
        
        status.isSkipped() -> 
            return "Auditor: ${status.message}"  // Ex: "SKIPPED_RATE_LIMITED"
        
        status == AuditorStatusTracker.Status.STALE && ageMs != null -> {
            val ageMin = (ageMs / 60_000).toInt()
            return "Auditor: STALE (${ageMin}m old)"
        }
        
        status.isActive() -> {
            // Build detailed line from cache
            val cached = AuditorVerdictCache.get() ?: 
                return "Auditor: ${status.message}"
            
            val parts = mutableListOf<String>()
            parts.add(cached.verdict.verdict.name)
            parts.add("conf=${String.format(\"%.2f\", cached.verdict.confidence)}")
            
            if (cached.modulation.appliedModulation) {
                val smbFactor = cached.verdict.boundedAdjustments.smbFactorClamp
                if (smbFactor < 1.0) {
                    parts.add("smb×${String.format(\"%.2f\", smbFactor)}")
                }
                
                val intervalAdd = cached.verdict.boundedAdjustments.intervalAddMin
                if (intervalAdd > 0) {
                    parts.add("+${intervalAdd}m")
                }
            }
            
            if (cached.modulation.preferTbr) {
                parts.add("preferTBR")
            }
            
            val line = "Auditor: " + parts.joinToString(" ")
            return if (line.length > 80) line.substring(0, 77) + "..." else line
        }
        
        else -> return "Auditor: UNKNOWN"
    }
}
```

---

## 📊 EXEMPLES DE LOGS APRÈS FIX

### Cas 1 : Pas d'API Key
**Avant** : `Auditor: OFFLINE`  
**Après** : `Auditor: OFFLINE_NO_APIKEY`

### Cas 2 : Rate Limited
**Avant** : `Auditor: OFFLINE`  
**Après** : `Auditor: SKIPPED_RATE_LIMITED`

### Cas 3 : Conditions non remplies (BG bas)
**Avant** : `Auditor: OFFLINE`  
**Après** : `Auditor: SKIPPED_NO_TRIGGER`

### Cas 4 : Timeout AI
**Avant** : `Auditor: OFFLINE`  
**Après** : `Auditor: ERROR_TIMEOUT`

### Cas 5 : Verdict OK
**Avant** : `Auditor: SOFTEN conf=0.78 smb×0.65 +3m`  
**Après** : `Auditor: SOFTEN conf=0.78 smb×0.65 +3m` (inchangé, toujours OK)

---

## 🚀 FICHIERS À MODIFIER

### Nouveaux
1. ✅ `AuditorStatusTracker.kt` (créé)

### À Modifier
2. ⏳ `AuditorOrchestrator.kt` - Ajouter `AuditorStatusTracker.updateStatus()` aux 6 points
3. ⏳ `AuditorAIService.kt` - Tracker erreurs réseau/API
4. ⏳ `RtInstrumentationHelpers.kt` - Utiliser `AuditorStatusTracker` au lieu de cache direct
5. ⏳ `DetermineBasalAIMI2.kt` - Vérifier que `aiAuditorEnabled` est bien synchrone

---

## 🔍 PROCHAINES ÉTAPES

### 1. Compléter l'intégration (CODE)

**A. `AuditorOrchestrator.kt`** :
```kotlin
// Point 1 - Disabled
if (!isAuditorEnabled()) {
    AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.OFF)
    // ...
}

// Point 2 - No trigger
if (!shouldTrigger) {
    AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.SKIPPED_NO_TRIGGER)
    // ...
}

// Point 3 - Rate limited
if (!checkRateLimit(now)) {
    AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.SKIPPED_RATE_LIMITED)
    // ...
}

// Point 4 - Verdict OK
if (verdict != null) {
    val status = when (verdict.verdict) {
        AuditorVerdict.VerdictType.CONFIRM -> AuditorStatusTracker.Status.OK_CONFIRM
        AuditorVerdict.VerdictType.SOFTEN -> AuditorStatusTracker.Status.OK_SOFTEN
        AuditorVerdict.VerdictType.REDUCE -> AuditorStatusTracker.Status.OK_REDUCE
        // etc.
    }
    AuditorStatusTracker.updateStatus(status)
    // ...
}

// Point 5 - Timeout
else {
    AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.ERROR_TIMEOUT)
    // ...
}

// Point 6 - Exception
catch (e: Exception) {
    AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.ERROR_EXCEPTION)
    // ...
}
```

**B. `AuditorAIService.kt`** :
```kotlin
suspend fun getVerdict(...): AuditorVerdict? {
    // Check API key first
    val apiKey = getApiKeyForProvider(provider)
    if (apiKey.isNullOrBlank()) {
        AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.OFFLINE_NO_APIKEY)
        return null
    }
    
    try {
        // Make HTTP request
        val response = httpClient.post(...)
        
        if (!response.isSuccessful) {
            AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.ERROR_HTTP)
            return null
        }
        
        // Parse JSON
        val verdict = parseVerdict(response.body)
        return verdict
        
    } catch (e: UnknownHostException) {
        AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.OFFLINE_NO_NETWORK)
        return null
    } catch (e: SocketTimeoutException) {
        AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.ERROR_TIMEOUT)
        return null
    } catch (e: JsonParseException) {
        AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.ERROR_PARSE)
        return null
    } catch (e: Exception) {
        AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.ERROR_EXCEPTION)
        return null
    }
}
```

**C. `RtInstrumentationHelpers.kt`** :
Remplacer entièrement `buildAuditorLine()` avec logique basée statuts (voir section "Update RtInstrumentationHelpers" ci-dessus)

### 2. Tester (VALIDATION)

**Test 1** : Preference disabled
- Désactiver Auditor dans settings
- Observer rT : `Auditor: OFF` ✅

**Test 2** : Enabled mais pas d'API key
- Activer Auditor, retirer API key
- Observer rT : `Auditor: OFFLINE_NO_APIKEY` ✅

**Test 3** : Enabled, API key OK, pas de réseau
- Mode avion
- Observer rT : `Auditor: OFFLINE_NO_NETWORK` ✅

**Test 4** : Enabled, tout OK, mais rate limited
- Faire plusieurs boucles rapides (<5min)
- Observer rT : `Auditor: SKIPPED_RATE_LIMITED` ✅

**Test 5** : Enabled, tout OK, conditions remplies
- Observer rT : `Auditor: SOFTEN conf=0.78 ...` ✅

### 3. Build (VALIDATION)

```bash
./gradlew assembleDebug
```

**Attendu** : ✅ BUILD SUCCESSFUL

---

## 📋 STATUS

**Date** : 2025-12-31  
**Status** : 🔄 EN COURS  
**Priorité** : 🔴 CRITIQUE (visibilité/sécurité)

**Créé** :
- ✅ `AuditorStatusTracker.kt`
- ✅ Documentation complète

**À Faire** :
- ⏳ Intégrer dans `AuditorOrchestrator.kt`
- ⏳ Intégrer dans `AuditorAIService.kt`
- ⏳ Refactorer `RtInstrumentationHelpers.kt`
- ⏳ Build & Test

---

**Note** : Ce fix est **non-bloquant** par design. Aucun changement de logique métier, seulement amélioration de la traçabilité.
