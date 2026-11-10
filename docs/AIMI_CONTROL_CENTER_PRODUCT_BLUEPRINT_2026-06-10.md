# AIMI Control Center - Blueprint Produit 2026-06-10

## 1. Objectif

Le problème n'est plus seulement le nombre de préférences AIMI. Le vrai problème produit est que l'utilisateur voit trop directement les mécanismes internes:

- HTR
- RBT shadow / authority
- PKPD relief
- damping exercise / tail / late fat
- adaptive basal governance
- physio assistant
- modes repas et prébolus

Alors que son besoin réel est beaucoup plus simple:

- "Je fais trop d'hypo"
- "Je corrige trop lentement les repas non déclarés"
- "Je suis dans la cible mais trop instable"
- "Je veux que l'état physiologique compte davantage"
- "Je veux plus ou moins d'autonomie AIMI"

Le produit doit donc passer d'un **panneau de sous-systèmes** à un **cockpit d'intentions cliniques**.

---

## 2. Principes Produit

### 2.1 Intention avant mécanique

L'utilisateur ne doit pas régler HTR, RBT, MaxIOB priority ou tail damping comme des objets isolés. Il doit régler une intention de comportement, puis AIMI traduit cette intention en paramètres internes.

### 2.2 Conserver les réglages existants sans réécriture silencieuse

Lors d'une mise à jour APK:

- aucune valeur actuelle ne doit être perdue,
- aucune famille ne doit être "devinée" puis écrite sans confirmation,
- le visuel doit montrer à quoi correspondent les réglages actuels,
- le runtime doit rester compatible avec les préférences legacy tant que la migration n'est pas confirmée.

### 2.3 Séparer trois couches

Le futur écran AIMI doit séparer clairement:

1. **Comportement AIMI**
2. **Contexte patient**
3. **Laboratoire expert**

### 2.4 L'Advisor doit recommander des mouvements de famille

Le futur AIMI Advisor ne doit plus seulement recommander une clé brute. Il doit pouvoir dire:

- "déplace la protection d'un cran vers la sécurité",
- "augmente la capture repas non déclaré",
- "augmente le lissage",
- "renforce l'influence physio".

---

## 3. Architecture UX Cible

### 3.1 Bloc A - AIMI Control Center

Ce bloc devient l'entrée principale du produit. Il contient 5 cartes de contrôle.

#### Carte 1 - Protection vs Correction

Question utilisateur:

> "Quand AIMI agit, veux-tu qu'il protège davantage des hypos ou qu'il corrige plus fort les hypers ?"

Contrôle:

- curseur 5 niveaux
- `Tres protecteur` -> `Protecteur` -> `Equilibre` -> `Correctif` -> `Tres correctif`

Preferences internes principalement pilotées:

- `DoubleKey.OApsAIMIMaxSMB`
- `DoubleKey.OApsAIMIHighBGMaxSMB`
- `DoubleKey.OApsAIMIPriorityMaxIobFactor`
- `DoubleKey.OApsAIMIPriorityMaxIobExtraU`
- `DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor`
- `DoubleKey.OApsAIMIRedCarpetRestoreThreshold`

Ce que l'utilisateur observe:

- trop d'hypo -> aller vers la gauche
- hypers qui trainent -> aller vers la droite

#### Carte 2 - Capture Repas et Montees Rapides

Question utilisateur:

> "Quand une montee glycemique ressemble a un repas non declare, veux-tu qu'AIMI soit prudent ou assertif ?"

Controle:

- curseur 5 niveaux
- `Prudent` -> `Standard` -> `Actif` -> `Assertif` -> `Tres assertif`

Preferences internes principalement pilotees:

- `BooleanKey.OApsAIMIautoDriveActive`
- `BooleanKey.OApsAIMIHyperTrajectoryRelease`
- `BooleanKey.OApsAIMIRecursiveBeliefShadow`
- `BooleanKey.OApsAIMIRecursiveBeliefAuthority`
- `DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep`
- `DoubleKey.OApsAIMIautodrivePrebolus`
- `DoubleKey.OApsAIMIautodrivesmallPrebolus`
- `DoubleKey.OApsAIMIHyperEstablishedDevMgdl`
- `DoubleKey.OApsAIMIHyperDeepDevMgdl`

Ce que l'utilisateur observe:

- post-prandiaux non declares mieux captures -> aller vers la droite
- faux repas matinaux, cortisol, dawn ou stress mal interpretes -> aller vers la gauche

#### Carte 3 - Lissage et Stabilite

Question utilisateur:

> "Veux-tu un comportement plus lisse et amorti, ou plus reactif et nerveux ?"

Controle:

- curseur 5 niveaux
- `Tres lisse` -> `Lisse` -> `Equilibre` -> `Reactif` -> `Tres reactif`

Preferences internes principalement pilotees:

- `DoubleKey.OApsAIMISmbTailDamping`
- `DoubleKey.OApsAIMISmbExerciseDamping`
- `DoubleKey.OApsAIMISmbLateFatDamping`
- `BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled`
- `BooleanKey.OApsAIMIDynIsfTrajectoryTuningEnabled`
- `DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction`

Ce que l'utilisateur observe:

- trop de yo-yo, corrections qui s'enchainent -> aller vers la gauche
- corrections trop molles, inertie excessive -> aller vers la droite

#### Carte 4 - Influence Physiologique

Question utilisateur:

> "Dans les situations ambiguës, veux-tu qu'AIMI se fie surtout a la trajectoire glycemique, ou qu'il fasse davantage confiance au corps reel ?"

Controle:

- curseur 3 niveaux
- `Faible` -> `Moderee` -> `Forte`

Preferences internes principalement pilotees ou contextualisees:

- `BooleanKey.AimiPhysioAssistantEnable`
- `BooleanKey.AimiPhysioSleepDataEnable`
- `BooleanKey.AimiPhysioHRVDataEnable`
- `StringKey.ActivitySourceMode`
- `StringKey.OuraPersonalAccessToken`
- utilisation des phases physiologiques, temperature nocturne, HRV, sommeil, activite

Important:

- les modules cliniques comme grossesse, cycle, thyroide, endometriose et night growth restent des **declarations de contexte**,
- ils ne doivent pas etre fondus dans un curseur unique,
- mais leur presence enrichit cette carte.

#### Carte 5 - Autonomie AIMI

Question utilisateur:

> "Jusqu'ou veux-tu laisser AIMI piloter sans intervention manuelle ?"

Controle:

- modes, pas slider libre
- `Observation`
- `Recommandations`
- `Application assistee`
- `Autorite controlee`

Preferences internes principalement pilotees:

- `BooleanKey.OApsAIMIautoDrive`
- `BooleanKey.OApsAIMIautoDriveActive`
- `BooleanKey.OApsAIMIautoDriveAuthoritative`
- `BooleanKey.OApsAIMIRecursiveBeliefShadow`
- `BooleanKey.OApsAIMIRecursiveBeliefAuthority`
- `BooleanKey.OApsAIMIMLtraining`
- `BooleanKey.AimiAuditorEnabled`

Ce que l'utilisateur observe:

- plus de conseil et de shadow au debut,
- plus d'autorite appliquee ensuite,
- une progression lisible du produit.

### 3.2 Bloc B - Contexte Patient

Ce bloc reste visible et explicite, car ce ne sont pas des "styles de comportement" mais des verites cliniques ou biologiques.

Il doit contenir:

- poids, CHO, TDD, via setup ou fiche patient
- grossesse
- honeymoon
- cycle / contraception
- thyroide
- endometriose
- night growth
- SOS urgence

Ces elements ne doivent pas etre caches dans AIMI Lab.

### 3.3 Bloc C - Sources et Permissions

Bloc simple et separe:

- Health Connect
- source activite
- Oura
- permissions de stockage si necessaire

Le but est de sortir les permissions et connecteurs du flux de tuning.

### 3.4 Bloc D - AIMI Lab

Tout ce qui reste technique, experimental ou R&D va ici:

- HTR aggressive
- RBT wavelet
- raw adaptive basal governance
- raw dynISF trajectory tuning
- straight-line tube advisor
- debug physio
- auditor detaille
- comparateurs et modes shadow avancés

Le produit principal ne doit plus commencer par ce bloc.

---

## 4. Mapping des Preferences Actuelles vers les Familles

### 4.1 Regle generale

Chaque carte du Control Center doit etre capable de lire les preferences actuelles et de produire:

- un niveau derive,
- une confiance de projection,
- un badge `Standard`, `Ajuste`, ou `Expert personnalise`.

Si les reglages legacy racontent une histoire coherente, on montre un niveau normalise.

Si les reglages legacy sont heterogenes ou contradictoires, on ne force pas un faux niveau. On affiche:

> `Profil expert personnalise`

et on laisse l'utilisateur consulter le detail.

### 4.2 Projection recommandees

#### Famille Protection vs Correction

Score derive a partir de:

- `OApsAIMIMaxSMB`
- `OApsAIMIHighBGMaxSMB`
- `OApsAIMIPriorityMaxIobFactor`
- `OApsAIMIPriorityMaxIobExtraU`
- `OApsAIMIPkpdPragmaticReliefMinFactor`
- `OApsAIMIRedCarpetRestoreThreshold`

Normalisation recommandee:

- plus la valeur est haute, plus le profil est `correctif`
- projection par moyenne ponderee des valeurs normalisees dans leurs bornes reelles

Bornes source:

- `OApsAIMIMaxSMB` et `HighBGMaxSMB`: 0.05 -> 15.0
- `PriorityMaxIobFactor`: 1.0 -> 1.6
- `PriorityMaxIobExtraU`: 0.0 -> 5.0
- `PragmaticReliefMinFactor`: 0.50 -> 1.0
- `RedCarpetRestoreThreshold`: 0.50 -> 0.95

#### Famille Capture Repas et Montees Rapides

Score derive a partir de:

- `OApsAIMIautoDriveActive`
- `OApsAIMIHyperTrajectoryRelease`
- `OApsAIMIRecursiveBeliefShadow`
- `OApsAIMIRecursiveBeliefAuthority`
- `OApsAIMIMpcInsulinUPerKgPerStep`
- `OApsAIMIautodrivePrebolus`
- `OApsAIMIautodrivesmallPrebolus`

Interpretation:

- V3 actif + HTR actif + MPC haut + prebolus plus haut = profil plus `assertif`
- V3 inactif ou HTR coupe = profil plus `prudent`

Bornes source:

- `OApsAIMIMpcInsulinUPerKgPerStep`: 0.03 -> 0.12
- `OApsAIMIautodrivePrebolus`: 0.1 -> 10.0
- `OApsAIMIautodrivesmallPrebolus`: 0.05 -> 2.0

#### Famille Lissage et Stabilite

Score derive a partir de:

- `OApsAIMISmbTailDamping`
- `OApsAIMISmbExerciseDamping`
- `OApsAIMISmbLateFatDamping`
- `OApsAIMIT3cAdaptiveBasalEnabled`
- `OApsAIMIDynIsfTrajectoryTuningEnabled`
- `OApsAIMIDynIsfTrajectoryMaxFraction`

Interpretation critique:

- pour les dampings AIMI, une valeur basse = amortissement fort,
- une valeur haute = frein plus leger,
- donc plus la valeur est haute, plus le profil est `reactif`.

Bornes source:

- `TailDamping`, `ExerciseDamping`, `LateFatDamping`: 0.0 -> 1.0
- `DynIsfTrajectoryMaxFraction`: 0.02 -> 0.12

#### Famille Influence Physiologique

Projection derivee a partir de:

- `AimiPhysioAssistantEnable`
- `AimiPhysioSleepDataEnable`
- `AimiPhysioHRVDataEnable`
- source d'activite
- presence d'une source Oura exploitable

Important:

- cette famille ne doit pas etre presentee comme une equivalence mathematique parfaite,
- car aujourd'hui la physio continue deja d'influencer certaines branches meme si `AimiPhysioAssistantEnable` est a `OFF`,
- il faut donc afficher ici une projection **qualitative** et non un faux score precis.

#### Famille Autonomie AIMI

Mode derive recommande:

- `Observation` si `OApsAIMIautoDrive = OFF`
- `Recommandations` si `OApsAIMIautoDrive = ON` et `OApsAIMIautoDriveActive = OFF`
- `Application assistee` si `OApsAIMIautoDriveActive = ON` et autorite limitee
- `Autorite controlee` si V3 applique + autorite active

Badges complementaires:

- `ML actif` si `OApsAIMIMLtraining = ON`
- `Audit actif` si `AimiAuditorEnabled = ON`
- `RBT authority` si `OApsAIMIRecursiveBeliefAuthority = ON`

---

## 5. Strategie de Migration Visuelle lors d'une Mise a Jour APK

### 5.1 Regle absolue

La mise a jour ne doit pas "remplacer" les anciens reglages par un nouveau visuel opaque.

Elle doit afficher:

> "Voici comment AIMI interprete vos reglages actuels."

### 5.2 Ecran de premiere ouverture apres mise a jour

Sequence recommandee:

1. scanner les preferences actuelles,
2. calculer le profil derive par famille,
3. afficher une page `Vos reglages AIMI actuels`,
4. laisser trois choix:
   - `Conserver l'equivalent actuel`
   - `Simplifier avec AIMI Control Center`
   - `Voir les reglages experts`

### 5.3 Design de chaque carte de migration

Chaque carte doit afficher:

- le niveau derive,
- un resume comportemental,
- la confiance de projection,
- le nombre de preferences legacy utilisees,
- un bouton `Voir les details`.

Exemple:

> `Protection vs Correction: Correctif`
>
> Derive de 6 reglages actuels
>
> MaxSMB 1.0U, HighBGMaxSMB 1.0U, PriorityMaxIOBFactor 1.20, PriorityExtra +2.0U, Relief 0.75, Red Carpet 0.75

### 5.4 Cas des profils non parfaitement convertibles

Si les reglages sont trop heterogenes:

- afficher `Expert personnalise`
- ne pas bouger les valeurs
- ne pas forcer une position de slider artificielle
- proposer:
  - `Garder tel quel`
  - `Normaliser vers un profil proche`

### 5.5 Ecriture des nouvelles familles

Regle recommandee:

- tant que l'utilisateur n'a pas touche un curseur, on ne reecrit rien,
- les valeurs raw existantes restent la source de verite,
- la nouvelle UI ne fait qu'une lecture interpretee.

Quand l'utilisateur modifie une famille:

- la famille devient source de verite pour les cles qu'elle pilote,
- un panneau detail doit montrer quelles anciennes cles vont changer.

### 5.6 Compatibilite runtime

Phase 1 recommandee:

- runtime inchange,
- lecture des anciennes preferences,
- nouvelle UI seulement derivee.

Phase 2:

- ecriture des familles vers les anciennes preferences,
- mais conservation des cles legacy pour backward compatibility et export.

### 5.7 Export et support

Le support utilisateur et l'audit doivent pouvoir exporter:

- la vue famille,
- les raw keys associees,
- un hash ou snapshot de migration.

Ainsi, un rapport AIMI Advisor ou un bug report peut dire:

- `Profil famille: Capture repas = Assertif`
- `Source: autoDriveActive=true, HTR=true, MPC=0.09, prebolus=1.5`

---

## 6. Evolution Souhaitee de AIMI Advisor

### 6.1 Passage d'un advisor de cles a un advisor de comportement

L'Advisor doit evoluer vers quatre classes principales de recommandations:

- `Pression hypo`
- `Pression hyper`
- `Instabilite / variabilite`
- `Faux repas / ambiguite physiologique`

### 6.2 Exemple de recommandations futures

Si trop d'hypos:

- deplacer `Protection vs Correction` d'un cran vers la gauche
- deplacer `Lissage et Stabilite` d'un cran vers la gauche si les chutes sont tardives

Si trop d'hypers post-prandiaux non declares:

- deplacer `Capture Repas et Montees Rapides` vers la droite
- si besoin deplacer `Protection vs Correction` legerement vers la droite

Si TIR bon mais fortes deviations:

- deplacer `Lissage et Stabilite` vers la gauche

Si faux repas matinaux ou pics endocriniens:

- deplacer `Capture Repas` vers la prudence
- renforcer `Influence Physiologique`

### 6.3 Bouton Apply

Le bouton `Apply` doit montrer:

- le mouvement de famille,
- les cles legacy touchees,
- l'effet comportemental attendu.

Exemple:

> `Appliquer: Capture repas de Standard vers Actif`
>
> Cela va augmenter:
>
> - `OApsAIMIMpcInsulinUPerKgPerStep`
> - `OApsAIMIautodrivePrebolus`
>
> et maintenir:
>
> - `HTR ON`
> - `RBT shadow ON`

---

## 7. Ce qui doit rester explicite hors familles

Tout ne doit pas devenir un slider.

Doivent rester explicites:

- grossesse
- honeymoon
- cycle
- thyroide
- endometriose
- night growth
- poids / CHO / TDD
- SOS urgence
- connecteurs et permissions

Ces elements sont des declarations de realite, pas des styles de comportement.

---

## 8. Ce qui doit sortir du parcours principal

Doivent sortir du flux standard et aller dans `AIMI Lab`:

- `OApsAIMIHyperTrajectoryReleaseAggressive`
- `OApsAIMIRecursiveBeliefWavelet`
- `OApsAIMIDynIsfTrajectoryTuningEnabled`
- `OApsAIMIDynIsfTrajectoryShadowOnly`
- `OApsAIMIStraightLineTubeAdvisorEnabled`
- raw `Adaptive Basal Governance`
- raw `Tube` thresholds
- debug physio
- auditor detaille

La philosophie est:

- **visible si utile au patient**
- **cache si c'est un mecanisme**
- **lab si c'est de la R&D ou du shadow**

---

## 9. Decisions Produit a Trancher Avant Implementation

### 9.1 `OApsAIMIautoDrive` vs `OApsAIMIautoDriveActive`

Le produit doit converger vers un seul choix utilisateur lisible:

- `OFF`
- `AIMI Assist`
- `Autodrive V3`

Les deux booleens actuels sont trop techniques pour l'UX finale.

### 9.2 `OApsAIMIRecursiveBeliefAuthority`

Il faut aligner code, doc et produit.

La doc terrain indique un demarrage prudent en authority off, alors que le code source expose aujourd'hui un default `true`.

### 9.3 Sens reel de `AimiPhysioAssistantEnable`

Le produit doit clarifier:

- ce que coupe vraiment ce switch,
- et ce que la physio continue a influencer meme quand il est a `OFF`.

Sinon le futur curseur `Influence Physiologique` sera incomprehensible.

### 9.4 PKPD par defaut

Le bon modele produit semble etre:

- setup assistant type PKPD,
- puis activation recommande ou assistee,
- plutot qu'un simple boolean brut expose au milieu des autres.

---

## 10. Roadmap de Livraison Recommandee

### Lot 1 - Migration visuelle sans changement runtime

- nouvelle page `AIMI Control Center`
- calcul des familles a partir des prefs existantes
- ecran `Vos reglages actuels`
- detail par famille

Objectif:

- aucun risque de regression comportementale
- gain UX immediat

### Lot 1 - Statut de livraison actuel

Lot 1 est maintenant concretement pose dans l'application sous forme d'un ecran Compose dedie:

- entree `AIMI Control Center` dans les preferences AIMI,
- projection des reglages actuels vers 5 familles de comportement,
- affichage du niveau derive, de la confiance de projection et du detail des cles lues,
- bloc `Contexte patient` separe,
- bloc `Sources physio et connecteurs` separe.

Important:

- cet ecran ne modifie aucune valeur existante,
- aucune option supplementaire n'est a activer pour changer le comportement clinique,
- les preferences legacy restent la source d'autorite runtime,
- l'objectif est de rendre la configuration actuelle lisible avant d'autoriser un futur write-back par famille.

### Lot 2 - Curseurs de famille avec write-back legacy

- chaque famille peut ecrire les cles legacy associees
- preview des impacts avant validation
- badge `Expert personnalise` preserve

### Lot 2 - Statut de livraison actuel

Le `AIMI Control Center` permet maintenant de preparer de vrais changements produit:

- chaque famille AIMI dispose d'un controle Compose actionnable,
- l'utilisateur voit le `profil actuel` puis la `cible apres application`,
- un bloc `Preview impacts` affiche les cles legacy qui seront modifiees,
- rien n'est ecrit tant que l'utilisateur n'a pas valide explicitement l'application.

Le comportement choisi pour ce lot reste volontairement prudent:

- les changements sont toujours ecrits dans les preferences legacy existantes,
- les familles `Contexte patient` et `Sources / connecteurs` restent separees,
- une famille ne pousse pas de reconfiguration silencieuse hors de son role produit,
- le ML et l'auditor restent hors du write-back automatique de l'autonomie pour eviter un effet de bord non intentionnel.

Avant / apres observable:

- avant: l'utilisateur comprend ce que AIMI fait, mais ne peut pas encore le deplacer simplement,
- apres: il peut choisir une intention clinique lisible, voir l'impact exact, puis appliquer.

### Lot 3 - AIMI Advisor oriente familles

- recommandations de comportements
- apply au niveau famille
- justification clinique lisible

### Lot 3 - Statut de livraison actuel

Le `AIMI Control Center` embarque maintenant un premier Advisor directement oriente familles:

- un bloc `AIMI family advisor` apparait au-dessus des cartes,
- les recommandations sont formulees en langage produit et non en raw keys,
- chaque recommandation charge un `brouillon recommande` au niveau famille,
- l'utilisateur garde ensuite la meme preview d'impacts et la meme confirmation explicite avant tout write-back legacy.

Le perimetre volontairement choisi pour ce lot reste pragmatique:

- l'Advisor corrige d'abord les incoherences de comportement entre `Meal capture`, `Autonomy` et `Physio`,
- il ne pousse pas encore de relecture outcome-basee complete a partir de l'historique glycemique,
- il reste donc predictible, explicable et sans effet de bord silencieux.

### Lot 4 - Reduction de la surface legacy

- ecran standard epure
- raw prefs bascules en `AIMI Lab`
- documentation utilisateur alignee

### Lot 4 - Statut de livraison actuel

La navigation AIMI standard est maintenant recentree sur les blocs qui portent une vraie intention produit:

- `AIMI Control Center`
- `PKPD guide`
- `Contexte patient`
- `Physio / connecteurs`
- `SOS`
- `AIMI Lab` pour les reglages experts ou R&D

La reduction de surface appliquee dans ce lot suit une logique de non-regression:

- les reglages legacy bruts les plus techniques restent disponibles, mais sortent du flux standard,
- les familles visibles dans le Control Center restent la voie recommandee pour modifier le comportement,
- le write-back continue d'ecrire dans les cles legacy existantes pour ne pas casser les invariants runtime,
- aucune option supplementaire n'est a activer pour beneficier de cette simplification produit.

Avant / apres observable:

- avant: l'utilisateur traverse un grand nombre de switches et sous-ecrans experts pour trouver le bon comportement,
- apres: le parcours standard commence par l'intention clinique, puis AIMI Lab ne sert plus qu'au reglage avance.

### Lot 5 - Harmonisation runtime pilotee par familles

- UAM / faux repas sensible aux familles
- PKPD module par familles
- ML encadre par familles
- export d'audit aligne

### Lot 5 - Statut de livraison actuel

Le runtime ne se contente plus d'utiliser les familles pour le visuel ou le write-back:

- `Meal capture`, `Physio` et `Autonomy` modulent maintenant les seuils de competition `repas vs non-repas` dans l'arbitre UAM,
- `Protection`, `Meal capture`, `Stability` et `Physio` modulent la facon dont PKPD applique ses facteurs d'absorption, de resistance et d'agressivite,
- le raffinement ML garde le meme schema de modele, mais son autorite de correction est maintenant bornee par le profil famille courant,
- les exports CSV de raffinement embarquent des colonnes d'audit famille pour permettre une analyse offline sans casser le schema existant du modele.

Avant / apres observable:

- avant: les familles agissaient surtout comme une couche UI et de projection sur des cles legacy,
- apres: les familles deviennent aussi une couche runtime de coherence entre trajectoire, etat physiologique, PKPD et affinage ML.

---

## 11. Avant / Apres Attendu pour l'Utilisateur

### Avant

- beaucoup de switches et numeriques sans hierarchie claire,
- difficulte a comprendre ce qui est central vs experimental,
- difficulté a relier un comportement clinique a un ensemble de cles,
- risque de "micro-tuning" confus.

### Apres

- 5 cartes de comportement compréhensibles,
- un bloc contexte patient clair,
- un bloc connecteurs / permissions separé,
- un AIMI Lab reserve a l'expert,
- une mise a jour APK qui explique au lieu de surprendre.

---

## 12. Recommendation Finale

La meilleure simplification n'est pas de supprimer des preferences au hasard. C'est de:

1. **reorganiser autour d'intentions cliniques,**
2. **projeter fidèlement les valeurs actuelles dans ce nouveau visuel,**
3. **garder les raw keys pour compatibilite et audit,**
4. **laisser l'Advisor piloter des familles plutot que des cles unitaires.**

Le modele de reference a suivre est bien celui du setup PKPD, mais etendu a tout AIMI:

- une interface qui parle le langage du resultat clinique,
- un moteur interne qui continue a faire sa sophistication,
- et une migration qui respecte completement l'historique du patient.
