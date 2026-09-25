# Agent ops — AIMI → KMP (FR / EN)

> Docs only. No clinical formulas.  
> Companion: [STATUS](../../docs/kmp-migration/STATUS.md) · [DELTA](../../docs/kmp-migration/DELTA-remaining.md)

**Verified 2026-09-19** after `git fetch origin kmp-aimi-migration-study` and `git fetch origin dev_OAPSAIMI`.

**Inventaire 2026-09-24 :** le tip code étudié est `ce1384814e` ; le tip ref est `166ddb6db0` (plus `c653fc4485`). Lots suivants : [DELTA](../../docs/kmp-migration/DELTA-remaining.md). Ce fichier d’ops ne change pas de rôles.

**Inventory 2026-09-24:** studied code tip is `ce1384814e`; ref tip is `166ddb6db0` (not `c653fc4485`). Next lots: [DELTA](../../docs/kmp-migration/DELTA-remaining.md). Roles in this file are unchanged.

---

## FR — modèle d’exploitation

Lots cliniques et lots docs : **un PR GitHub par lot**, base `kmp-aimi-migration-study`.  
Le code est produit par des **cloud agents Cursor**. L’orchestrateur n’a **pas besoin d’un clone local**.

### Rôles

| Rôle | Branche / focus | Job |
|---|---|---|
| **Migration KMP** (orchestrateur) | coordonne | fusionne les lots, lance les cloud agents, demande le **GO** utilisateur, tient les todos |
| **AIMI Référence** | `dev_OAPSAIMI` | ancre / vérité clinique vs tip |
| **KMP Delta** | `kmp-aimi-migration-study` | delta restant, inventaires |
| **Qualité KMP Milos** | conventions study KMP | Metro, sourcesets, prefs, **pas de Hilt dans `commonMain`** |
| **Review Portage AIMI** | fidélité du port clinique | mergeable vs formules du tip, call-sites, tests |

### Triple feu (avant chaque merge clinique)

Avant de merger un lot clinique, **Ancre + Qualité + Delta + Portage** doivent être **GO** ou **MERGEABLE**. Les caveats sont OK ; un **NO_GO** bloque.

1. **Ancre** (AIMI Référence) — le tip / freeze visé est le bon SHA ; rien d’inventé.
2. **Qualité** — Metro, sourcesets, prefs, pas de Hilt/`javax.inject` dans `commonMain`.
3. **Delta** — le lot est le delta annoncé ; hors-scope tenu.
4. **Portage** — formules / call-sites / tests = tip (ou fusion KMP documentée), pas un overwrite Android aveugle.

L’orchestrateur demande ensuite le **GO** utilisateur, puis merge **un** PR.

### Docs ancre conflictuels (superseded)

Les PRs docs ancre qui ont divergé / conflicté ont été **fermés sans merge**, remplacés par ce tracker consolidé :

[#73](https://github.com/MTR93600/OpenApsAIMI/pull/73) · [#74](https://github.com/MTR93600/OpenApsAIMI/pull/74) · [#83](https://github.com/MTR93600/OpenApsAIMI/pull/83) · [#85](https://github.com/MTR93600/OpenApsAIMI/pull/85) · [#87](https://github.com/MTR93600/OpenApsAIMI/pull/87) · [#89](https://github.com/MTR93600/OpenApsAIMI/pull/89)

`mergedAt = null` (vérifié `gh` 2026-09-19). Les ancres mergées plus tard (#81, #91–#99, #102–#116) restent la preuve par lot.

---

## EN — operating model

Clinical lots and docs lots: **one GitHub PR per lot**, base `kmp-aimi-migration-study`.  
Code is written by **Cursor cloud agents**. The orchestrator does **not** need a local clone.

### Roles

| Role | Branch / focus | Job |
|---|---|---|
| **Migration KMP** (orchestrator) | coordinates | merge lots, launch cloud agents, ask the user for **GO**, keep todos |
| **AIMI Référence** | `dev_OAPSAIMI` | anchor / clinical truth vs tip |
| **KMP Delta** | `kmp-aimi-migration-study` | remaining delta, inventories |
| **Qualité KMP Milos** | study KMP conventions | Metro, sourcesets, prefs, **no Hilt in `commonMain`** |
| **Review Portage AIMI** | clinical port fidelity | mergeable vs tip formulas, call sites, tests |

### Triple feu (before each clinical merge)

Before merging a clinical lot, **Ancre + Qualité + Delta + Portage** must be **GO** or **MERGEABLE**. Caveats are OK; **NO_GO** blocks.

1. **Ancre** (AIMI Référence) — the intended tip / freeze SHA is the one fetched; nothing invented.
2. **Qualité** — Metro, sourcesets, prefs, no Hilt/`javax.inject` in `commonMain`.
3. **Delta** — the lot is the announced delta; out-of-scope held.
4. **Portage** — formulas / call sites / tests match the tip (or a documented KMP fuse), not a blind Android overwrite.

The orchestrator then asks the user for **GO**, then merges **one** PR.

### Conflicting docs-ancre PRs (superseded)

Docs-ancre PRs that conflicted were **closed unmerged** in favour of this consolidated tracker:

[#73](https://github.com/MTR93600/OpenApsAIMI/pull/73) · [#74](https://github.com/MTR93600/OpenApsAIMI/pull/74) · [#83](https://github.com/MTR93600/OpenApsAIMI/pull/83) · [#85](https://github.com/MTR93600/OpenApsAIMI/pull/85) · [#87](https://github.com/MTR93600/OpenApsAIMI/pull/87) · [#89](https://github.com/MTR93600/OpenApsAIMI/pull/89)

`mergedAt = null` (checked with `gh` on 2026-09-19). Later merged anchors (#81, #91–#99, #102–#116) remain the per-lot proof.

---

## FR — SDK Android des cloud agents

`.cursor/` existait déjà (règles sous `.cursor/rules/`). Il n’y avait pas de `.cursor/environment.json` : le fichier a été ajouté à côté des règles, sans les modifier.

Cursor lit `.cursor/environment.json` de la révision démarrée (schéma public `environment.schema.json`, champ `install`). Au démarrage d’un agent sur une révision qui contient ce fichier, la commande suivante installe le SDK de façon idempotente :

```bash
bash .cursor/scripts/install-android-sdk.sh
```

**Ne pas lancer ce script en local.** Il écrit dans `~/.gradle` (dont `init.d/cursor-cloud-test-forks.gradle`, qui s’applique à tous les builds Gradle de l’utilisateur), fait des `sudo` et écrit `/etc/profile.d`. En tête, il s’arrête avec un message et un code non nul si le processus n’est pas dans une VM d’agent cloud Cursor, sauf opt-in explicite `AAPS_ALLOW_LOCAL_SDK_INSTALL=1`.

Signal retenu, vérifié sur une VM cloud : `/proc/self/cgroup` contient `cursor-agent`. Le shell agent est dans `…/cursor-agent/workload`, et `exec-daemon` (celui qui exécute `install`) est dans `…/cursor-agent/daemon`. `CURSOR_AGENT=1` est présent dans le shell agent mais absent de l’environnement d’`exec-daemon`, donc ce n’est pas le signal.

Le script pose cmdline-tools 23.0 (sha1 vérifié), `platform-tools`, `platforms/android-37.0` (`Versions.compileSdk = 37`) et `build-tools/36.0.0` (minimum et défaut d’AGP `9.4.0` dans `gradle/libs.versions.toml`). JDK 21 : si `JAVA_HOME` pointe déjà vers un JDK 21 il est gardé ; sinon le script prend `/usr/lib/jvm/java-21-openjdk-*` (architecture non figée) ou un `java` 21 déjà sur le `PATH`, et n’installe `openjdk-21-jdk-headless` que s’il manque. Limite : les layouts autres que Debian/Ubuntu (Homebrew, SDKMAN, etc.) ne sont pas sondés — poser `JAVA_HOME` vers un JDK 21. `sdk.dir` est écrit dans `local.properties` (gitignoré, ne pas committer). Les variables sont aussi dans `/etc/profile.d/android-sdk.sh`. Le code 141 de `yes | sdkmanager` (SIGPIPE) est attendu.

Tests hôte Android de ce lot (après l’install) :

```bash
./gradlew :plugins:calibration:jvmTest :plugins:calibration:testAndroidHostTest
```

Mémoire : ne pas modifier le `gradle.properties` du dépôt. Sur Gradle 9.7, `~/.gradle/gradle.properties` (utilisateur) prime sur le fichier du projet, donc le script y écrit `org.gradle.jvmargs=-Xmx3g -XX:+UseParallelGC -Xss1024m` : seul `-Xmx` passe de 8g à 3g, `-Xss1024m` du dépôt est conservé. `maxParallelForks` n’est pas une clé `gradle.properties` : `buildSrc` le calcule avec `availableProcessors() / 2` (au moins 1). Le script écrit donc `~/.gradle/init.d/cursor-cloud-test-forks.gradle`, appliqué après l’évaluation des projets, pour forcer `maxParallelForks = 1`.

Espace disque : prévoir environ **12 Go** une fois le cache Gradle du monorepo rempli (SDK + distributions + dépendances), en plus du checkout.

## EN — Cloud agent Android SDK

`.cursor/` already existed (rules under `.cursor/rules/`). There was no `.cursor/environment.json`; it was added beside the rules and does not modify them.

Cursor reads `.cursor/environment.json` from the revision the agent starts on (public schema `environment.schema.json`, `install` field). On startup, a revision that contains this file runs:

```bash
bash .cursor/scripts/install-android-sdk.sh
```

**Do not run this script locally.** It writes `~/.gradle` (including `init.d/cursor-cloud-test-forks.gradle`, which applies to every Gradle build of that user), uses `sudo`, and writes `/etc/profile.d`. At the top it exits with a message and a non-zero status unless the process is on a Cursor cloud agent VM, or `AAPS_ALLOW_LOCAL_SDK_INSTALL=1` is set.

Signal, checked on a cloud VM: `/proc/self/cgroup` contains `cursor-agent`. The agent shell is in `…/cursor-agent/workload`, and `exec-daemon` (the process that runs `install`) is in `…/cursor-agent/daemon`. `CURSOR_AGENT=1` is set in the agent shell but not in the exec-daemon environment, so it is not the signal.

The script installs cmdline-tools 23.0 (sha1 checked), `platform-tools`, `platforms/android-37.0` (`Versions.compileSdk = 37`), and `build-tools/36.0.0` (AGP `9.4.0` minimum and default, from `gradle/libs.versions.toml`). JDK 21: an existing `JAVA_HOME` that is already 21 is kept; otherwise the script picks `/usr/lib/jvm/java-21-openjdk-*` (architecture not pinned) or a JDK 21 `java` already on `PATH`, and installs `openjdk-21-jdk-headless` only when none is found. Limit: non-Debian/Ubuntu layouts (Homebrew, SDKMAN, and similar) are not searched — set `JAVA_HOME` to a JDK 21. `sdk.dir` is written to gitignored `local.properties` (do not commit it). The same variables are written to `/etc/profile.d/android-sdk.sh`. Exit code 141 from `yes | sdkmanager` (SIGPIPE) is expected.

Host Android tests for this lot (after install):

```bash
./gradlew :plugins:calibration:jvmTest :plugins:calibration:testAndroidHostTest
```

Memory: do not edit the repo `gradle.properties`. On Gradle 9.7, the user `~/.gradle/gradle.properties` overrides the project file, so the script writes `org.gradle.jvmargs=-Xmx3g -XX:+UseParallelGC -Xss1024m` there: only `-Xmx` changes from 8g to 3g, and the repo `-Xss1024m` is kept. `maxParallelForks` is not a `gradle.properties` key: `buildSrc` sets it from `availableProcessors() / 2` (at least 1). The script therefore writes `~/.gradle/init.d/cursor-cloud-test-forks.gradle`, applied after project evaluation, to force `maxParallelForks = 1`.

Disk: allow about **12 GB** once the monorepo Gradle cache is warm (SDK + distributions + dependencies), on top of the checkout.
