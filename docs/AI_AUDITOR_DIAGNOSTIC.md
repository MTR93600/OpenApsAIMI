# 🔍 AI DECISION AUDITOR - DIAGNOSTIC COMPLET

## Date : 2025-12-27 12:10

## ✅ CORRECTIONS APPLIQUÉES

### 1. Fix Initial : aiAuditorEnabled synchrone
**Ligne 6010** : `finalResult.aiAuditorEnabled` est maintenant défini **IMMÉDIATEMENT** quand la préférence est lue, pas dans le callback async.

### 2. Logs de Debug Ajoutés  
**Ligne 6009** : Log APS ajouté pour tracer la valeur de la préférence :
```kotlin
aapsLogger.debug(LTag.APS, "🧠 AI Auditor: Preference value = $auditorEnabled")
```

---

## 📋 CHECKLIST DE VÉRIFICATION

### Étape 1 : Vérifier la Clé de Préférence

✅ **Confirmé** : La clé existe dans BooleanKey.kt
```kotlin
// core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt:139
AimiAuditorEnabled("aimi_auditor_enabled", false),  // 🧠 AI Decision Auditor
```

### Étape 2 : Vérifier l'Injection de Dépendances

✅ **Confirmé** : Toutes les classes sont @Singleton et injectables

| Classe | Annotation | Fichier |
|--------|-----------|---------|
| `AuditorOrchestrator` | `@Singleton` | AuditorOrchestrator.kt:40 |
| `AuditorDataCollector` | `@Singleton` | AuditorDataCollector.kt:27 |
| `AuditorAIService` | `@Singleton` | AuditorAIService.kt:27 |
| `DecisionModulator` | `object` (singleton natif) | AuditorDataStructures.kt |

✅ **Confirmé** : `AuditorOrchestrator` est injecté dans `DetermineBasalAIMI2.kt:219`
```kotlin
@Inject lateinit var auditorOrchestrator: AuditorOrchestrator
```

### Étape 3 : Vérifier le Flow d'Exécution

```
DetermineBasalAIMI2.determine_basal()
  ↓
Ligne 6007: Lire preferences.get(BooleanKey.AimiAuditorEnabled)
  ↓
Ligne 6009: Logger "🧠 AI Auditor: Preference value = $auditorEnabled"
  ↓
Ligne 6010: finalResult.aiAuditorEnabled = auditorEnabled  // ✅ Synchrone !
  ↓
Ligne 6012: if (auditorEnabled) { ... }
  ↓
Ligne 6081: auditorOrchestrator.auditDecision(...) { }  // Async
```

---

## 🐛 HYPOTHÈSES DE PROBLÈME RESTANT

### Hypothθse 1 : La Préférence ne Se Sauvegarde Pas

**Test** : Lis directement la valeur après activation

```kotlin
// Dans OpenAPSAIMIPlugin.kt, après le AdaptiveSwitchPreference
addPreference(
    AdaptiveSwitchPreference(
        ctx = context,
        booleanKey = BooleanKey.AimiAuditorEnabled,
        title = R.string.aimi_auditor_enabled_title,
        summary = R.string.aimi_auditor_enabled_summary
    ).apply {
        // Log quand la valeur change
        setOnPreferenceChangeListener { _, newValue ->
            aapsLogger.info(LTag.CORE, "🧠 AI Auditor preference changed to: $newValue")
            true
        }
    }
)
```

**Action à faire** : Ajoute ce listener et regarde si le log apparaît quand tu toggles le switch.

### Hypothèse 2 : SharedPreferences vs AdaptivePreferences

Le système AIMI utilise désormais `AdaptivePreferences` qui peut avoir un comportement différent de `SharedPreferences`.

**Vérification** : Dans les logs APS, cherche :
```
🧠 AI Auditor: Preference value = true   // ou false
```

Si tu vois toujours `false` même après activation, c'est que :
1. La préférence ne se sauvegarde pas
2. Ou la clé utilisée n'est pas la bonne

### Hypothèse 3 : Cache de Préférences

Les préférences peuvent être mises en cache. Essaie de :
1. Activer le switch
2. **Force close** l'app (Settings → Apps → AAPS → Force Stop)
3. Relancer l'app
4. Vérifier si ça persiste

---

## 🔬 TESTS À EFFECTUER

### Test 1 : Logs APS (PRIORITÉ 1)

**Action** :
1. Active "Enable AI Decision Auditor" dans les préférences
2. Va dans Settings → Log → Filtre APS
3. Attends 1 cycle AIMI (5 min)
4. Cherche dans les logs :
   ```
   🧠 AI Auditor: Preference value = true
   ```

**Résultat Attendu** :
- ✅ Si tu vois `true` : La préférence fonctionne !
- ❌ Si tu vois `false` : Problème de sauvegarde

### Test 2 : RT aiAuditorEnabled (PRIORITÉ 2)

**Action** :
1. Après avoir activé et attendu 1 cycle
2. Regarde le RT (Résultat AIMI)

**Résultat Attendu** :
```json
{
  "aiAuditorEnabled": true,  // ✅ Doit être true !
  "aiAuditorVerdict": null,  // null au début (normal)
  ...
}
```

### Test 3 : Persistance après Redémarrage (PRIORITÉ 3)

**Action** :
1. Active le switch
2. Force close l'app
3. Relance
4. Vérifie que le switch est toujours activé
5. Vérifie le RT

**Résultat Attendu** :
- ✅ Switch reste ON
- ✅ `aiAuditorEnabled: true` dans le RT

---

## 🛠️ SOLUTIONS POSSIBLES

### Solution A : Ajouter un Listener sur la Préférence

Si la préférence ne se sauvegarde pas, ajoute un listener explicite qui force la sauvegarde :

```kotlin
// Dans OpenAPSAIMIPlugin.kt
addPreference(
    AdaptiveSwitchPreference(
        ctx = context,
        booleanKey = BooleanKey.AimiAuditorEnabled,
        title = R.string.aimi_auditor_enabled_title,
        summary = R.string.aimi_auditor_enabled_summary
    ).apply {
        setOnPreferenceChangeListener { preference, newValue ->
            val enabled = newValue as Boolean
            aapsLogger.info(LTag.CORE, "🧠 AI Auditor: User toggled to $enabled")
            
            // Force commit
            preferences.put(BooleanKey.AimiAuditorEnabled, enabled)
            
            // Log confirmation
            val confirmed = preferences.get(BooleanKey.AimiAuditorEnabled)
            aapsLogger.info(LTag.CORE, "🧠 AI Auditor: Confirmed value = $confirmed")
            
            true
        }
    }
)
```

### Solution B : Vérifier la Clé String vs Boolean

Vérifie que dans `OpenAPSAIMIPlugin.kt`, la ligne est bien :

```kotlin
booleanKey = BooleanKey.AimiAuditorEnabled,  // ✅ Correct
```

Et PAS :

```kotlin
stringKey = StringKey.AimiAuditorEnabled,  // ❌ Incorrect !
```

### Solution C : Reset des Préférences

Si tout le reste échoue, reset les préférences AIMI :

1. Settings → AIMI Settings
2. Scroll en bas
3 "Reset to Defaults" (si disponible)
4. Réactive "Enable AI Auditor"

---

## 📊 TABLEAU DE DIAGNOSTIC

| Élément | Status | Notes |
|---------|--------|-------|
| BooleanKey existe | ✅ | BooleanKey.kt:139 |
| @Inject AuditorOrchestrator | ✅ | DetermineBasalAIMI2.kt:219 |
| @Singleton classes | ✅ | Toutes OK |
| Code synchrone aiAuditorEnabled | ✅ | Ligne 6010 |
| Log debug ajouté | ✅ | Ligne 6009 |
| Compilation réussie | ✅ | BUILD SUCCESSFUL |
| UI Switch visible | ❓ | À vérifier |
| Préférence sauvegardée | ❓ | **À TESTER** |
| Log APS visible | ❓ | **À TESTER** |
| RT reflète enabled=true | ❓ | **À TESTER** |

---

## 🎯 PROCHAINES ACTIONS UTILISATEUR

### Action 1 : Capture Logs APS (URGENT)

```bash
# Dans Android Studio Logcat, filtre :
APS
```

Puis :
1. Active le switch "Enable AI Auditor"
2. Attends 5 minutes
3. Cherche "🧠 AI Auditor"
4. **Envoie le screenshot des logs**

### Action 2 : Vérifier SharedPreferences Directement

```bash
# Via adb
adb shell
run-as info.nightscout.androidaps
cd shared_prefs
cat adaptive_preferences.xml | grep aimi_auditor
```

Devrais voir :
```xml
<boolean name="aimi_auditor_enabled" value="true" />
```

### Action 3 : Test Minimal

Si rien ne fonctionne, essaie ce test minimal :

1. **Désactive** tous les autres plugins AIMI (Autodrive, WCycle, etc.)
2. Active **SEULEMENT** "Enable AI Decision Auditor"
3. Redémarre l'app
4. Vérifie le RT

---

## 🧠 ÉTAT ACTUEL DU CODE

```
✅ Préférence définie : BooleanKey.AimiAuditorEnabled
✅ UI créée : AdaptiveSwitchPreference
✅ Classes injectées : @Singleton sur toutes
✅ Code fixé : aiAuditorEnabled synchrone
✅ Logs ajoutés : aapsLogger.debug
✅ Compilation : BUILD SUCCESSFUL
```

**Prochaine étape** : **L'utilisateur DOIT capturer les logs APS pour voir si `preferences.get()` retourne `true` ou `false`.**

---

**Créé le** : 2025-12-27 12:10  
**Status** : ✅ CODE PRÊT - EN ATTENTE DE TESTS UTILISATEUR  
