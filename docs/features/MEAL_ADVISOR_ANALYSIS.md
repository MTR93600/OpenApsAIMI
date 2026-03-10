# Analyse Approfondie : Flux de Décision & Performance d'AIMI Meal Advisor

## 1. Contexte & Problématique
Les observations indiquent une **lenteur exceptionnelle** et une **inefficacité** du Meal Advisor depuis l'introduction de nouvelles sécurités, entraînant des hyperglycémies post-prandiales. L'ajout de contextes manuels ne résout pas le problème.

L'objectif de cette analyse est de **cartographier** le chemin décisionnel ("Decision Pipeline") pour identifier les goulots d'étranglement (Safety Bottlenecks) et proposer des correctifs ciblés.

---

## 2. Cartographie du Flux Décisionnel (Current State)

Le schéma ci-dessous illustre le parcours d'une recommandation du Meal Advisor à travers le moteur `DetermineBasalAIMI2.kt`.

### Phase 1 : Entrée & Calcul Initial
1.  **Advisor Request** : L'utilisateur valide un repas.
2.  **OpenAPS Core** : Calcule un SMB théorique (`proposedUnits`) basé sur les GL, COB et IOB.
3.  **Appel `finalizeAndCapSMB`** : C'est ici que tout se joue.

### Phase 2 : Le "Gauntlet" de Sécurité (Le Goulot d'Étranglement)
C'est dans cette phase, située dans `finalizeAndCapSMB` et `applySafetyPrecautions`, que les ralentissements et blocages se produisent.

| Étape | Action Code | Analyse du Blocage |
| :--- | :--- | :--- |
| **Safety Net** | `SafetyNet.calculateSafeSmbLimit` | Calcule une limite "sûre". Si l'Auditor (IA) est lent ou timeout, `auditorConfidence` est null. Le système retombe sur des limites très conservatrices par défaut. |
| **Safety Precautions** | `applySafetyPrecautions` | **POINT CRITIQUE**. Cette fonction applique des réductions drastiques (x0.6, x0.5, voire x0) basées sur des heuristiques (chute légère, sport, etc.). |
| **Refractory Period** | `recentBolus check` | Si l'IA met du temps à calculer, le "temps depuis dernier bolus" peut s'être écoulé, mais le code peut penser qu'une autre micro-action bloque l'envoi. |
| **Throttle PKPD** | `SmbTbrThrottleLogic` | **Nouveau Frein**. Une logique "temps réel" a été ajoutée pour freiner les SMB si l'insuline "travaille déjà". Cela peut entrer en conflit avec un gros repas qui nécessite une action immédiate. |

### Phase 3 : Validation Finale
| Étape | Action Code | Analyse du Blocage |
| :--- | :--- | :--- |
| **Meal Force Send** | `isExplicitUserAction` Check | Le code tente de forcer l'envoi pour les repas *SI* `gatedUnits > 0`. **Problème :** Si l'étape 2 (Safety Precautions) a réduit `gatedUnits` à 0, cette étape de forçage est **sautée**. |
| **Ultimate Cap** | `capSmbDose` | Plafonne à MaxIOB. C'est nécessaire, mais si le MaxSMB est mal configuré (trop bas pour un repas), cela bloque l'action. |

---

## 3. Diagnostic des Causes Racines

### A. La Condition "Force Send" est court-circuitée
Dans `DetermineBasalAIMI2.kt` (lignes 1735+), le code dit :
```kotlin
if (isExplicitUserAction && gatedUnits > 0f) { ... }
```
Si les précautions de sécurité (lignes 1620-1629) détectent une "condition critique" (même bénigne comme une légère baisse avant le repas) ou si le `Throttle PKPD` est trop agressif, `gatedUnits` tombe à **0**.
Résultat : Le bloc `if` qui est censé forcer l'envoi pour le repas est purement et simplement ignoré. Le Meal Advisor a "raison", mais le moteur "a peur" et ne fait rien.

### B. Le "Throttle" en Temps Réel est aveugle au contexte
La nouvelle logique `insulinObserver.update` (lignes 1673+) calcule un frein basé sur l'activité de l'insuline. Elle ne semble pas désactivée explicitement quand un "Meal Advisor" vient juste d'être validé, sauf si `isExplicitUserAction` est strictement vrai. Si l'Advisor ne passe pas ce flag correctement, le frein s'applique à plein régime pendant le repas.

### C. Latence de l'Auditor (IA)
Si `AuditorVerdictCache` (ligne 1600) ne répond pas instantanément, `auditorLastConfidence` est null. Le système perd alors son "permis de conduire vite" et revient à une conduite "jeune conducteur" (limites basses), ce qui est fatal pour un gros repas.

---

## 4. Propositions d'Évolution (Solution Plan)

Pour restaurer la rapidité sans sacrifier la sécurité vitale, voici les évolutions proposées :

### Évolution 1 : Priorité Absolue au Repas (The "Red Carpet" Logic)
Modifier `finalizeAndCapSMB` pour que le "Force Send" des repas puisse **récupérer** un SMB annulé par les sécurités mineures.
*   **Action** : Si c'est une action explicite (Repas), on regarde `proposedUnits` (la demande initiale) plutôt que `gatedUnits` (le résultat filtré) pour décider d'entrer dans le mode forcé.
*   **Sécurité** : On garde le `capSmbDose` (MaxIOB) comme garde-fou ultime, mais on ignore les "petits freins" (Throttle, baisse légère).

### Évolution 2 : Asynchronous Trust (Confiance Asynchrone)
Ne pas bloquer le calcul sur la réponse de l'Auditor.
*   **Action** : Si l'Auditor est lent, utiliser une "Confiance par Défaut" optimiste pendant les 15 premières minutes d'un repas déclaré, au lieu de retomber sur le mode par défaut restrictif.

### Évolution 3 : Désactiver le Throttle PKPD en post-prandial immédiat
*   **Action** : Ajouter une condition dans le bloc `insulinObserver` : Si `mealContext` est actif ET que le repas a commencé il y a < 60 minutes, `throttle` = 1.0 (Pas de frein).

### Évolution 4 : Feedback Visuel "Safety Brake"
*   **Action** : Si le Meal Advisor propose un bolus mais qu'il est bloqué par la sécurité, afficher une notification claire "⚠️ Repas détecté mais bloqué par sécurité (Raison : X)" pour que l'utilisateur sache pourquoi c'est lent.

---

## 5. Résumé des Actions Techniques Recommandées

1.  **Modifier `DetermineBasalAIMI2.kt`** :
    *   Dans `finalizeAndCapSMB`, changer la condition `if (isExplicitUserAction && gatedUnits > 0f)` en `if (isExplicitUserAction && proposedUnits > 0f)`.
    *   Recalculer `gatedUnits` à l'intérieur de ce bloc en relaxant les contraintes, plutôt que d'accepter le 0 fatidique.
2.  **Mettre à jour `SafetyModule`** : Exclure le paramètre "SportSafety" et "DropChute" si un contexte "Meal Advisor" de haute confiance est présent.

Cette stratégie devrait éliminer la latence perçue et restaurer l'agressivité nécessaire pour couvrir les repas, tout en gardant le filet de sécurité MaxIOB.
