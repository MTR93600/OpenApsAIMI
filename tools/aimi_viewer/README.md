# AIMI Viewer

Application Android Flutter, locale et en lecture seule, pour visualiser les exports produits par OpenApsAIMI dans `Documents/AAPS`.

## Fonctionnement

1. Au premier lancement, choisir le dossier `Documents/AAPS` dans le sélecteur Android.
2. Android conserve l’autorisation de lecture après redémarrage.
3. L’application copie uniquement les fichiers reconnus vers son cache privé, sans modifier les originaux.
4. Les JSONL et CSV sont parcourus ligne par ligne dans un isolate Dart.
5. L’écran est recalculé sur une fenêtre glissante de 24 heures.

Le journal complet peut devenir volumineux. Pour préserver la mémoire et le stockage du téléphone, la passerelle Android ne met en cache que la fin des gros journaux cumulatifs. L’export `AIMI_Decisions_Last24h.jsonl` est préféré lorsqu’il existe.

## Exports reconnus

- `AIMI_Decisions_Last24h.jsonl`
- `AIMI_Decisions.jsonl`
- `oapsaimi_pkpd_records.csv` — schéma sans en-tête de 25 colonnes
- `AIMI_HORMONITOR_event_stream_v1.jsonl`
- `AIMI_HORMONITOR_daily_outcomes_v1.jsonl`
- `AIMI_HORMONITOR_dataset_qa_v1.jsonl`
- `AIMI_HORMONITOR_shadow_contributions_v1.jsonl`
- `AIMI_HORMONITOR_loop_blackbox_v1.jsonl`
- `AIMI_HORMONITOR_daily_state_v1.json`

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
- L’application ne communique ni avec la pompe ni avec le moteur de dosage.
- Les indicateurs sont destinés à l’observation technique et ne remplacent pas une décision médicale.
