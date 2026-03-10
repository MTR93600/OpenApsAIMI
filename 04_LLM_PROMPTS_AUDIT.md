# 🤖 Audit Prompts LLM & Strategy

**Fichiers analysés** : `AuditorPromptBuilder.kt`, `AuditorAIService.kt`.

## 📜 1. Prompt "Auditor" (`AuditorPromptBuilder.kt`)

### Rôle
Agir comme "Diaby", un "challenger bienveillant" qui valide ou critique les décisions de la boucle (SMB, TBR).

### Structure Actuelle
*   **Persona**: "Diaby", Pattern Recognition Expert, Endocrinologue Contextuel.
*   **Instruction**: Analyse contextuelle, garde-fous (ne pas changer profil, ne pas commander pompe), détection risques (stacking, compression low).
*   **Output**: JSON strict (`verdict`, `confidence`, `riskFlags`, `evidence`).

### Analyse Critique (V1)
*   🟢 **Points Forts**:
    *   Persona clair et engageant ("Diaby").
    *   Format JSON forcé (évite le parsing d'explications textuelles).
    *   Instructions cliniques explicites (ex: "Phase lutéale", "Compression Low").
    *   Interdictions claires (NE PAS doser).
*   🔴 **Points Faibles**:
    *   **Subjectivité**: "Risk Assessor Prudent mais Pas Paralysé" laisse trop de marge d'interprétation à la "Temperature" du modèle.
    *   **Hallucination de Données**: Le prompt ne force pas explicitement le modèle à dire "INCONNU" si une donnée manque (ex: steps). Il risque d'inventer une justification.
    *   **Manque de Contexte Historique**: Le prompt reçoit l'input instantané. Il manque l'historique des 2-3 dernières décisions pour détecter l'oscillation (Ping-Pong SMB).

### Proposition V2 (Améliorations)

Ajouter cette section **Safety Assertions** dans le prompt système :

```text
## SAFETY ASSERTIONS (REQUIRED)
Before verdict, you MUST validate:
1. DATA_INTEGRITY: If glucose_delta is missing, verdict MUST be "SOFTEN".
2. HYPO_RULE: If bg < 75, verdict MUST be "SOFTEN" or "CONFIRM" (never imply aggressive action).
3. STACKING_RULE: If iob_activity > 80% AND smb_proposed > 0.5, Check carefully for stacking.

## ANTI-HALLUCINATION
- If Input.steps is null/0, do NOT mention "sedentary" or "active". State "Activity Unknown".
- Do NOT recalculate IOB. Use provided Input.iob.
- Do NOT invent future BG values.
```

---

## 📜 2. Prompt "Provider" (`AuditorAIService.kt`)

### Analyse Technique
*   **Température**: 0.3 (Bon choix, favorise le déterminisme).
*   **Modèles**:
    *   OpenAI: `gpt-5.2` (Futuriste, fallback `gpt-4o` recommandé).
    *   Gemini: `gemini-2.0-flash` (Très rapide, context window large).
    *   Claude: `claude-sonnet` (Excellent raisonnement).
*   **JSON Mode**: Activé (`response_format: {type: "json_object"}`).

### Risque Technique
*   **Timeout**: 3 minutes. C'est très long pour une décision "temps réel" (boucle de 5 min). Si l'auditor met 3 min à répondre, la boucle est déjà passée.
*   **Recommandation**: Timeout max 30-45 secondes. Si pas de réponse, fallback "SILENT" (Log only).

---

## 🛡️ Stratégie Validation LLM

Pour valider que "Diaby" ne devient pas fou, implémenter un test unitaire "Golden Dataset" :

1.  **Dataset**: 50 snapshots JSON réels (anonymisés) couvrant :
    *   Cas normal (stable).
    *   Hypo imminente.
    *   Hyper post-prandiale.
    *   Erreur capteur (compression low).
    *   Données manquantes.
2.  **Expected Output**: Pour chaque snapshot, définir le verdict attendu (CONFIRM / SOFTEN).
3.  **CI Pipeline**: Lancer le prompt V2 sur ce dataset avec température 0.0.
4.  **Critère Succès**: 100% de concordance sur les cas "Hypo" et "Compression Low".

---

## 🚨 Conclusion Audit LLM
L'intégration est de haute qualité (JSON strict, Prompt Engineering avancé). Le principal risque est la **latence** (3 min timeout) et l'absence de **validation déterministe** (Golden Tests) avant déploiement. L'IA doit être un "Conseiller", jamais un "Décideur" en boucle fermée sans validation humaine ou algorithmique stricte.
