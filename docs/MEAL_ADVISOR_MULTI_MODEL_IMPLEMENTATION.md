# MEAL ADVISOR - MULTI-MODEL SUPPORT IMPLEMENTATION

**Date:** 2025-12-20 17:10  
**Status:** 🔄 EN COURS  

---

## 🎯 **OBJECTIF**

1. ✅ Identifier erreur Gemini (JSON parsing)
2. 🔄 Ajouter support multi-modèles (OpenAI, Gemini, DeepSeek, Claude)
3. 🔄 Ajouter préférences API keys
4. 🔄 Créer prompts uniformes
5. 🔄 Assurer récupération complète résultat
6. 🔄 Vérifier Gemini Flash 3.0 (non, c'est 2.0 Flash Exp maintenant)

---

## 🐛 **ERREUR IDENTIFIÉE**

### **Screenshot Error:**
```
Gemini Error: Unterminated string at character 10 of { food_
```

### **Cause Root:**
`FoodRecognitionService.kt` ligne 197-198:
```kotlin
private fun parseStartContent(content: String): EstimationResult {
    val cleanedJson = content.replace("```json", "").replace("```", "").trim()
    val result = JSONObject(cleanedJson)  // ← Peut crasher si JSON invalide
```

**Problèmes:**
1. Gemini peut retourner JSON avec caractères d'échappement non standards
2. Nettoyage insuffisant (juste retire ```json)
3. Pas de gestion JSON incomplet/malformé
4. Pas de récupération complète si réponse tronquée

---

## 🏗️ **ARCHITECTURE SOLUTION**

### **1. Interface Commune**
✅ **Créé:** `AIVisionProvider.kt`
- Interface `AIVisionProvider`
- Data class `EstimationResult`
- Object `FoodAnalysisPrompt` avec:
  - Prompt sys

tème unifié
  - Fonction `cleanJsonResponse()` robuste
  - Fonction `parseJsonToResult()` avec fallbacks

### **2. Providers à Implémenter**

| Provider | Model | Status | API Endpoint |
|----------|-------|--------|--------------|
| **OpenAI** | gpt-4o | 🔄 À migrer | `https://api.openai.com/v1/chat/completions` |
| **Gemini** | gemini-2.0-flash-exp | 🔄 À corriger | `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-exp:generateContent` |
| **DeepSeek** | deepseek-chat | 🔄 À créer | `https://api.deepseek.com/v1/chat/completions` |
| **Claude** | claude-3-5-sonnet | 🔄 À créer | `https://api.anthropic.com/v1/messages` |

### **3. Préférences API Keys**

À ajouter dans `StringKey.kt`:
```kotlin
AimiAdvisorOpenAIKey
AimiAdvisorGeminiKey
AimiAdvisorDeepSeekKey  // NOUVEAU
AimiAdvisorClaudeKey    // NOUVEAU
```

---

## 📝 **MODÈLES CONFIRMÉS (Web Search)**

### **Gemini:**
- ✅ Disponible: `gemini-2.0-flash-exp` (décembre 2024)
- Endpoint: `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-exp:generateContent`
- Supporte: Vision, JSON mode avec `responseMimeType: "application/json"`

### **DeepSeek:**
- ✅ Compatible OpenAI SDK
- Model: `deepseek-chat` (supporte vision via DeepSeek-VL)
- Endpoint: `https://api.deepseek.com/v1/chat/completions`

### **Claude:**
- ✅ Claude 3.5 Sonnet (meilleur vision model Anthropic)
- Endpoint: `https://api.anthropic.com/v1/messages`
- Requires headers: `x-api-key`, `anthropic-version: 2023-06-01`

### **Perplexity:**
- ❌ PAS de support vision direct
- Sonar API lancée janvier 2025 sans vision
- **EXCLUS de l'implémentation**

---

## 🔧 **CORRECTIONS APPORTÉES**

### **1. Nettoyage JSON Robuste**
```kotlin
fun cleanJsonResponse(rawJson: String): String {
    return rawJson
        .replace("```json", "")
        .replace("```", "")
        .replace("\n", " ")     // NOUVEAU: enlève retours ligne
        .replace("\r", " ")     // NOUVEAU: enlève CR
        .trim()
        .let { cleaned ->
            // NOUVEAU: Trouve premier { et dernier }
            if (!cleaned.startsWith("{")) {
                val start = cleaned.indexOf('{')
                val end = cleaned.lastIndexOf('}')
                if (start >= 0 && end > start) {
                    cleaned.substring(start, end + 1)
                } else {
                    cleaned
                }
            } else {
                cleaned
            }
        }
}
```

### **2. Parsing avec Fallbacks**
```kotlin
fun parseJsonToResult(cleanedJson: String): EstimationResult {
    val result = JSONObject(cleanedJson)
    
    return EstimationResult(
        description = result.optString("food_name", "Unknown food"),  // opt* au lieu de get*
        carbsGrams = result.optDouble("carbs", 0.0),
        proteinGrams = result.optDouble("protein", 0.0),
        fatGrams = result.optDouble("fat", 0.0),
        fpuEquivalent = result.optDouble("fpu", 0.0),
        reasoning = result.optString("reasoning", "No reasoning provided")
    )
}
```

---

## 📋 **PLAN D'IMPLÉMENTATION**

### **Étape 1: Providers** (en cours)
- [ ] `OpenAIVisionProvider.kt`
- [ ] `GeminiVisionProvider.kt` (avec fix JSON)
- [ ] `DeepSeekVisionProvider.kt`
- [ ] `ClaudeVisionProvider.kt`

### **Étape 2: Préférences**
- [ ] Ajouter keys dans `StringKey.kt`
- [ ] Ajouter dans preferences XML

### **Étape 3: Refonte Service**
- [ ] `FoodRecognitionService.kt` → Factory pattern
- [ ] Provider selection dynamique

### **Étape 4: UI**
- [ ] `MealAdvisorActivity.kt` → Update spinner
- [ ] Ajouter OpenAI, DeepSeek, Claude

### **Étape 5: Tests**
- [ ] Compilation
- [ ] Test chaque provider
- [ ] Vérifier JSON parsing robuste

---

**STATUS:** Architecture créée, implémentation providers en cours...
