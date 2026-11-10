# Dual-brain auditor — état (mis à jour ref. code)

## Résumé

- **Local Sentinel** : implémenté dans `LocalSentinel.kt` (détections score / tiers / recommandations).
- **Orchestration** : **`LocalSentinel.computeAdvice` est appelé depuis `AuditorOrchestrator.auditDecision`** (voir `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/advisor/auditor/AuditorOrchestrator.kt`, zone ~L200+). La logique « Sentinel toujours, auditeur externe conditionnel » y est branchée.
- **Design détaillé** : toujours valable dans `DUAL_BRAIN_AUDITOR_DESIGN.md` pour l’architecture et les scénarios.

## Ancienne section « à implémenter »

Les étapes listées historiquement (intégration orchestrateur + pipeline `DetermineBasalAIMI2`) **étaient un plan de travail** ; l’intégration **Sentinel → `AuditorOrchestrator`** est **faite** dans le dépôt actuel. Le reste (affinements helpers SMB 30 min / historique BG, logs RT premium, tests scénarios exhaustifs) relève d’**itérations** optionnelles, pas d’un blocage « non câblé » au niveau Sentinel.

## Suivi

Pour le détail refactor AIMI + tests moteur, voir aussi `docs/AIMI_REFACTOR_CHECKLIST.md`.
