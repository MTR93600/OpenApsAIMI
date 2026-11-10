# 🚨 ANALYSE CRITIQUE : JSON Decoding Exception - Locale Française

**Date:** 2025-12-25  
**Erreur:** `JsonDecodingException` à `$.consoleLog[22]`  
**Cause:** Formatage de nombres avec la locale française dans les logs  
**Gravité:** 🔴 CRITIQUE - Crash de l'app lors de la lecture de l'historique

---

## 🔍 L'Erreur Complète

```
kotlinx.serialization.json.internal.JsonDecodingException: 
Unexpected JSON token at offset 2636: Expected end of the array or comma 
at path: $.consoleLog[22]

JSON input: .....end: 2,4 mg/dL/interval | ->,"TICK ts=1766666103812 bg=130 d.....
```

---

## 🎯 Cause Racine Identifiée

### Le Fragment Révélateur
```
end: 2,4 mg/dL/interval
```

Ce `2,4` **N'EST PAS VALIDE EN JSON** !

### Pourquoi ?

1. **En français**, le séparateur décimal est la **virgule** (`,`)
   - `2.4` → `2,4`
   
2. **En JSON**, seul le **point** (`.`) est valide
   - `2,4` dans une string JSON est interprété comme : `"2` + **FIN D'ÉLÉMENT** + `4"`

3. **Le parseur JSON** voit :
   ```json
   {
     "consoleLog": [
       "...",
       "end: 2,4 mg/dL/interval",  // ❌ Il lit : "end: 2", puis panique sur le "4"
       "TICK ts=..."
     ]
   }
   ```

---

## 🔬 Code Problématique

### Dans Notre Code (Ajouté Récemment)

**Fichier:** `DetermineBasalAIMI2.kt`

#### Lignes 4135-4139 (PK/PD Learner)
```kotlin
consoleLog.add("  │ DIA (learned): ${"%.2f".format(pkpdRuntime.params.diaHrs)}h")
consoleLog.add("  │ Peak (learned): ${"%.0f".format(pkpdRuntime.params.peakMin)}min")
consoleLog.add("  │ fusedISF: ${"%.1f".format(pkpdRuntime.fusedIsf)} mg/dL/U")
consoleLog.add("  │ pkpdScale: ${"%.3f".format(pkpdRuntime.pkpdScale)}")
```

**Problème:** `"%.2f".format()` utilise `Locale.getDefault()`  
**Résultat sur appareil FR:** `4.25` → `4,25` → **CRASH JSON**

#### Lignes 5947-5950 (Basal Learner)
```kotlin
consoleLog.add("  │ shortTerm: ${"%.3f".format(basalLearner.shortTermMultiplier)}")
consoleLog.add("  │ mediumTerm: ${"%.3f".format(basalLearner.mediumTermMultiplier)}")
consoleLog.add("  │ longTerm: ${"%.3f".format(basalLearner.longTermMultiplier)}")
consoleLog.add("  └ combined: ${"%.3f".format(basalLearner.getMultiplier())}")
```

**Même problème !**

#### Lignes 5960-5962 (Reactivity Learner)
```kotlin
consoleLog.add("  │ globalFactor: ${"%.3f".format(analysis.globalFactor)}")
consoleLog.add("  │ shortTermFactor: ${"%.3f".format(analysis.shortTermFactor)}")
consoleLog.add("  │ combinedFactor: ${"%.3f".format(unifiedReactivityLearner.getCombinedFactor())}")
```

**Encore le même problème !**

---

### Dans le Code Existant (Avant Nos Modifications)

**147+ occurrences !** Exemples :

#### Ligne 1206
```kotlin
consoleLog.add("PKPD_TBR_BOOST original=${"%.2f".format(originalRate)} ...")
```

#### Ligne 1463
```kotlin
consoleLog.add("REACTIVITY_CLAMP bg=${bg.roundToInt()} react=${"%.2f".format(currentReactivity)} ...")
```

#### Ligne 1962
```kotlin
consoleLog.add("DIA_DYNAMIC rapidIOB=${String.format("%.1f", rapidIOBAmount)}U ...")
```

**Tous utilisent `String.format()` ou `"%.f".format()` sans `Locale.US` !**

---

## 💥 Impact et Gravité

### Gravité : 🔴 CRITIQUE

1. **Crash de l'App** lors de :
   - Visualisation de l'historique OpenAPS
   - Lecture des APS Results depuis la DB
   - Export de données
   - Analyse des décisions passées

2. **Données Perdues** :
   - Les rT JSON corrompus ne peuvent pas être désérialisés
   - Historique inaccessible
   - Impossible d'analyser les décisions passées

3. **Déclenchement** :
   - ✅ **Reproductible** : Appareil configuré en français
   - ✅ **Systématique** : Chaque exécution de la boucle produit des JSON invalides
   - ✅ **Silencieux** : Pas d'erreur à l'écriture, seulement à la lecture

---

## 🔧 Solution : Forcer Locale.US

### Principe

Kotlin/Java offre deux façons de formater :

1. **`String.format(format, value)`** - Utilise `Locale.getDefault()`
2. **`String.format(Locale.US, format, value)`** - Force la locale US

De même pour l'extension Kotlin :

1. **`"%.2f".format(value)`** - Utilise `Locale.getDefault()`
2. **`"%.2f".format(Locale.US, value)`** - Force la locale US

### Changement Requis

**AVANT (CASSÉ):**
```kotlin
consoleLog.add("value: ${"%.2f".format(x)}")
```

**APRÈS (FIXÉ):**
```kotlin
consoleLog.add("value: ${"%.2f".format(Locale.US, x)}")
```

---

## 📝 Plan de Correction

### Étape 1: Fixer Notre Code (Ajouté Récemment)

✅ **3 endroits à fixer** :
1. Lignes 4135-4139 (PK/PD Learner)
2. Lignes 5947-5950 (Basal Learner)
3. Lignes 5960-5962 (Reactivity Learner)

### Étape 2: Fixer le Code Existant

⚠️ **147+ occurrences** dans `DetermineBasalAIMI2.kt`

**Stratégie recommandée:**
1. Rechercher TOUS les `"%.Xf".format()` et `String.format("%.Xf")`
2. Ajouter `Locale.US` à chacun
3. Vérifier qu'aucun formatage de nombre n'est oublié

### Étape 3: Validation

1. ✅ Build réussi
2. ✅ Test sur appareil FR
3. ✅ Vérification JSON valide
4. ✅ Désérialisation sans erreur

---

## 🧪 Test de Reproduction

### Comment Reproduire

1. **Configurer appareil en français**
   ```
   Paramètres → Système → Langues → Français (France)
   ```

2. **Exécuter la boucle** une fois

3. **Tenter de lire l'historique**
   ```
   OpenAPS → View Last Run
   ```

4. **Résultat attendu (AVANT FIX):** ❌ CRASH
5. **Résultat attendu (APRÈS FIX):** ✅ JSON valide affiché

---

## 📊 Exemples de Transformation

### Locale Française vs US

| Valeur | Locale FR | Locale US | Valide JSON ? |
|--------|-----------|-----------|---------------|
| `4.25` | `4,25` | `4.25` | ❌ FR / ✅ US |
| `1.567` | `1,567` | `1.567` | ❌ FR / ✅ US |
| `45.2` | `45,2` | `45.2` | ❌ FR / ✅ US |
| `0.875` | `0,875` | `0.875` | ❌ FR / ✅ US |

### JSON Produit

**Locale FR (CASSÉ):**
```json
{
  "consoleLog": [
    "DIA (learned): 4,25h",      // ❌ Parseur panique sur ",25"
    "fusedISF: 45,2 mg/dL/U"     // ❌ Parseur panique sur ",2"
  ]
}
```

**Locale US (CORRECT):**
```json
{
  "consoleLog": [
    "DIA (learned): 4.25h",      // ✅ Valide
    "fusedISF: 45.2 mg/dL/U"     // ✅ Valide
  ]
}
```

---

## ⚠️ Causes Aggravantes

### 1. Problème Silencieux

- **NO WARNING** à l'écriture du JSON
- **CRASH DIFFÉRÉ** à la lecture (parfois plusieurs heures/jours après)
- L'utilisateur ne fait pas le lien entre l'écriture et le crash

### 2. Corruption Cumulative

- Chaque exécution de la boucle crée un nouveau rT corrompu
- L'historique entier devient progressivement inutilisable
- Impossible de retracer les décisions passées

### 3. Pas de Fallback

- Si la désérialisation échoue, **tout le batch échoue**
- Un seul rT corrompu peut bloquer l'accès à tout l'historique
- Pas de mécanisme de "skip corrupted entry"

### 4. Validation Absente

- Aucune validation du JSON avant persistence
- Aucun test automatique avec différentes locales
- Le bug peut rester invisible en dev (locale EN)

---

## 🎯 Recommandations

### Court Terme (URGENT)

1. ✅ **Fixer toutes les occurrences** de formatage sans `Locale.US`
2. ✅ **Tester** sur appareil français
3. ✅ **Release HOTFIX** immédiatement

### Moyen Terme

1. 📝 **Ajouter helper function** :
   ```kotlin
   private fun Double.formatUS(decimals: Int): String =
       "%.${decimals}f".format(Locale.US, this)
   ```

2. 🧪 **Tests unitaires** avec différentes locales :
   ```kotlin
   @Test
   fun `consoleLog should produce valid JSON in French locale`() {
       Locale.setDefault(Locale.FRANCE)
       // Run loop
       // Verify JSON.parse succeeds
   }
   ```

3. 🛡️ **JSON Schema Validation** avant persistence :
   ```kotlin
   fun RT.validate(): Boolean {
       return runCatching {
           Json.decodeFromString<RT>(this.serialize())
           true
       }.getOrElse { false }
   }
   ```

### Long Terme

1. 🏗️ **Architecture** : Ne pas stocker du JSON dans des strings
   - Utiliser des objets structurés
   - Sérialiser uniquement au moment de la persistence
   
2. 📊 **Observabilité** : Logger les échecs de désérialisation
   - Metrics : Combien de rT corrompus ?
   - Alertes : Si taux de corruption > 1%

3. 🧹 **Migration Tool** : Nettoyer l'historique existant
   - Scanner les rT corrompus
   - Tenter de les réparer (remplacer `,` par `.`)
   - Marquer comme "repaired" dans metadata

---

## 📚 Références

### Kotlin Documentation
- [String.format](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.text/format.html)
- [Locale](https://docs.oracle.com/javase/8/docs/api/java/util/Locale.html)

### JSON Spec
- [RFC 8259 - JSON Grammar](https://datatracker.ietf.org/doc/html/rfc8259#section-6)
  - Number = `[ minus ] int [ frac ] [ exp ]`
  - Decimal separator **MUST** be `.` (period)

### Similar Issues
- [Stack Overflow: JSON parsing with French locale](https://stackoverflow.com/questions/4713007)
- [Kotlinx.serialization locale issues](https://github.com/Kotlin/kotlinx.serialization/issues/392)

---

**Conclusion:**  
Le problème est **100% reproductible**, **bien identifié**, et **facilement corrigible**.  
La gravité est **CRITIQUE** car elle rend l'historique inutilisable.  
**Fix requis IMMÉDIATEMENT** pour tous les utilisateurs francophones.
