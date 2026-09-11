# AIMI Viewer

Application Android Flutter, locale et en lecture seule, pour visualiser les exports produits par OpenApsAIMI dans `Documents/AAPS`.

## Fonctionnement

1. Au premier lancement, choisir le dossier `Documents/AAPS` dans le sélecteur Android.
2. Android conserve l’autorisation de lecture après redémarrage.
3. Choisir une journée civile locale ou une semaine ISO, puis naviguer avec les chevrons ou le calendrier.
4. Android extrait uniquement la période demandée vers le cache privé, sans modifier les originaux.
5. Les JSONL et CSV extraits sont parcourus ligne par ligne dans un isolate Dart.

Le journal complet peut dépasser 1 Go. AIMI Viewer préfère `AIMI_Decisions.jsonl`, construit dans son stockage privé un index incrémental par journée et offsets d’octets, puis lit uniquement les segments utiles par seek. Si le fournisseur SAF ne permet pas le seek, un parcours séquentiel à mémoire bornée est utilisé. Les lignes de décision sont compactées avant leur écriture dans le cache : le journal complet n’est jamais copié ni chargé en mémoire. `AIMI_Decisions_Last24h.jsonl` n’est utilisé qu’en repli lorsque le journal complet est absent ; il apparaît comme une seule source logique « Décisions AIMI » avec une couverture éventuellement partielle.

## Exports reconnus

- `AIMI_Decisions.jsonl` — source préférée, indexée par jour
- `AIMI_Decisions_Last24h.jsonl` — repli optionnel si le journal complet est absent
- `oapsaimi_pkpd_records.csv` — schéma sans en-tête de 25 colonnes
- `AIMI_HORMONITOR_event_stream_v1.jsonl`
- `AIMI_HORMONITOR_daily_outcomes_v1.jsonl`
- `AIMI_HORMONITOR_dataset_qa_v1.jsonl`
- `AIMI_HORMONITOR_shadow_contributions_v1.jsonl`
- `AIMI_HORMONITOR_loop_blackbox_v1.jsonl`

Les exports QA, shadow et blackbox sont signalés par leurs métadonnées sans être copiés intégralement. `AIMI_HORMONITOR_daily_state_v1.json` est un état interne optionnel et n’est pas requis par le Viewer.

## Lecture des indicateurs

- La période est `[début, fin)` sur des minuits locaux ; elle suit donc correctement les changements d’heure.
- La journée ou semaine en cours s’arrête à l’instant du rafraîchissement ; aucune portion future n’est présentée comme observée.
- Les suivis « jour » et « semaine » utilisent respectivement la journée civile et la semaine ISO du lundi au dimanche.
- Les suivis d’auditeur asynchrones ne sont pas comptés comme de nouvelles décisions de boucle.
- L’état physiologique `UNKNOWN` est conservé sous « État non déterminé » et expliqué comme une absence de classification fiable, pas comme une anomalie.
- Les protections LGS sont explicitées : aucune protection déclenchée, glycémie basse, baisse prédite, baisse à terme ou signal capteur bruité.
- Une phase de cycle `UNKNOWN` n’est pas affichée comme une phase réelle. Une préférence d’affichage locale propose « Non renseigné », « Non applicable » ou « Suivi activé dans AAPS », sans demander le sexe, le genre ou l’âge.
- Sur une semaine, la TDD présentée est la moyenne des dernières valeurs disponibles pour chaque `day_local`.

## Développement

Prérequis : Flutter 3.47 ou plus récent et un SDK Android configuré.

```sh
flutter pub get
flutter test
flutter run
```

Pour produire l’APK :

```sh
flutter build apk --release
```

L’APK se trouve ensuite dans `build/app/outputs/flutter-apk/app-release.apk`.

## Limites et sécurité

- Aucun accès réseau n’est déclaré dans le manifeste de production.
- L’autorisation SAF demandée est une autorisation de lecture du seul dossier choisi.
- Aucun fichier d’AAPS n’est créé, déplacé, renommé ou modifié.
- L’index, les extractions temporaires et la préférence de suivi du cycle restent dans le stockage privé de l’application.
- L’application ne communique ni avec la pompe ni avec le moteur de dosage.
- Les indicateurs sont destinés à l’observation technique et ne remplacent pas une décision médicale.
