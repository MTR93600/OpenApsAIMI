# 🌀 Analyse des Trajectoires (Phase-Space)

Ce document décrit les formes géométriques utilisées par le contrôleur de trajectoire d'AIMI pour classifier l'état métabolique et adapter la stratégie de délivrance d'insuline.

## 📐 Concepts de base

Le système ne regarde pas seulement la glycémie (1D) ou sa tendance (2D), mais analyse la dynamique dans un "Espace de Phase" multidimensionnel incluant :
*   Glycémie ($G$)
*   Vitesse ($dG/dt$)
*   Accélération ($d^2G/dt^2$)
*   Activité Insuline ($I_{act}$)

L'objectif n'est pas de viser un point fixe, mais de guider le système vers une **orbite stable** (attracteur).

---

## 🔍 Les 5 Formes de Trajectoire

### 1. ⭕ Orbite Stable (Stable Orbit)
* **Symbole :** ⭕
* **Description :** Le système est en équilibre. La glycémie fluctue légèrement autour de la cible (±20 mg/dL) avec des variations lentes.
* **Comportement Algo :** Mode croisière. Micro-ajustements uniquement. Pas de SMB agressifs.
* **Santé :** 90-100%

### 2. 🔄 Convergente (Closing Converging)
* **Symbole :** 🔄
* **Description :** Une perturbation a eu lieu (repas), mais la trajectoire se referme naturellement vers la cible. La dérivée seconde (accélération) pointe vers l'équilibre.
* **Comportement Algo :** Accompagnement. L'insuline active est suffisante. On évite de rajouter du "bruit" (sur-correction).
* **Santé :** 70-90%

### 3. ↗️ Divergente (Open Diverging)
* **Symbole :** ↗️
* **Description :** Le système s'échappe. L'insuline active est insuffisante pour contrer le flux de glucose entrant. La "boucle" s'ouvre.
* **Comportement Algo :** **Alerte.** Nécessite une intervention énergétique forte (SMB, augmentation Basal).
* **Santé :** < 50%

### 4. 🌀 Spirale Serrée (Tight Spiral)
* **Symbole :** 🌀
* **Description :** Le système oscille rapidement. Forte correction suivie d'une forte contre-réaction. Risque de "pompage" (oscillations induites par le contrôleur).
* **Comportement Algo :** **Freinage.** Il faut amortir le système. Augmentation de l'intervalle de décision, réduction des gains SMB pour calmer le jeu.
* **Santé :** Variable (indique une instabilité dynamique).

### 5. ❓ Incertaine (Uncertain)
* **Symbole :** ❓
* **Description :** Les données sont bruitées, incohérentes (ex: CGM saute, ou délai insuline anormal). Pas de forme géométrique claire.
* **Comportement Algo :** Prudence maximale (Fallback).

---

## 🧠 Intégration avec l'AI Auditor

**Question :** Y a-t-il une plus-value à inclure l'Auditor si un doute persiste sur la forme ?

**Réponse : OUI ABSOLUMENT.**

L'algorithme géométrique est très précis mais "aveugle" au contexte externe (Stress, Sport annoncé, Repas complexe). L'Auditor agit comme un **arbitre sémantique**.

### Quand l'activer ?
L'Auditor doit être sollicité lorsque la géométrie échoue à donner une direction claire :
1.  **Faible Cohérence (`Metrics.coherence < 0.4`)** : Le glucose ne réagit pas à l'insuline comme prévu (Résistance soudaine ? Site bouché ?).
2.  **Spirale Serrée (`TIGHT_SPIRAL`)** : Le système oscille. Est-ce le contrôleur qui est trop agressif ou une perturbation externe cyclique ?
3.  **Divergence Inexpliquée :** Le glucose monte alors que l'IOB est élevé.

### Données Complémentaires Requises (Context Injection)

Pour trancher, l'Auditor a besoin de ces 3 axes de données que le module Trajectory ignore :

| Axe | Donnée | Utilité pour la Décision |
| :--- | :--- | :--- |
| **🍔 Nutrition** | `COB`, `TimeSinceLastCarb`, `MealComposition` (si dispo) | Distinguer une divergence due à une **vidange gastrique tardive** (Pizza) d'une divergence "médicale" (Maladie/Site). <br>👉 *Si Repas complexe récent : Autoriser SMB.* |
| **🏃 Activité** | `Steps` (15/60min), `HeartRate`, `SleepState` | Expliquer des oscillations (Spirales). Le sport crée souvent des faux positifs de "divergence" (adrénaline) suivis de "convergence" brutale. <br>👉 *Si Sport intense : Interdire sur-correction.* |
| **💉 Matériel** | `CannulaAge`, `ReservoirLevel`, `PumpSuspendHistory` | Diagnostiquer une **incohérence grave**. Si ça diverge ET que le site a 3 jours ET que la cohérence est nulle... <br>👉 *Alerte : Changer cathéter (ne pas bombarder d'insuline).* |

### Scénarios d'Arbitrage

1.  **Le cas "Pizza Effect" :**
    *   *Géométrie :* Vitesse augmente, ça diverge ↗️ (Alerte rouge).
    *   *Auditor :* "Je vois des glucides entrés il y a 4h avec note 'Pizza'. C'est attendu."
    *   *Verdict :* **VALIDER** l'agressivité.

2.  **Le cas "Site Bouché" :**
    *   *Géométrie :* Divergence ↗️ malgré IOB élevé. Cohérence très basse (-0.8).
    *   *Auditor :* "Aucun repas récent. Site âgé de 70 heures."
    *   *Verdict :* **BLOQUER** l'augmentation basale (ça ne sert à rien) et émettre une notification "Vérifier Cathéter".

**Recommandation d'implémentation :**
Créer une classe `TrajectoryAuditorBridge` qui prend en entrée `TrajectoryMetrics` + `PhysioContext` pour sortir un `ModulationFactor`.

---

## 📊 Visualisation (ASCII)

Le bloc graphique demandé sera intégré dans la section `Reasoning (rT)` pour offrir un diagnostic immédiat.

```text
─────────────────────────────────┐
│ 🌀 TRAJECTORY STATUS            │
├─────────────────────────────────┤
│ Type: 🔄 Converging             │
│ Health: ████████░░ 74%          │
│ ETA: 35 min to stable orbit     │
│                                 │
│ Metrics:                        │
│ ├─ Curvature:    ████░░░ 0.18   │
│ ├─ Convergence: +0.45 mg/dL/min │
│ ├─ Coherence:    ███████ 0.78   │
│ └─ Energy:       █░░░░░░ 1.2U   │
└─────────────────────────────────┘
```
