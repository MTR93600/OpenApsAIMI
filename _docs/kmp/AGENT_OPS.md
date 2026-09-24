# Agent ops — AIMI → KMP (FR / EN)

> Docs only. No clinical formulas.  
> Companion: [STATUS](../../docs/kmp-migration/STATUS.md) · [DELTA](../../docs/kmp-migration/DELTA-remaining.md)

**Verified 2026-09-19** after `git fetch origin kmp-aimi-migration-study` and `git fetch origin dev_OAPSAIMI`.

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
