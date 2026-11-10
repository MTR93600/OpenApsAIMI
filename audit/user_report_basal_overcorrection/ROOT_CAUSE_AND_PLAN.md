# Cause racine confirmée + plan de correction produit

Source de preuve : `AIMI_Support_Package_1785672196581` — **appareil du mainteneur**, build
`4.0.0.0-dev.AIMI.310726` (identique au build du rapport), 2026-08-01 14:07 → 2026-08-02 14:00,
404 ticks exportés. Profil : basal 0,5 → 0,6 U/h · ISF 70 → 30 · cible 115 → 100.

**Aucune modification de code n'a été faite.** Ce document est une proposition à valider.

---

## 1. Verdict sur le diagnostic

| Volet du diagnostic utilisateur | Statut sur TES données |
|---|---|
| Le canal basal produit des TBR de 4–8× le profil à glycémie modérée | **CONFIRMÉ** |
| Les protections SMB ne s'appliquent pas à ce canal | **CONFIRMÉ, et pire que décrit** |
| Cause = `eventualBG` gonflé, dosage proportionnel | **INFIRMÉ** — voir §2.3 |
| Cycle basal → hypo → suspension → rebond → re-correction | **NON REPRODUIT ici** — voir §4 |

**Le mécanisme est confirmé et sa cause racine est désormais identifiée avec une preuve
arithmétique.** La chaîne causale rapportée par l'utilisateur ne l'est pas — et l'explication réelle
est structurellement plus grave que celle qu'il propose.

---

## 2. Cause racine — vérifiée numériquement

### 2.1 Le contrôleur PI a un déséquilibre de gain de 36:1

[`DynamicBasalController.kt:178`](../../plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/basal/DynamicBasalController.kt#L178) :

```kotlin
var multiplier = 1.0 + (proportionalError * 0.05) + (velocity * 12.0 * 0.15)
multiplier = multiplier.coerceIn(0.0, 10.0)
```

- gain P = **0,05** par mg/dL d'écart à la cible
- gain D = 12 × 0,15 = **1,8** par mg/dL/5 min de vitesse
- **rapport D/P = 36:1**

Conséquence directe : une vitesse de **+2,5 mg/dL/5 min** (une montée banale, 30 mg/dL/h) ajoute
**+4,5** au multiplicateur. Pour annuler cet apport, il faudrait que la glycémie soit
**90 mg/dL sous la cible**. Le terme proportionnel ne peut donc, en pratique, **jamais** freiner le
terme dérivé. Le contrôleur ne sait pas où est la glycémie ; il ne regarde que sa pente.

Le même déséquilibre existe dans la variante en-tick
[`calculateDynamicRate`](../../plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/basal/DynamicBasalController.kt#L78)
(`P_WEIGHT = 0.05`, `D_WEIGHT = 0.15`, `MAX_TBR_MULTIPLIER = 10.0`), appliquée à `:11486`.

### 2.2 Vérification sur tes logs de production — la formule reproduit exactement

| Tick | BG | cible | P | D | mult. recalculé | mult. **loggé** | TBR |
|---|---|---|---|---|---|---|---|
| 02/08 08:36 | 104,0 | 115 | **−11,0** | 2,54 | `1 + 0,05·(−11) + 1,8·2,54` = **5,02** | **5,03** | 5,00 U/h |
| 02/08 08:41 | 109,2 | 115 | **−5,8** | 4,00 | `1 + 0,05·(−5,8) + 1,8·4,0` = **7,91** | **7,90** | 5,00 U/h |

**3 des 7 ticks PI à TBR > 4 U/h ont un terme proportionnel négatif**, c'est-à-dire que le moteur
demande 5 à 8 fois le basal profil **alors que la glycémie est sous la cible**.

Sur un profil de 0,5–0,6 U/h, 5,00 U/h ≈ **9× le profil**.

### 2.3 Ce que le rapport a manqué : l'eventual était SOUS la cible

Le rapport attribue la surcorrection à un `eventualBG` gonflé. Tes données montrent le contraire, et
c'est plus préoccupant :

```
=== 2026-08-02 06:06 → 06:21 ===              cible 115
  heure    BG     IOB    TBR     eventual (dose-facing)
  06:06   94.2   -0.09   4.05        90      ← eventual 25 SOUS la cible
  06:11   94.2    0.06   4.05        86
  06:16   98.2    0.32   4.05        88
  06:21  102.8    0.53   4.05        89
=== 2026-08-02 08:31 → 08:45 ===
  08:31  100.5   -0.14   3.64        87
  08:36  104.0    0.12   5.00        91      ← eventual 24 SOUS la cible
  08:41  109.2    0.46   5.00        94
```

**La prédiction dit « le patient va atterrir à 86–94 mg/dL », soit sous la cible, et le moteur
demande quand même 4 à 5 U/h.** La règle oref classique « `eventualBG < target` ⇒ pas d'insuline »
**n'existe pas sur le canal basal** (elle n'existe que côté SMB). C'est confirmé par le code
(matrice §2.2 du VERDICT) et désormais observé en production.

### 2.4 Le mutex SMB → Harmonia désactive le seul amortisseur qui fonctionnait

Narration du tick 08:36 :

```
Adjustments : MaxIob 20,00 U [Basal-First: SMB OFF] | MaxSMB 0,0 U
PI-Fallback: P=-11,0 D=2,5 Mult=5,03x; 🌿HARMONIA_PRODUCTION_BASAL_FIRST
Temp basal 5,00 U/h for 30 minutes
```

Séquence complète, observée :

1. `basalFirstActive` s'active (condition `bg < 110.0`) → `maxSMB = 0.0` → **SMB = 0**
   ([`:14718-14726`](../../plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt#L14718))
2. Parce que le SMB est à zéro, le mutex `smb_already_requested` **libère** le canal Harmonia
   basal-first ([`:7500`](../../plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt#L7500))
3. Le PI-Fallback produit ×5,03
4. Harmonia production **remplace** le taux **et force `adaptiveMult = 1.0`**
   ([`:7872`](../../plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt#L7872))
   → **`AdaptiveBasal ×0,70`, le seul réducteur qui liait dans le rapport, est désactivé**
5. Résultat : 5,00 U/h à BG 104 avec IOB 0,12

**11 des 44 ticks à TBR > 4 U/h portent cette signature `HARMONIA_PRODUCTION_BASAL_FIRST`.**

### 2.5 Le cas le plus extrême de ton jeu de données

```
01/08 17:32   BG 73.9   IOB +2.54   TBR 4.18 U/h  (≈8× profil)   eventual 389
01/08 17:37   BG 83.7   IOB +2.63   TBR 4.15 U/h                 eventual 335
01/08 17:42   BG 93.7   IOB +2.73   TBR 4.12 U/h                 eventual 325
01/08 17:52   BG 84.9   IOB +2.55   TBR 0.00 U/h                 eventual  39   ← bascule
```

**BG 73,9 mg/dL avec 2,54 U d'IOB positif → 4,18 U/h demandés.** `hypoGuard` vaut 70 pour une cible
100 : à 73,9 on passe à 3,9 mg/dL du seul arrêt dur du canal. Puis en 10 minutes, l'eventual passe de
325 à 39 et le TBR de 4,12 à 0.

### 2.6 Autres constats sur tes 24 h

| Mesure | Valeur |
|---|---|
| TBR max demandée | 6,30 U/h (≈11× profil) |
| Ticks > 4 U/h | 44 / 404 (10,9 %) ; **13 à BG < 110** |
| Temps à 0 U/h | 31,4 % ; bascules ≥ 2 U/h entre ticks consécutifs : 11,1 % |
| Bilan de masse | excès basal **17,4 U (41 %)** vs SMB/bolus **24,8 U (59 %)** |
| BG min exporté | 71,8 — **0 tick sous 70** (censure sélective des descentes, cf. R3) |
| `meal_modes_max_basal` configuré | **10,0 U/h** = **16–20× ton profil**, utilisé comme consigne exacte |
| Ticks à ≥ 9 U/h dans le JSONL | **0** → le chemin mode repas est invisible dans la télémétrie |

Le bilan de masse **41 % / 59 %** est le troisième jeu de données consécutif où le canal SMB délivre
la majorité de l'insuline au-dessus du profil — la thèse « le basal délivre les surdoses » reste
inversée.

---

## 3. Ce qui est confirmé vs ce qui ne l'est pas

**Confirmé (preuve arithmétique + production) :**
- déséquilibre de gain 36:1 dans le contrôleur qui produit le taux final ;
- demande de 5–9× le profil avec BG **sous** la cible et prédiction **sous** la cible ;
- absence de la règle « eventual < cible ⇒ suppression » sur le canal basal ;
- le mutex Basal-First → Harmonia désactive `AdaptiveBasal` ;
- `meal_modes_MaxBasal` = consigne exacte à 10 U/h, non exportée.

**Non confirmé sur tes données :** la cascade clinique. Sur 100 min après chacune des trois
séquences : nadirs 72, 98, 104 — **aucune hypoglycémie sous 70**. Le mécanisme s'arme, l'issue est
restée contenue (IOB faible, repas ensuite). **La justification du chantier est donc la marge de
sécurité, pas un préjudice démontré ici** — mais la séquence à BG 73,9 avec 4,18 U/h montre que la
marge est mince.

---

## 4. Plan de correction

Le « minimum » (baisser `D_WEIGHT`) serait un pansement : il déplacerait le seuil sans corriger la
raison pour laquelle un contrôleur peut ignorer la position de la glycémie, ni les trois autres
défauts structurels. Le plan ci-dessous traite la classe de défaut.

### Lot 0 — Rendre le système observable (préalable, non négociable)

Sans cela, aucun des lots suivants ne peut être validé : les décisions au basal le plus élevé
(mode repas) et les descentes basses sont absentes de la télémétrie.

- **0.1** Hisser l'export JSONL en **un point unique et inconditionnel**, en sortie de tick, atteint
  par les 14 chemins de retour (aujourd'hui : 2 sites d'export pour 13 retours anticipés).
- **0.2** Journaliser systématiquement, dans le tick : branche productrice du taux, valeur avant/après
  chaque étage (PI → fusion → cap → Dynamic → Adaptive → endocrine → final), et l'autorité active.
  Aujourd'hui il faut corréler une narration en texte libre pour reconstituer la chaîne.
- **0.3** Compteur d'invariants violés (cf. lot 2), exporté.

*Risque : nul (télémétrie seule). Bénéfice : rend les lots 1–4 mesurables.*

### Lot 1 — Refonte du contrôleur : erreur **projetée** au lieu de P + D déséquilibrés

**Ne pas simplement retuner les gains.** Le vrai défaut est que P et D sont dans des unités
différentes combinées par des poids arbitraires. La formulation correcte, et physiologiquement
motivée, est de projeter la glycémie sur l'horizon d'action de l'insuline et de mesurer **un seul**
écart :

```
BG_projetée = BG + vitesse × horizon        (horizon ≈ délai d'action, ~45–60 min)
erreur       = BG_projetée − cible
multiplicateur = f(erreur)                   avec f(0) = 1, f monotone, bornée
```

Sur le tick 08:36 : `104 + 30 × 1 h = 134` vs cible 115 → erreur **+19** → multiplicateur ≈ **1,95×**
au lieu de **5,03×**. Le moteur corrige toujours la montée, mais proportionnellement à *où la
glycémie va*, pas à *sa pente instantanée*.

Bénéfices : une seule grandeur à régler (l'horizon, qui a un sens physiologique) ; le terme dérivé ne
peut plus dominer ; `projectBg` existe déjà dans ce fichier (`:202`) et est utilisé par T3C — on
unifie sur la même projection.

- **1.1** Introduire la formulation projetée dans `DynamicBasalController`, derrière préférence,
  défaut OFF au départ.
- **1.2** Mode **shadow** : calculer les deux, n'appliquer que l'ancien, exporter les deux. Mesurer
  l'écart sur plusieurs jours réels avant bascule.
- **1.3** Unifier `compute()` (fallback) et `calculateDynamicRate()` — aujourd'hui deux formules
  différentes pour la même fonction, ce qui est un défaut en soi.

### Lot 2 — Invariants de sécurité terminaux (traite R1, la classe de défaut)

Le problème structurel n'est pas qu'une protection manque, c'est qu'elles sont posées **en amont**
d'un multiplicateur ×0–10. Toute règle ajoutée en amont de `:11486` sera à nouveau écrasée.

- **2.1** Créer **un point de plafonnement terminal unique**, après le dernier multiplicateur et
  immédiatement avant `rT.rate = rate` ([`:11616`](../../plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt#L11616)),
  qui reçoit l'autorité du tier (REBOUND_GUARD, post-hypo, activité) **propagée jusque-là** au lieu
  d'être consommée à `:7829`.
- **2.2** Y exprimer les invariants comme des assertions vérifiables, pas comme des branches :
  - si `BG < cible` **et** `eventual < cible` → multiplicateur ≤ 1,0 (la règle oref manquante) ;
  - si post-hypo actif → plafond absolu en U/h, contraignant, quelle que soit la branche productrice ;
  - si `IOB < 0` → aucune escalade au-dessus du profil sans confirmation de montée soutenue ;
  - plafond global exprimé en **× profil**, pas seulement en `max_basal` absolu.
- **2.3** Chaque violation d'invariant = compteur + ligne de log (lot 0.3), pour mesurer avant de
  durcir.

### Lot 3 — Fermer les échappatoires d'autorité

- **3.1** **Ne plus forcer `adaptiveMult = 1.0`** quand Harmonia/T3C prend la main (`:7865`, `:7872`).
  Ces canaux doivent hériter de la gouvernance, pas s'en exempter. C'est ce qui a neutralisé le seul
  réducteur actif sur tes ticks à 5 U/h.
- **3.2** Requalifier `meal_modes_MaxBasal` : soit un **plafond** (`coerceAtMost`), soit une consigne
  explicitement nommée `meal_modes_setpoint` et bornée par `maxSafe`. Aujourd'hui `forceExact = true`
  court-circuite cinq étages de sécurité pour poser 10,0 U/h sur un profil 0,5.
- **3.3** Revoir le mutex `smb_already_requested` : « le SMB a été refusé pour raison de sécurité »
  et « le SMB n'a pas été demandé » doivent être deux états distincts. Aujourd'hui un blocage de
  sécurité SMB **ouvre** le canal basal-first, ce qui inverse l'intention.

### Lot 4 — Learners

- **4.1** Fenêtre d'exclusion post-hypo pour l'entraînement moyen/long terme de `BasalLearner`
  (aujourd'hui : aucune, et le rebond prend la branche ×1,12).
- **4.2** Terme de convergence vers le neutre sur les trois termes — le motif existe déjà dans
  `UnifiedReactivityLearner` (`:695-706`) ; `onHypoDetected` est aujourd'hui irréversible sur BG calme.
- **4.3** Trancher `onPersistentHyper()` : code mort → soit le brancher, soit le supprimer.

### Lot 5 — Validation

- **5.1** Tests de caractérisation figeant le comportement **actuel** (cas A–E de la mission), avant
  toute modification.
- **5.2** Harnais de rejeu sur ces paquets JSONL : rejouer 24 h et comparer taux ancien/nouveau.
- **5.3** Critères d'acceptation mesurables : suppression des ticks « TBR > 3× profil avec BG < cible
  **et** eventual < cible » ; conservation de la réactivité aux vraies montées (comparer l'aire sous
  la courbe > 180 avant/après en rejeu).

### Ordonnancement proposé

```
Lot 0 (observabilité)  ──►  Lot 5.1/5.2 (filet de tests + rejeu)
                              │
                              ├──►  Lot 2 (invariants terminaux)   ◄── traite la classe de défaut
                              ├──►  Lot 3 (échappatoires)          ◄── gain immédiat, risque faible
                              ├──►  Lot 1 (contrôleur, shadow)     ◄── cœur, à valider longuement
                              └──►  Lot 4 (learners)
```

Lots 2 et 3.1/3.3 donnent le meilleur rapport sécurité/risque immédiat. Le lot 1 est le vrai
correctif de fond mais doit passer par une phase shadow mesurée.

---

## 5. Décision demandée

Conformément aux règles du dépôt, **je n'ai modifié aucun code**. Avant d'écrire quoi que ce soit,
il faut arbitrer :

1. Commence-t-on par le **lot 0** (observabilité) ou par le **lot 3** (échappatoires, gain immédiat) ?
2. Le **lot 1** part-il en shadow derrière préférence, ou préfères-tu figer d'abord les tests de
   caractérisation (5.1) ?
3. Périmètre : ces défauts touchent le chemin de dosage. Les lots 1 à 3 doivent-ils être découpés en
   PR séparées avec préférence par lot, ou traités comme un seul chantier ?

> Ces changements modifient la logique de dosage d'un système qui pilote une pompe à insuline.
> Rien ici n'est un conseil thérapeutique ; la validation clinique et le réglage de tes propres
> préférences (`meal_modes_max_basal = 10,0`, `openapsma_max_basal = 9,0`) relèvent de ton suivi
> médical.
