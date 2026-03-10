# ✅ TRAJECTORY GUARD - UI PREFERENCE ADDED

**Date**: 2026-01-01 21:48 CET  
**Status**: 🟢 **COMPLETE & COMPILED**  
**Impact**: Users can now enable/disable Trajectory Guard via Settings

---

## 📍 LOCALISATION

**Menu Path** (dans AAPS) :
```
Settings
  → OpenAPS AIMI
    → Adaptive PK/PD
      → 🌀 Trajectory Guard  ← NOUVEAU MENU
```

---

## 🔧 MODIFICATIONS APPORTÉES

### 1. OpenAPSAIMIPlugin.kt (lignes 1206-1226)

**Ajout du PreferenceScreen** :
```kotlin
// 🌀 Phase-Space Trajectory Control
addPreference(preferenceManager.createPreferenceScreen(context).apply {
    key = "AIMI_Trajectory"
    title = "🌀 Trajectory Guard"
    
    addPreference(PreferenceCategory(context).apply {
        title = "Phase-Space Control Settings"
    })
    
    addPreference(
        AdaptiveSwitchPreference(
            ctx = context,
            booleanKey = BooleanKey.OApsAIMITrajectoryGuardEnabled,
            title = R.string.oaps_aimi_trajectory_enabled_title,
            summary = R.string.oaps_aimi_trajectory_enabled_summary
        )
    )
})
```

### 2. strings.xml (lignes 327-328)

**Strings ajoutés** :
```xml
<string name="oaps_aimi_trajectory_enabled_title">Enable Trajectory Guard</string>
<string name="oaps_aimi_trajectory_enabled_summary">
    Phase-space control system that analyzes glucose trajectory geometry 
    to prevent over/under-correction and improve convergence to target.
</string>
```

---

## 🎯 COMPORTEMENT UI

### Quand l'utilisateur active le switch :

1. **Toggle ON** :
   - `BooleanKey.OApsAIMITrajectoryGuardEnabled` = `true`
   - Le système commence l'analyse de trajectoire **au prochain loop**
   - Logs apparaissent dans rT : `🌀 TRAJECTORY ANALYSIS`
   - Champs structurés populés : `trajectoryEnabled: true`, etc.

2. **Toggle OFF** (default) :
   - `BooleanKey.OApsAIMITrajectoryGuardEnabled` = `false`
   - Aucun traitement trajectoire
   - `trajectoryEnabled: false` dans tous les rT
   - **Zéro impact** sur performances

---

## 📱 APPARENCE DANS L'APP

```
┌─────────────────────────────────────┐
│ OpenAPS AIMI                        │
├─────────────────────────────────────┤
│ Adaptive PK/PD                  >   │
│ 🌀 Trajectory Guard            >   │ ← NOUVEAU
│ Enable Steps From Watch        ⚪   │
│ Enable xDrip 1-min readings    ⚪   │
└─────────────────────────────────────┘
```

**Clic sur "🌀 Trajectory Guard"** :

```
┌─────────────────────────────────────┐
│ 🌀 Trajectory Guard                 │
├─────────────────────────────────────┤
│ Phase-Space Control Settings        │
├─────────────────────────────────────┤
│ Enable Trajectory Guard        ⚪   │ ← SWITCH
│                                     │
│ Phase-space control system that     │
│ analyzes glucose trajectory         │
│ geometry to prevent over/under-     │
│ correction and improve convergence  │
│ to target.                          │
└─────────────────────────────────────┘
```

---

## ✅ VALIDATION

### Checks effectués :

- [x] Préférence ajoutée dans `OpenAPSAIMIPlugin.kt`
- [x] Strings ajoutés dans `strings.xml`
- [x] Build successful ✅
- [x] Pas d'erreurs de compilation
- [x] Préférence liée au bon `BooleanKey`

### Tests à effectuer (sur device) :

1. **Navigation** :
   - Ouvrir Settings → OpenAPS AIMI
   - Vérifier que "🌀 Trajectory Guard" apparaît
   - Cliquer dessus → écran de préférences s'ouvre

2. **Switch** :
   - Toggle ON
   - Vérifier que la valeur est sauvegardée
   - Redémarrer AAPS
   - Vérifier que la valeur persiste

3. **Fonctionnel** :
   - Avec switch OFF : aucun log trajectoire
   - Avec switch ON : logs `🌀 TRAJECTORY ANALYSIS` dans rT

---

## 🔄 SYNCHRONISATION

### Rappel de l'écosystème complet :

**Feature Flag** (`BooleanKey.kt`) :
```kotlin
OApsAIMITrajectoryGuardEnabled("key_aimi_trajectory_guard_enabled", false)
```

**Lecture dans le code** (`DetermineBasalAIMI2.kt`) :
```kotlin
if (preferences.get(BooleanKey.OApsAIMITrajectoryGuardEnabled)) {
    // ... trajectory guard logic ...
}
```

**Modification par l'utilisateur** :
```
UI Switch → SharedPreferences → BooleanKey.value → Code reads
```

---

## 📊 IMPACT UTILISATEUR

### Activation progressive recommandée :

**Phase 1** : Shadow Mode (OFF par défaut)
- Les utilisateurs peuvent activer manuellement
- Données collectées mais pas de modulation agressive

**Phase 2** : Beta Testing (ON pour beta users)
- Groupe restreint active le feature
- Monitoring journalier des métriques

**Phase 3** : General Availability
- Documentation utilisateur publiée
- Activation recommandée dans release notes

---

## 🎓 DOCUMENTATION UTILISATEUR (future)

### Guide rapide :

**Qu'est-ce que le Trajectory Guard ?**

Le Trajectory Guard analyse la "forme" de votre évolution glycémique dans le temps pour :
- 🎯 Converger plus rapidement vers la cible
- 🔄 Éviter les oscillations (yo-yo)
- ⚠️ Détecter les sur-corrections avant qu'elles arrivent

**Dois-je l'activer ?**

- **OUI** si vous expérimentez beaucoup d'oscillations
- **OUI** si vous avez des spirals BG fréquents
- **PEUT-ÊTRE** si vous voulez tester une nouvelle approche
- **NON** si vous êtes satisfait du contrôle actuel

**Comment l'utiliser ?**

1. Settings → OpenAPS AIMI → 🌀 Trajectory Guard
2. Activer le switch
3. Observer les logs pendant 48-72h
4. Monitorer TIR / hypos / variabilité
5. Ajuster si nécessaire (support forum)

---

## 🐛 TROUBLESHOOTING

### Problème : Switch ne sauvegarde pas

**Solution** :
- Vérifier permissions SharedPreferences
- Essayer redémarrage AAPS
- Vérifier logs Android : `adb logcat | grep Preference`

### Problème : Aucun effet après activation

**Solution** :
- Vérifier dans rT que `trajectoryEnabled: true`
- Si `false`, vérifier logs d'erreur
- Possiblement données insuffisantes (attendre 90 min)

### Problème : Trop de warnings

**Solution** :
- Normal au début (phase d'adaptation)
- Réduire agressivité avec ajustements (future feature)
- Désactiver temporairement si critique

---

## ✍️ SIGNATURE

**Developer**: Lyra (Antigravity AI)  
**Feature**: UI Preference for Trajectory Guard  
**Date**: 2026-01-01 21:48 CET  
**Build**: ✅ SUCCESS  
**Files Modified**: 2  
**Lines Added**: ~25  

**Next Step**: User Testing & Feedback Collection

---

*"Control is not about force, it's about harmony with the system's natural trajectory."* 🌀✨

---

**END OF UI PREFERENCE DOCUMENTATION**
