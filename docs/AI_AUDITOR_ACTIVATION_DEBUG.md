# 🔍 AI AUDITOR - VÉRIFICATION ACTIVATION

## Date: 2025-12-29 00:05

## 🔬 SYMPTÔME RAPPORTÉ

**Situation** : BG 160 mg/dL, Delta +4 mg/dL/5min  
**Résultat** : `aiAuditorEnabled: false` dans RT  
**Attendu** : L'auditor devrait être actif et auditer cette décision

---

## ✅ VÉRIFICATIONS EFFECTUÉES

### 1. Code d'Activation (DetermineBasalAIMI2.kt)

**Lignes 6046-6054** : ✅ INTACT
```kotlin
val auditorEnabled = preferences.get(BooleanKey.AimiAuditorEnabled)
aapsLogger.debug(LTag.APS, "🧠 AI Auditor: Preference value = $auditorEnabled")
finalResult.aiAuditorEnabled = auditorEnabled

if (auditorEnabled) {
    // ... audit logic
}
```

**Status** : ✅ Non modifié par mes changements (learners)

---

### 2. Conditions de Trigger (DecisionModulator.kt)

**Lignes 203-250** : ✅ CORRECT

**Logique** :
```kotlin
fun shouldTriggerAudit(...) {
    // NEVER trigger during prebolus
    if (inPrebolusWindow) return false
    
    // Only SKIP if ALL true:
    val isStable = abs(delta) < 0.5 && abs(shortAvgDelta) < 0.5
    val noAction = smbProposed < 0.05
    val lowIob = iob < 0.5
    val noRecentSmb = smb30min < 0.1
    
    if (isStable && noAction && lowIob && noRecentSmb) {
        return false  // Skip audit
    }
    
    return true  // AUDIT!
}
```

**Avec BG=160, Delta=+4** :
- `isStable` = `false` (delta +4 >> 0.5) ✅
- Donc **return `true`** → Devrait trigger ✅

**Status** : ✅ Logique correcte

---

### 3. Orchestrator (AuditorOrchestrator.kt)

**Lignes 133-166** : ✅ CORRECT

**Flux** :
1. Check `isAuditorEnabled()` → Si false, skip
2. Check `shouldTriggerAudit()` → Si false, skip  
3. Check `checkRateLimit()` → Si rate limited, skip
4. Launch async audit

**Status** : ✅ Logique correcte

---

## 🐛 DIAGNOSTIC : POURQUOI `aiAuditorEnabled: false` ?

### Hypothèses par Ordre de Probabilité

### **H1 : Préférence Non Sauvegardée** (90%)

**Symptôme** : La préférence `AimiAuditorEnabled` est cochée dans l'UI mais pas persistée.

**Test** :
1. Ouvrir les logs APS
2. Chercher : `"🧠 AI Auditor: Preference value = "`
3. Si tu vois `false`, la préférence n'est pas sauvegardée

**Cause possible** :
- Preferences key `BooleanKey.AimiAuditorEnabled` pas défini correctement
- SharedPreferences pas synchronisées
- Redémarrage app requis après activation

**Solution** :
```kotlin
// Vérifier dans BooleanKey.kt (ou équivalent)
AimiAuditorEnabled("aimi_auditor_enabled", false)
```

---

### **H2 : Rate Limiting Trop Strict** (5%)

**Symptôme** : L'auditor s'active mais est immédiatement rate-limited.

**Test** : Chercher dans logs :
```
"AI Auditor: Rate limited"
```

**Solution** : Augmenter `AimiAuditorMaxPerHour` dans les préférences

---

### **H3 : Exception Silencieuse** (3%)

**Symptôme** : Exception levée dans le bloc `if (auditorEnabled)` capture par le try/catch.

**Test** : Chercher dans logs :
```
"⚠️ AI Auditor error: "
```

**Solution** : Lire le stacktrace

---

### **H4 : Prebolus Window Détecté à Tort** (2%)

**Symptôme** : `inPrebolusWindow = true` alors que tu n'es pas en mode repas.

**Test** : Vérifier si meal mode actif dans les 30min

**Solution** : Désactiver tous les meal modes

---

## 🔧 ACTIONS DE DEBUG IMMÉDIATES

### Action 1 : Vérifier le Log de Préférence

**Dans les logs APS**, chercher :
```
🧠 AI Auditor: Preference value = true/false
```

Si `false` → **H1 confirmée** (préférence non sauvegardée)

---

### Action 2 : Forcer l'Activation via Code

**Temporary debug** - Modifier ligne 6046 :
```kotlin
// BEFORE:
val auditorEnabled = preferences.get(BooleanKey.AimiAuditorEnabled)

// AFTER (debug):
val auditorEnabled = true  // FORCE ENABLE FOR DEBUG
```

Recompiler, tester. Si ça marche → **H1 confirmée**.

---

### Action 3 : Ajouter Plus de Logging

**Dans AuditorOrchestrator.kt**, ligne 142, ajouter :
```kotlin
val shouldTrigger = DecisionModulator.shouldTriggerAudit(...)

aapsLogger.info(LTag.APS, "🧠 AI Auditor: shouldTrigger=$shouldTrigger, bg=$bg, delta=$delta")
```

Cela permettra de voir si `shouldTriggerAudit` retourne bien `true`.

---

## 📊 CHECKLIST DE VÉRIFICATION

- [ ] Préférence "Enable AI Auditor" cochée dans l'UI
- [ ] App redémarrée après activation
- [ ] Logs APS montrent `Preference value = true`
- [ ] Pas de meal mode actif (évite prebolus window)
- [ ] `Max Audits Per Hour` > 0 (défaut: 12)
- [ ] Aucune erreur dans logs (`AI Auditor error`)

---

## 🎯 CONCLUSION

**MES MODIFICATIONS N'ONT PAS ALTÉRÉ** :
- ✅ Le code d'activation de l'auditor
- ✅ Les conditions de trigger
- ✅ L'flow de l'orchestrator

**Le problème est probablement** :
- 🔴 **90%** : Préférence non persistée (H1)
- 🟡 **5%** : Rate limiting (H2)
- 🟡 **5%** : Autre (exceptions, prebolus, etc.)

**NEXT STEP** : Vérifier les logs pour confirmer H1 ! 🔍

---

**Créé le** : 2025-12-29 00:05  
**Status** : ✅ CODE VÉRIFIÉ - HYPOTHÈSES DIAGNOSTIQUES PRÊTES
