# AIMI Family Runtime Harmonization 2026-06-11

## Objectif

Finaliser la feuille de route produit en faisant des familles AIMI non seulement une couche UI, mais aussi une couche runtime de coherence entre:

- detection repas non declares,
- arbitrage physio vs trajectoire,
- modulation PKPD,
- refinement ML,
- preferences legacy existantes.

## Ce qui est maintenant en place

### 1. Control Center comme entree principale

Le `AIMI Control Center` porte les 5 familles de comportement:

- `Protection`
- `Meal capture`
- `Stability`
- `Physio`
- `Autonomy`

Chaque famille:

- projette les reglages legacy existants,
- permet un ajustement via slider ou mode,
- montre un preview avant application,
- ecrit ensuite dans les cles legacy existantes apres confirmation.

### 2. Advisor oriente familles

L'Advisor du Control Center ne charge plus des cles unitaires brutes. Il propose un brouillon produit au niveau famille, puis laisse l'utilisateur verifier l'impact legacy avant d'appliquer.

### 3. Surface standard simplifiee

Le parcours standard AIMI met en avant:

- `AIMI Control Center`
- `PKPD guide`
- `Contexte patient`
- `Physio / connecteurs`
- `SOS`
- `AIMI Lab` pour l'expert

Les preferences brutes techniques restent disponibles, mais ne pilotent plus l'experience principale.

## Harmonisation runtime appliquee

### UAM et faux repas

Le runtime lit maintenant un `AimiBehaviorRuntimeProfile` derive des familles courantes.

Ce profil module:

- le cap de suppression repas,
- la marge exigee pour qu'un signal non-repas domine,
- le seuil minimal de confiance non-repas,
- la marge exigee pour suppress `meal interpretation`.

Effet produit:

- un profil prudent sur `Meal capture` avec `Physio` actif coupe plus facilement les faux repas hormonaux,
- un profil assertif sur `Meal capture` avec plus d'autorite demande un signal non-repas plus convaincant avant de supprimer l'hypothese repas.

### PKPD

PKPD reste base sur les modules existants, mais les familles influencent maintenant la facon d'appliquer leurs sorties:

- `Physio` determine a quel point les facteurs physiologiques restent proches du neutre ou pleinement appliques,
- `Meal capture` module l'impact de l'absorption repas quand le contexte repas est actif,
- `Protection` module l'agressivite des corrections,
- `Stability` module l'agressivite en phase de montee.

Effet produit:

- moins de decalage entre l'intention choisie dans le cockpit et la reponse PKPD reelle,
- moins de sur-reaction PKPD lorsque la posture choisie est prudente ou tres physiologique.

### Machine learning

Le modele TFLite et le schema principal d'entree ne changent pas.

Le changement produit est volontairement non destructif:

- le raffinement ML garde la meme taille d'entree,
- il ne casse ni le modele embarque, ni la compatibilite de prediction actuelle,
- son amplitude de correction SMB est maintenant limitee par le profil famille courant.

Impact detaille ML:

- `Autonomy` faible reduit l'autorite du raffinement,
- `Protection` prudente resserre davantage le clamp,
- `Physio` fort reduit aussi l'autorite de correction pour laisser plus de place au contexte corporel,
- un profil tres assertif peut conserver une autorite ML un peu plus large.

Pour l'analyse offline:

- des colonnes d'audit famille sont ajoutees au CSV,
- le parseur d'entrainement ignore ces colonnes si le schema principal n'en a pas besoin,
- on peut donc etudier l'effet des familles sans invalider le pipeline existant.

## Impact observable avant / apres

### Avant

- l'utilisateur regle beaucoup de cles sans lien produit lisible,
- les familles sont surtout une projection UI,
- la coherence entre UAM, physio, PKPD et ML depend encore fortement de reglages disperses.

### Apres

- l'utilisateur part d'une intention clinique lisible,
- la meme intention influence la vue, le write-back legacy et une partie du runtime,
- les faux repas hormonaux sont mieux filtres quand la posture est prudente,
- la capture repas reste plus tenace quand la posture est volontairement assertive,
- PKPD et ML se comportent davantage dans le meme sens que le profil choisi.

## Options et activation

Aucune option supplementaire n'est a activer pour beneficier de cette architecture produit.

Le comportement repose sur:

- la projection des reglages actuels dans les familles,
- le write-back legacy existant,
- le runtime profile derive automatiquement de ces familles.

`AIMI Lab` reste l'espace expert pour les reglages bruts, sans casser le parcours principal.

## Point d'attention volontaire

Le raffinement ML est ici encadre, pas re-entraine de force.

Le gain recherche est:

- plus de coherence immediate,
- zero rupture de schema,
- zero divergence entre UX produit et moteur,
- une base propre pour une future evolution du modele avec labels famille si cela devient utile.
