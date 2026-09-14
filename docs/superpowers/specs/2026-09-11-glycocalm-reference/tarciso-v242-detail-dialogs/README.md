# Provenance

Copied read-only from https://github.com/TarcisoSilva/OpenApsAIMISMBonly, branch `test/v242`,
commit `dee8a052eac9cc43ef5194a296122f728b914991` ("v242: Glass dialogs battery/prime/sensor/loop/target/insulin
+ dark theme + JDK 17 guard + cleanup", 2026-09-11).

Original location in that repo:
`plugins/main/src/main/kotlin/app/aaps/plugins/main/general/overview/glass/`

These are REFERENCE material for
`docs/superpowers/specs/2026-09-11-glass-pill-detail-screens-design.md` — a structural/functional reference to
port from, not code to copy verbatim. They use that repo's own Fragment/ViewModel/data-source wiring, which
differs from ours (same relationship the original Glass main-screen port had to its own reference fork). They
also do NOT implement a consistent "GlycoCalm" visual system (see the design spec's research findings) — only
2 accidental hex-token overlaps, no Plus Jakarta Sans, inconsistent corner radii, still branch on `isDark` to a
separate dark variant. Treat as a source for dialog STRUCTURE and FUNCTIONAL LOGIC (what data each dialog
shows, what actions it exposes), not as a visual source of truth — visual styling for the ported version
should follow `../DESIGN.md`/`../code.html` instead.
