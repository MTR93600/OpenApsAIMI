# Plan de dev — Dexcom ONE+ : warm-up sur le dashboard + basal profil de sécurité

> Statut : **PLAN — à valider avant lancement du développement.**
> ⚠️ Contient une tâche **safety-critical** (administration d'insuline sans glycémie). Toute la
> partie « basal profil pendant warm-up » doit être relue par un mainteneur/clinicien et validée en
> device avant merge. Ce document n'est pas un conseil médical.

## 1. Objectif

Après « Connect », l'utilisateur **revient au dashboard** et suit le warm-up de là (décompte +
retour de glycémie annoncé), **sans être bloqué sur un écran dédié**. Trois exigences dures :

1. **Continuité en veille** — le warm-up (session BLE) continue téléphone verrouillé/en veille.
2. **Visibilité** — décompte + phase honnête visibles sur le dashboard **et** en notification.
3. **Sécurité basal** — dès le lancement du warm-up (pas de glycémie), la pompe passe au **basal du
   profil** et y reste jusqu'au retour de la glycémie ; le mécanisme se déclenche **d'emblée**.

## 2. Étude d'architecture (vérifiée)

### 2.1 Dashboard ≠ Overview (deux UIs indépendantes)
`OverviewEntryFragment.showSelectedOverview()` choisit selon `BooleanKey.OverviewUseDashboardLayout`
([OverviewEntryFragment.kt:66-70](../plugins/main/src/main/kotlin/app/aaps/plugins/main/general/overview/OverviewEntryFragment.kt#L66)) :
- `true` → **`DashboardFragment`** (fork AIMI, `general/dashboard/`, Compose + Vico) — **cible**.
- `false` → `OverviewFragment` (classique, `general/overview/`) — **hors périmètre, ne pas toucher**.

Le « rond du haut » du dashboard :
- **Décision de contenu** : `general/dashboard/compose/DashboardComposeHeroUiMapper.buildHeroState(context, StatusCardState): GlucoseHeroUiState?`
  ([mapper](../plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/compose/DashboardComposeHeroUiMapper.kt#L17)).
- **Rendu / fallback `"---"`** : `general/dashboard/compose/DashboardCircleTopCompose.kt` (~L167).
- **Données** : `general/dashboard/viewmodel/OverviewViewModel.kt` produit le `StatusCardState`.
- **Composant d'anneau** : `core.ui.compose.dashboard.GlucoseHeroRing` + `GlucoseHeroUiState`
  ([GlucoseHeroRing.kt:37](../core/ui/src/main/kotlin/app/aaps/core/ui/compose/dashboard/GlucoseHeroRing.kt#L37)) —
  champs `mainText`, `subLeftText`, `subRightText`, `telemetryProgress`, `ringColorArgb`,
  `centerTextColorArgb`, `telemetryColorArgb`… **suffisants pour exprimer le warm-up → PAS de
  changement `:core:ui` nécessaire.**

### 2.2 Continuité en veille (déjà acquise)
La session tourne sur le daemon `OnePlusBleExecutor` du singleton driver, **pas sur une Activity**.
Aucune activité n'appelle `disconnect/shutdown` en cycle de vie ; le seul `shutdown()` est
`DexcomOnePlusPlugin.onStop()` (plugin désactivé). `onStart()` fait même `resumeStoredSession()`.
Process maintenu foreground par `DummyService` (FGS `specialUse`). **Preuve terrain : 6 glycémies
reçues téléphone en veille (2026-07-27).** ⇒ « rester au dashboard » n'exige aucun nouveau service.

### 2.3 Comportement sans glycémie (LE point safety)
- Pendant `WARMING`, `DexcomOnePlusIngest.isWarmupBlockingIngest` = true → **aucune glycémie ingérée**.
- APS AIMI : `glucoseStatus == null` → `EventResetOpenAPSGui(no_glucose_data)`, **retour sans
  recommandation** ([:838](../plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OpenAPSAIMIPlugin.kt#L838), [:1237](../plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OpenAPSAIMIPlugin.kt#L1237)).
- `LoopPlugin` : `apsResult == null` ([:730](../plugins/aps/src/main/kotlin/app/aaps/plugins/aps/loop/LoopPlugin.kt#L730)) → **ne pose pas de nouveau basal**.
- **Conséquence actuelle (à confirmer en device) : le dernier temp basal en cours PERSISTE pendant
  tout le warm-up.** Si un temp élevé tournait avant, il continue sans donnée → risque. C'est
  exactement le trou que l'exigence 3 doit combler.
- Leviers disponibles : `loop.invoke(initiator, allowNotification, tempBasalFallback)`
  ([Loop.kt:82](../core/interfaces/src/main/kotlin/app/aaps/core/interfaces/aps/Loop.kt#L82) — « true if called from failed SMB »),
  re-invoke tempBasalFallback ([LoopPlugin.kt:896](../plugins/aps/src/main/kotlin/app/aaps/plugins/aps/loop/LoopPlugin.kt#L896)),
  `commandQueue.tempBasalAbsolute(...)` ([:1092](../plugins/aps/src/main/kotlin/app/aaps/plugins/aps/loop/LoopPlugin.kt#L1092)) / annulation de temp.

### 2.4 Déjà livré (incrément 1, compile, non commité)
- `DexcomOnePlusPlugin.warmup: StateFlow<OnePlusWarmupState>` (mis à jour dans `onWarmup`).
- `DexcomOnePlusWarmupNotification` — notif ongoing (phases honnêtes + décompte chronomètre), annulée en `onStop`.

## 3. Exigences détaillées

| # | Exigence | Type |
|---|---|---|
| E1 | Warm-up visible sur le dashboard (anneau : décompte + phase honnête `CONNECTING`/`RECONNECTING`/`WARMING`) | Fonctionnel |
| E2 | Notification persistante (fait — incr. 1) | Fonctionnel |
| E3 | Connect → **retour dashboard** (plus d'écran bloquant imposé) | Fonctionnel |
| E4 | Écrans plugin rafraîchis (Start guidé + détail warm-up optionnel) | Fonctionnel |
| **S1** | **Dès `WARMING`/`CONNECTING` sans glycémie → basal profil garanti** (annuler tout temp résiduel) | **Safety** |
| **S2** | **S1 déclenché d'emblée au lancement du warm-up**, ré-affirmé périodiquement, **fonctionne en veille** | **Safety** |
| **S3** | **Handoff propre** : au retour de la glycémie (`READY`/1ʳᵉ EGV), la boucle normale reprend sans lutte | **Safety** |
| N1 | Zéro régression pour les autres sources / le dashboard des non-ONE+ / l'overview classique | Non-régression |
| N2 | Anneau warm-up **générique** (overview lit « source active en warm-up ? », jamais ONE+ en dur) | Archi |
| C1 | Imports explicites, strings EN uniquement, pas d'attrs Android en Compose, valeurs de thème, pas de nouvelle dépendance inter-module non discutée | Conventions projet |

## 4. Contrats d'interface (à figer par A1 avant le reste)

Dans `:core:interfaces` (évite une dépendance `:plugins:main → :plugins:source`) :
```kotlin
// core.interfaces.source (ou overview)
data class CgmWarmupStatus(
    val active: Boolean,          // true tant que warm-up / (re)connexion en cours
    val phase: Phase,             // CONNECTING | RECONNECTING | WARMING | ...
    val remainingMs: Long?,       // décompte si connu (protocole)
    val endsAtEpochMs: Long?,
    val message: String?,
) { enum class Phase { CONNECTING, RECONNECTING, WARMING, PAIRING, OTHER } }

interface CgmWarmupProvider { val warmupStatus: StateFlow<CgmWarmupStatus?> }  // null = rien à afficher
```
- `DexcomOnePlusPlugin` implémente `CgmWarmupProvider` (map depuis `OnePlusWarmupState`).
- `OverviewViewModel` lit `(activePlugin.activeBgSource as? CgmWarmupProvider)?.warmupStatus`.
- La couche **safety** consomme le **même** flux (source de vérité unique).

## 5. Découpage par agent (mode multi-agent)

Ordonnancement : **A1 (contrat) d'abord** → puis **A2, A3, A4 en parallèle** → **A5 vérifie en continu + final**.
Chaque agent : `dangerouslyDisableSandbox` non requis ; builds en **redirect (pas pipe)** pour un exit code fiable ;
compile **module par module** (`--no-daemon`, `> build.log 2>&1`, puis grep `^e: ` / `BUILD`).

### A1 — Contrat & État (fondation)
- **Périmètre** : le contrat §4, exposition depuis ONE+.
- **Fichiers** : `core/interfaces/.../source/CgmWarmupStatus.kt` + `CgmWarmupProvider` (nouveaux) ;
  `plugins/source/.../DexcomOnePlusPlugin.kt` (implémente le provider, mappe `OnePlusWarmupState → CgmWarmupStatus`, réutilise le `warmup: StateFlow` existant).
- **Sortie / contrat** : interface stable + provider ONE+ branché.
- **Acceptation** : `:core:interfaces` et `:plugins:source` compilent ; `activeBgSource` castable en `CgmWarmupProvider` ; aucun nouveau `implementation(project(...))` ajouté.
- **Dépend de** : incrément 1 (déjà là).

### A2 — Anneau warm-up sur le dashboard (fork dashboard)
- **Périmètre** : E1, N1, N2 — **uniquement `general/dashboard/`**, **pas** `:core:ui`, **pas** l'overview classique.
- **Fichiers** :
  - `general/dashboard/viewmodel/OverviewViewModel.kt` — collecter `CgmWarmupProvider.warmupStatus` de la source active ; injecter dans `StatusCardState`.
  - `general/dashboard/compose/DashboardComposeHeroUiMapper.kt` — si **pas de BG valide + warm-up actif** → construire un `GlucoseHeroUiState` warm-up : `mainText = mm:ss` (ou « --- » si `remainingMs` inconnu), `subLeftText = "WARM-UP"`, `subRightText = fin`, `telemetryProgress`, couleurs teal (via thème, pas d'attr Android).
  - `general/dashboard/compose/DashboardCircleTopCompose.kt` — remplacer le fallback `"---"` par l'état warm-up quand présent.
- **Acceptation** : layout dashboard → anneau montre le décompte pendant `WARMING`, « reconnexion… » pendant `RECONNECTING`, et **redevient glycémie normale** dès qu'une valeur arrive ; **si aucune source ne remonte de warm-up, `StatusCardState`/hero inchangés** (diff nul de comportement) ; layout classique intact.
- **Dépend de** : A1.

### A3 — SAFETY : basal profil pendant warm-up ⚠️
- **Périmètre** : S1, S2, S3.
- **Étape 0 (obligatoire) — constater l'existant** : reproduire en device/log qu'un temp basal
  résiduel persiste pendant le warm-up (ou prouver le contraire). **Ne rien coder avant ce constat.**
- **Mécanisme visé** : à l'entrée en warm-up (transition vers `CONNECTING`/`WARMING`, source ONE+
  active, pas de glycémie valide) → **garantir le basal profil** : annuler tout temp en cours
  (retour au basal programmé) et/ou `loop.invoke("DexcomOnePlus:warmup", allowNotification=false, tempBasalFallback=true)`
  **après avoir vérifié** que ce chemin pose bien le basal profil quand `apsResult == null` (sinon
  action explicite d'annulation de temp via `commandQueue`).
- **Déclenchement (S2)** : câblé sur le **flux d'état** (`CgmWarmupProvider` / driver), **pas** sur une
  Activity → fonctionne en veille (tourne sur le process/daemon maintenu par `DummyService`).
  Ré-affirmer à intervalle (aligné KeepAlive / cycle warm-up) tant que warm-up actif et pas de glycémie.
- **Handoff (S3)** : à la 1ʳᵉ glycémie valide / `READY`, **cesser** de forcer le basal profil ; laisser
  la boucle normale reprendre ; garantir l'absence de double-commande ou de lutte.
- **Fichiers probables** : point d'observation dans `:plugins:source` (plugin ONE+ qui possède l'état
  et déjà `@Inject` des deps) appelant `Loop`/`CommandQueue` via interfaces `core:interfaces` ;
  **ne pas** modifier l'algo APS lui-même. Vérifier disponibilité de `Loop`/`CommandQueue` en injection ici (sinon, exposer un hook).
- **Acceptation** : device — au lancement du warm-up, TBR affiché = **basal profil** en < 1 cycle ;
  **aucune** administration basée sur donnée absente/vieille ; en veille idem ; au retour glycémie,
  boucle normale reprend ; open-loop / suspend respectés ; contraintes (maxBasal) respectées.
- **Revue** : **safety-critical** — revue mainteneur/clinicien + test device obligatoires avant merge.
- **Dépend de** : A1.

### A4 — Flux & écrans plugin
- **Périmètre** : E3, E4.
- **Fichiers** :
  - `plugins/source/.../activities/DexcomOnePlusStartActivity.kt` — `onStarted` : `finish()` vers le
    dashboard (ne plus `startActivity(WarmupActivity)`). Vérifier que **rien** n'arrête la session
    (aucun `disconnect/shutdown` ajouté).
  - Refonte Compose Start (parcours guidé : stepper, code, liste RSSI, « retour dashboard ») et écran
    détail warm-up (anneau teal + phase + session BLE) — cf. maquette artefact.
  - Notification (incr. 1) : ajustements mineurs si besoin (icône dédiée si ajoutée à `:core:ui`).
- **Acceptation** : Connect → dashboard, session **non interrompue** (log : pas de `down reason=…` à la
  navigation) ; écrans conformes maquette ; strings EN only ; `stringResource` en Compose.
- **Dépend de** : A2 (le flux ne doit atterrir que quand le dashboard sait afficher le warm-up) ; **E3
  ne merge pas avant A2** pour éviter un état où le warm-up n'est visible nulle part.

### A5 — Vérification (agent dédié) 🔎
- **Compilation** : `:core:interfaces`, `:plugins:source`, `:plugins:main`, `:core:ui` (si touché) — chacun en redirect, grep `^e: `/`BUILD`.
- **Tests unitaires** : mapper warm-up (mm:ss, phase→UiState), `CgmWarmupStatus` mapping, non-régression `DexcomOnePlusIngest`, policy reconnect. Lancer `:plugins:source:testFullDebugUnitTest` + `:plugins:main` ciblés.
- **Conventions (C1)** : imports explicites, strings EN uniquement (pas de trad modifiée), pas de `rh.gac`/attr Android en Compose, valeurs de thème, **aucune** nouvelle dépendance inter-module.
- **Revue safety adversariale (A3)** : chercher activement les cas où (a) un temp résiduel survit au
  lancement warm-up, (b) une administration se fait sans glycémie, (c) la veille casse le déclenchement,
  (d) le handoff au retour glycémie provoque une double-commande. Rapport de findings **vérifiés**.
- **Protocole device** (capteur neuf) : Connect → (1) notif ongoing + phases ; (2) dashboard = anneau
  décompte ; (3) **TBR = basal profil** pendant tout le warm-up, y compris **écran verrouillé** ;
  (4) glycémie qui revient → anneau glycémie + boucle normale ; (5) non-ONE+ : dashboard inchangé.
  Récupérer `dexcom.rtf` + capture TBR/loop.
- **Sortie** : liste de findings priorisés (safety d'abord), + GO/NO-GO par exigence.

## 6. Risques & points à valider

- **[Safety] `tempBasalFallback` ne pose peut-être PAS le basal profil quand `apsResult == null`** →
  A3 doit le prouver et, à défaut, utiliser une annulation de temp explicite. **À trancher tôt.**
- **[Safety] Interaction avec suspend / open-loop / max-IOB** — le forçage basal profil doit respecter
  l'état de boucle (suspendu → ne rien administrer) et les contraintes.
- **[Archi] Accès `Loop`/`CommandQueue` depuis `:plugins:source`** — vérifier l'injection ; sinon hook `core:interfaces`.
- **[Archi] `activeBgSource` castable en `CgmWarmupProvider`** — confirmer l'API `activePlugin`.
- **[UX] `remainingMs` inconnu tôt** — l'anneau affiche « warming… » indéterminé jusqu'à ce que le protocole fournisse le temps.

## 7. Definition of Done
Tous E1-E4 + S1-S3 + N1-N2 + C1 satisfaits ; modules compilent ; tests verts ; **revue safety + device signés** ;
rien de commité sans validation utilisateur ; NOTICE/attribution inchangés (aucune nouvelle source amont).

---
*Plan préparé en tant qu'architecte logiciel senior. La section safety (A3 / S1-S3) est un livrable de
travail à faire valider par un mainteneur/clinicien du projet avant toute mise en œuvre en production.*
