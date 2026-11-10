# Meal Advisor — pipeline réponse (qualité / sécurité)

**Dernière mise à jour** : couche produit côté vision LLM + UI.

## Couches (ordre)

1. **Contexte utilisateur** — `MealVisionUserPrompt`  
   - Suppression des caractères de contrôle, longueur bornée.  
   - Remplacement `"` → `'` et `\` → `/` dans le texte libre pour éviter de casser le JSON de la requête HTTP ou d’injecter des guillemets dans le champ « User description ».

2. **API**  
   - **OpenAI** & **DeepSeek** : `response_format: { "type": "json_object" }` (sortie JSON objet valide, aligné sur la doc OpenAI pour les chat completions).  
   - **DeepSeek** : si la réponse HTTP **400** mentionne `response_format`, **nouvel essai sans** ce champ (compatibilité API).  
   - **Gemini** : `responseMimeType: application/json` (déjà en place).  
   - **Claude** : prompt strict JSON (pas de flag équivalent universel dans l’implémentation actuelle).

3. **Transport / erreurs réseau** — inchangé ; chaque provider renvoie `emptyErrorResult` en cas d’exception.

4. **Extraction & parse** — `MealVisionJsonParser` + **`MealVisionChatCompletionsParser`** (OpenAI / DeepSeek)  
   - Gestion **`refusal`** (modèle qui refuse) et contenu vide → `emptyErrorResult` sécurisé.  
   - `FoodAnalysisPrompt.cleanJsonResponse` : retrait fences ```, normalisation des sauts de ligne, extraction du premier `{` … dernier `}`.  
   - `JSONObject` + `parseJsonToResult` : champs optionnels, macros clampées 0…500 g.

5. **Durcissement métier / UI** — `MealAdvisorResponseSanitizer`  
   - Cohérence min / estimate / max, plafonds (glucides recommandés, FPU), textes tronqués, flag manuel si incohérence évidente.

6. **Écran** — `MealAdvisorActivity`  
   - Bandeau d’avertissement si risque glucides cachés **ou** confirmation manuelle recommandée (message explicite).

## Tests unitaires

- `MealAdvisorResponseSanitizerTest` — bornes macros, texte, glucides recommandés.  
- `MealVisionJsonParserTest` — JSON derrière markdown, entrée invalide, échappement du prompt utilisateur.

## Fichiers Kotlin principaux

| Fichier | Rôle |
|---------|------|
| `MealVisionUserPrompt.kt` | Contexte utilisateur |
| `MealVisionJsonParser.kt` | Parse unique post-modèle |
| `MealVisionChatCompletionsParser.kt` | Refusal / vide — OpenAI & DeepSeek |
| `MealAdvisorResponseSanitizer.kt` | Post-traitement sûr |
| `AIVisionProvider.kt` (`FoodAnalysisPrompt`) | Prompt système + `cleanJsonResponse` |
| `*VisionProvider.kt` | Appels HTTP + parse |
