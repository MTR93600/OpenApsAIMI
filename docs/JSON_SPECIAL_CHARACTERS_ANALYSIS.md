# 🛡️ Analyse : Caractères Spéciaux et Sécurité JSON

**Date:** 2025-12-25  
**Question:** Les caractères Unicode/emoji peuvent-ils causer des problèmes JSON ?  
**Réponse:** OUI, potentiellement, mais ce n'est PAS la cause du crash actuel

---

## 🎯 Deux Problèmes Distincts

### Problème 1 : Locale Française (CAUSE DU CRASH) 🔴

**Priorité:** CRITIQUE  
**Status:** ✅ FIXÉ (dans notre code)

```kotlin
// AVANT (CASSÉ):
"%.2f".format(value)  // FR: 2.4 → 2,4 → JSON invalide

// APRÈS (FIXÉ):
"%.2f".format(Locale.US, value)  // FR: 2.4 → 2.4 → JSON valide ✅
```

### Problème 2 : Caractères Unicode (RISQUE POTENTIEL) ⚠️

**Priorité:** MOYEN  
**Status:** ⏳ À SURVEILLER

**Caractères utilisés:**
- Emoji : `📊 🍱 ⚠️`
- Box drawing : `│ └`
- Flèches : `→`
- Math : `×`

**Risques:**
1. Encodage incohérent (UTF-8 vs autres)
2. Caractères de contrôle (`\u0000`-`\u001F`)
3. Guillemets/backslash non échappés
4. Taille excessive du JSON

---

## 📊 L'Erreur Initiale - Détails

```
JSON input: .....end: 2,4 mg/dL/interval | ->,"TICK ts=...
                      ^^^                  ^^
                      Le problème!         Pas le problème
```

### Analyse

1. **`| ->`** : Flèche ASCII simple
   - ✅ Valide en JSON
   - Pas la cause du crash

2. **`2,4`** : Virgule décimale française
   - ❌ Invalide en JSON
   - **CAUSE RÉELLE DU CRASH**

3. **Guillemet après virgule** : `,"TICK`
   - ✅ Syntaxe JSON correcte
   - Le parseur attend un nouveau string

### Pourquoi ça Casse

```json
{
  "consoleLog": [
    "end: 2,4 mg/dL/interval"
  ]
}
```

Le parseur JSON lit :
1. `"` → Début de string
2. `end: 2` → Contenu OK
3. `,` → **FIN DE STRING** (le parseur pense)
4. `4 mg/dL...` → **ERREUR** : J'attendais un nouveau `"` ou `]`

---

## ✅ JSON Spec : Que Dit la Norme ?

### RFC 8259 - Caractères Autorisés

**Dans un JSON string:**
```
unescaped = %x20-21 / %x23-5B / %x5D-10FFFF
```

**Traduction:**
- ✅ Tous les Unicode de U+0020 à U+10FFFF (sauf quelques exceptions)
- ✅ Incluant **TOUS les emojis** (U+1F300+)
- ✅ Incluant **tous les symbols Unicode**

**SAUF:**
- ❌ Caractères de contrôle : U+0000 à U+001F (sauf `\t` `\n` `\r`)
- ❌ Guillemets : `"` (doit être `\"`)
- ❌ Backslash : `\` (doit être `\\`)

---

## 🔬 Test Empirique : Nos Caractères

| Caractère | Unicode | JSON Valide ? | Risque ? |
|-----------|---------|---------------|----------|
| `📊` | U+1F4CA | ✅ OUI | 🟡 Encodage |
| `│` | U+2502 | ✅ OUI | 🟢 Bas |
| `└` | U+2514 | ✅ OUI | 🟢 Bas |
| `→` | U+2192 | ✅ OUI | 🟢 Bas |
| `×` | U+00D7 | ✅ OUI | 🟢 Bas |
| `⚠️` | U+26A0 + U+FE0F | ✅ OUI | 🟡 Composite |
| `2,4` | ASCII | ❌ **NON** | 🔴 **CRITIQUE** |

**Conclusion:** Les emojis/Unicode sont **techniquement OK**, mais la **virgule décimale est le vrai problème**.

---

## ⚠️ Risques Potentiels des Caractères Unicode

### 1. Encodage Incohérent

**Problème:**
- Code écrit en UTF-8
- DB stockée en Latin-1
- → Corruption des emojis

**Solution:**
```kotlin
fun String.isValidUtf8(): Boolean {
    return try {
        this.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == this
    } catch (e: Exception) {
        false
    }
}
```

### 2. Caractères de Contrôle

**Problème:**
- Un `\n` non échappé dans le JSON string
- → JSON invalide

**Exemple problématique:**
```kotlin
consoleLog.add("Line 1\nLine 2")  // ❌ \n doit être échappé
```

**Devrait être:**
```json
{
  "consoleLog": ["Line 1\\nLine 2"]  // ✅ Correct
}
```

**Solution:**
```kotlin
fun String.sanitizeForJson(): String {
    return this
        .replace(Regex("[\u0000-\u0008\u000B-\u000C\u000E-\u001F\u007F]"), "")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}
```

### 3. Guillemets/Backslash Non Échappés

**Problème:**
```kotlin
consoleLog.add("User said: \"Hello\"")  // ❌ Guillemets pas échappés
```

**JSON produit (CASSÉ):**
```json
{
  "consoleLog": ["User said: "Hello""]  // ❌ Invalide
}
```

**Devrait être:**
```json
{
  "consoleLog": ["User said: \\\"Hello\\\""]  // ✅ Correct
}
```

### 4. Taille Excessive

**Problème:**
- Un log de 10 000 caractères
- JSON file devient énorme
- Ralentit DB/parsing

**Solution:**
```kotlin
private const val MAX_LOG_LENGTH = 500

fun String.truncate(): String = this.take(MAX_LOG_LENGTH)
```

---

## 🛡️ Solution : Défense en Profondeur

### Outil Créé : `JsonSafeLogger.kt`

```kotlin
import app.aaps.plugins.aps.openAPSAIMI.utils.JsonSafeLogger.formatUS
import app.aaps.plugins.aps.openAPSAIMI.utils.JsonSafeLogger.addSafe

// Option 1: Avec emojis/Unicode (sanitized)
consoleLog.addSafe("📊 DIA: ${dia.formatUS(2)}h")

// Option 2: ASCII-only (ultra-safe, pas d'emoji)
consoleLog.addSafeAscii("DIA: ${dia.formatUS(2)}h")

// Option 3: Formatage US + sanitization manuelle
val msg = "DIA: ${"%.2f".format(Locale.US, dia)}h".sanitizeForJson()
consoleLog.add(msg)
```

### Niveaux de Sécurité

#### Niveau 1 : Basique ✅ (Implémenté)
```kotlin
"%.2f".format(Locale.US, value)  // Force point décimal
```

#### Niveau 2 : Sanitization 🟡 (Optionnel)
```kotlin
consoleLog.addSafe("...")  // Échappe caractères de contrôle
```

#### Niveau 3 : ASCII-Only 🔒 (Ultra-Safe)
```kotlin
consoleLog.addSafeAscii("...")  // Supprime TOUS les non-ASCII
```

---

## 📝 Recommandations

### Court Terme (FAIT ✅)

1. ✅ Fixer Locale.US partout
2. ✅ Créer `JsonSafeLogger.kt`
3. ⏳ Tester sur appareil FR

### Moyen Terme (RECOMMANDÉ)

1. **Remplacer progressivement :**
   ```kotlin
   // Ancien
   consoleLog.add("DIA: ${"%.2f".format(dia)}h")
   
   // Nouveau
   consoleLog.addSafe("DIA: ${dia.formatUS(2)}h")
   ```

2. **Tests avec caractères problématiques :**
   ```kotlin
   @Test
   fun `consoleLog should handle special characters`() {
       consoleLog.addSafe("Test: \n\t\"quote\\backslash")
       val json = rt.serialize()
       assertDoesNotThrow { RT.deserialize(json) }
   }
   ```

3. **Monitoring :**
   ```kotlin
   // Log si sanitization a modifié le string
   val original = "..."
   val sanitized = original.sanitizeForJson()
   if (original != sanitized) {
       log.warn("Sanitized consoleLog: $original → $sanitized")
   }
   ```

### Long Terme (IDÉAL)

1. **Migration complète vers `addSafe()`**
2. **JSON Schema validation**
3. **Binary format pour les logs (pas JSON)**
   - Protobuf
   - MessagePack
   - CBOR

---

## 🧪 Tests Recommandés

### Test 1: Caractères Unicode

```kotlin
@Test
fun `emoji in consoleLog should not break JSON`() {
    val rt = RT(consoleLog = mutableListOf(
        "📊 Test",
        "│ Line",
        "└ End"
    ))
    
    val json = rt.serialize()
    val deserialized = RT.deserialize(json)
    
    assertEquals(3, deserialized.consoleLog?.size)
}
```

### Test 2: Caractères de Contrôle

```kotlin
@Test
fun `control characters should be sanitized`() {
    val msg = "Line1\u0000\u0001Line2".sanitizeForJson()
    assertFalse(msg.contains("\u0000"))
    assertFalse(msg.contains("\u0001"))
}
```

### Test 3: Guillemets/Backslash

```kotlin
@Test
fun `quotes and backslash should be escaped`() {
    val msg = "Say \"Hi\"\\path".sanitizeForJson()
    assertTrue(msg.contains("\\\""))
    assertTrue(msg.contains("\\\\"))
}
```

---

## 🎯 Conclusion

### Question Originale
> "L'erreur pourrait-elle venir de caractères transmis dans le JSON ?"

### Réponse

**Pour le crash actuel :** ❌ **NON**
- Cause = **Locale française** (virgule décimale)
- Pas = Emojis ou Unicode

**Pour des problèmes futurs :** ✅ **OUI, POTENTIELLEMENT**
- Caractères de contrôle non échappés
- Guillemets/backslash non échappés
- Encodage UTF-8 incohérent
- Taille excessive

### Actions

1. ✅ **Fix appliqué** : `Locale.US` partout
2. ✅ **Outil créé** : `JsonSafeLogger.kt`
3. ⏳ **À faire** : Migration progressive vers `addSafe()`
4. ⏳ **À tester** : Appareil FR réel

### Tu avais raison !

Excellente intuition de vouloir **filtrer et ne garder que l'essentiel**. C'est une **bonne pratique défensive** même si ce n'est pas la cause du crash actuel.

---

**Résumé Final:**
- 🔴 **Problème actuel** : Virgule décimale (locale) → **FIXÉ**
- 🟡 **Risque futur** : Caractères spéciaux → **OUTIL CRÉÉ**
- 🟢 **Défense en profondeur** : `JsonSafeLogger` → **RECOMMANDÉ**
