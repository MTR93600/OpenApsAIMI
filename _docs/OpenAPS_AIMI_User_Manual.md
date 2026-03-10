# Manuel utilisateur – OpenAPS AIMI
**Version : 2.x (Expert Activity & Cruise Mode Update)**
**Date : Décembre 2025**

Ce manuel couvre l'ensemble des fonctionnalités du plugin AIMI, y compris les modules récents : **Activity Manager Expert**, **Comparateur AIMI vs SMB**, **Optimisation Basale Croisière**, ainsi que les moteurs **AutoDrive** et **Machine Learning**.

---

## 1. Vue d’ensemble : "Comment pense AIMI ?"

Contrairement à oref0/oref1 qui applique des règles statiques (si BG < cible → basale 0), AIMI est un moteur **décisionnel multicritère**. Il ne se contente pas de réagir à la glycémie instantanée, mais fusionne de multiples signaux pour "comprendre" la situation métabolique.

### Flux de décision simplifié
```mermaid
graph TD
    A[Capteurs: CGM, Pompe (IOB), Activité (Pas/FC), Modes] --> B(Learners & PK/PD)
    B --> C{Contexte ?}
    C -->|Sport| D[Activity Manager]
    C -->|Repas| E[Meal Detection & DynISF]
    C -->|Repos| F[AutoDrive & Basal Optimization]
    D & E & F --> G[Calcul Cible (Basal + SMB)]
    G --> H[Safety Layer (Hypo, IOB, Delta)]
    H --> I[Sortie Pompe (TBR / SMB)]
```
1. **Perception** : AIMI lit la glycémie, l'insuline à bord (IOB), et l'activité physique (Pas/Cœur).
2. **Apprentissage (ML)** : Les modules *Learners* et *PK/PD* estiment votre sensibilité réelle et la durée d'action de l'insuline en temps réel.
3. **Modulation** : Selon le contexte (Sport Intense, Repas, Nuit), l'agressivité est ajustée (ISF dynamique, cible).
4. **Sécurité** : Avant toute action, la couche *Safety* vérifie les risques d'hypoglycémie. Si un risque existe, elle bloque ou réduit l'insuline, peu importe ce que l'IA suggère.

---

## 2. Installation et Mise à jour

### Première Installation
1.  **APK** : Installez l'APK AIMI (généré depuis la branche `aimi-dev`).
2.  **Activation** : Allez dans *ConfigBuilder ▶️ Plugins*, cochez **OpenAPS AIMI**.
3.  **Vérification** : Dans l'onglet *OpenAPS AIMI*, vérifiez que le statut affiche "Running".

### Mise à jour
*   **Sauvegarde** : Exportez toujours vos préférences avant une mise à jour majeure.
*   **Fichiers ML** : En cas de changement majeur de logique (ex: v1 → v2), il est conseillé de supprimer les fichiers `.csv` d'apprentissage dans `/AAPS/logs/` pour repartir sur une base saine, bien que AIMI sache généralement s'adapter.

---

## 3. Réglages de base recommandés

Pour démarrer, configurez ces valeurs dans *Préférences AIMI*. Ne copiez pas aveuglément, adaptez à votre profil.

| Paramètre | Valeur Recommandée (Adulte) | Valeur Recommandée (Enfant/Sensible) | Description |
| :--- | :--- | :--- | :--- |
| **Max Daily Safety Multiplier** | 3.0 - 4.0 | 2.5 - 3.0 | Plafond de base sécurité |
| **Current Basal Safety Multiplier** | 4.0 | 3.0 | Plafond instantané |
| **Max SMB** | 2.0 U | 0.5 - 1.0 U | Bolus max par 5 min |
| **ISF AIMI Adjustment** | 120-130% | 100-110% | Agressivité de l'ISF vs Profil |
| **Enable PK/PD** | ON | ON | Active l'apprentissage dynamique |

---

## 4. AutoDrive & Reactivity

### AutoDrive
C'est le "pilote automatique" avancé pour les repas. Lorsqu'il est actif :
*   Il détecte les variations de glycémie (Delta) et l'accélération.
*   Il applique automatiquement des micro-prébolus si la glycémie monte vite, sans attendre que vous déclariez un repas (utile pour les oublis).
*   **Réglage clé** : `OApsAIMIautoDrive`. Activez-le une fois que votre basal de base est bien réglé.

### Unified Reactivity
Ce module observe votre résistance à l'insuline sur les dernières heures.
*   Si vous faites des hypers rebelles, il augmente le **Global Factor** (> 1.0).
*   Si vous enchaînez les hypos, il le baisse (< 1.0).
*   Affiche un statut type `Reactivity 1.15 ↑` dans les logs, signifiant qu'il applique 15% d'insuline en plus.

---

## 5. Module Activité Expert (NOUVEAU) 🏃

AIMI intègre désormais un gestionnaire d'activité complet qui fusionne les pas (téléphone/montre) et la fréquence cardiaque (FC).

### Le Score d'Intensité (0 - 10)
AIMI calcule un score composite toutes les 5 minutes.

| État | Score | Critères Types | Action AIMI |
| :--- | :--- | :--- | :--- |
| **REST** | 0 - 2 | Assis, Couché | Mode normal. |
| **LIGHT** | 2 - 4 | Marche lente, Ménage | Surveillance. Pas d'action majeure. |
| **MODERATE** | 4 - 7 | Marche rapide, Vélo cool | **ISF x 1.3** (plus sensible), **Basal réduite** (80%), SMB bridés. |
| **INTENSE** | 7 - 10 | Running, Cardio | **ISF x 1.6**, **Basale 60%**, SMB bloqués ou très limités. |

### Mode "Recovery" (Récupération)
Après une activité intense, AIMI passe en mode *Recovery* pendant 30 à 60 minutes.
*   **But** : Éviter l'hypo tardive ("effet fenêtre métabolique").
*   **Effet** : Maintient une sensibilité accrue et limite les gros bolus même si la FC est redescendue.

---

## 6. Modes Repas & Courbes

Les modes (Meal, Dinner, Breakfast) ne sont pas juste des "étiquettes", ils changent la stratégie de la boucle.

### Dinner Mode (Dîner)
*   **Spécificité** : Souvent le repas le plus complexe (gras, soir).
*   **Comportement** :
    *   **0-30 min** : Force une basale minimale pour amorcer l'action.
    *   **30-90 min** : Maintient un "plancher" de basale (voir section 8) pour éviter les trous d'insuline.
    *   **Fin** : S'arrête automatiquement quand la glycémie est revenue proche de la cible ou après le délai max.

### High Carb / Snack
*   **High Carb** : Pour les repas riches en glucides rapides. Autorise des SMB plus agressifs et plus fréquents (intervalle 10 min).
*   **Snack** : Pour les collations. Moins agressif, vise juste à couvrir sans provoquer d'hypo pré-repas suivant.

---

## 7. Optimisation Basale "Croisière" (NOUVEAU) 🚢

Une critique fréquente des boucles fermées est la coupure brutale de la basale (0.00 U/h) dès que la glycémie baisse un peu, créant un manque d'insuline 2h plus tard. AIMI introduit une logique de **Basal Floor**.

### Le principe
En régime de croisière (hors repas majeur, hors sport intense) :
*   Si la glycémie est stable ou baisse doucement (Delta > -2) et reste au-dessus de la cible...
*   **AIMI refuse de couper à 0.**
*   Il maintient un **plancher de sécurité** (environ 45-50% du profil).

### Reprise Intelligente
Après une activité ou une coupure forcée :
*   Dès que la glycémie remonte (Delta positif), AIMI **lève immédiatement le frein**.
*   La basale remonte rapidement à 100% (voire plus) sans attendre une hyper.

> **Note** : La sécurité prime. Si `BG < 70` ou `PredBG < 65`, la basale est TOUJOURS coupée à 0.

---

## 8. Comparateur AIMI vs OpenAPS SMB (NOUVEAU) 🔬

Pour les utilisateurs avancés qui veulent comprendre les différences.
*   **Activation** : *Préférences ▶️ Comparateur*.
*   **Fonctionnement** : AIMI exécute silencieusement l'algo "OpenAPS original" en parallèle de sa propre logique.
*   **Logs** : Dans l'onglet *Comparateur* ou les fichiers CSV, vous verrez :
    *   *AIMI Decision*: 1.5U (SMB)
    *   *System Decision*: 0.0U (Original)
    *   *Diff*: +1.5U
*   **Usage** : Permet de valider que AIMI apporte une valeur ajoutée (plus de réactivité, moins d'hypos) sans risquer sa sécurité (puisque c'est AIMI qui pilote réellement la pompe).

---

## 9. Machine Learning & Fichiers CSV

AIMI apprend de vous. Il stocke ses données dans `/AAPS/logs/`.

*   **`oapsaimi_learning_records.csv`** : Contient l'historique utilisé pour entraîner le réseau de neurones (BG, IOB, COB, TDD).
*   **`oapsaimi_analysis.csv`** : Analyse des performances (TIR, Variabilité).
*   **Modèle ML** : Il faut environ **3 à 7 jours** de données continues pour que le modèle commence à faire des prédictions fiables.
    *   *Phase 1 (Jours 1-3)* : AIMI utilise principalement les règles statiques et PK/PD de base.
    *   *Phase 2 (Jours 3+)* : Les facteurs de sensibilité s'affinent.

---

## 10. Sécurité, Bonnes Pratiques & Dépannage

### check-list Sécurité
1.  **Ne surchargez pas** : Ne mettez pas `MaxSMB` à 5U si votre TDD est de 30U. Restez cohérent.
2.  **Activité** : Si vous faites du sport, **déclarez-le** ou activez les capteurs. AIMI ne peut pas deviner que vous courez sans données.
3.  **Hypo non expliquée ?** : Regardez les logs `Safety`. Si AIMI n'a pas coupé assez tôt, baissez le `Max Basal` ou augmentez la sensibilité (`Profile Sens`).

### Dépannage Rapide

| Symptôme | Cause Possible | Action |
| :--- | :--- | :--- |
| **Basale toujours à 0** | Safety trop stricte ou IOB > Max | Vérifiez `Max IOB`. Vérifiez si cible trop haute. |
| **Hyper après repas** | AutoDrive trop timide | Activez `High Carb` plus tôt. Augmentez `Meal Factor`. |
| **Pas de SMB** | Pas de données BG ou Mode 'Block' | Vérifiez CGM. Vérifiez si Mode "Recovery" actif. |
| **Batterie draine vite** | Calculs ML trop fréquents | Désactivez `ML Training` si le modèle est stable. |

---

*AIMI est un outil puissant. Prenez le temps d'observer ses réactions en mode "Comparateur" ou avec des limites conservatrices avant de lui donner les pleins pouvoirs.*
