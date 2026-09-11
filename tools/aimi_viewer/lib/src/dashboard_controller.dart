import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'analysis_period.dart';
import 'export_parser.dart';
import 'models.dart';
import 'storage_bridge.dart';

class DashboardController extends ChangeNotifier {
  DirectoryGrant? directory;
  DashboardData? data;
  AnalysisPeriod period = AnalysisPeriod.day(DateTime.now());
  HormoneTrackingPreference hormonePreference =
      HormoneTrackingPreference.unspecified;
  bool busy = false;
  String? errorMessage;

  Future<void> initialize() async {
    try {
      directory = await AndroidStorageBridge.currentDirectory();
      hormonePreference = await AndroidStorageBridge.hormonePreference();
      notifyListeners();
      if (directory != null) await refresh();
    } on PlatformException catch (error) {
      errorMessage = _friendlyPlatformError(error);
      notifyListeners();
    }
  }

  Future<void> chooseDirectory() async {
    try {
      final selected = await AndroidStorageBridge.chooseDirectory();
      if (selected == null) return;
      directory = selected;
      data = null;
      errorMessage = null;
      notifyListeners();
      await refresh();
    } on PlatformException catch (error) {
      errorMessage = _friendlyPlatformError(error);
      notifyListeners();
    }
  }

  Future<void> refresh() async {
    if (busy || directory == null) return;
    busy = true;
    errorMessage = null;
    notifyListeners();
    try {
      final windowEndMs = period.endMsAt(DateTime.now());
      final staged = await AndroidStorageBridge.stageFiles(
        startMs: period.startMs,
        endMs: windowEndMs,
      );
      final parsed = await compute(parseExportsInBackground, <String, Object?>{
        'windowStartMs': period.startMs,
        'windowEndMs': windowEndMs,
        'files': staged.map((file) => file.toParserMap()).toList(),
      });
      data = DashboardData.fromMap(parsed);
    } on PlatformException catch (error) {
      errorMessage = _friendlyPlatformError(error);
    } on Object catch (error) {
      errorMessage = 'Lecture impossible : $error';
    } finally {
      busy = false;
      notifyListeners();
    }
  }

  bool get canGoNext => !period.isCurrentAt(DateTime.now());

  Future<void> setPeriodKind(AnalysisPeriodKind kind) async {
    if (busy || kind == period.kind) return;
    period = period.withKind(kind);
    data = null;
    notifyListeners();
    await refresh();
  }

  Future<void> previousPeriod() async {
    if (busy) return;
    period = period.previous();
    data = null;
    notifyListeners();
    await refresh();
  }

  Future<void> nextPeriod() async {
    if (busy || !canGoNext) return;
    final candidate = period.next();
    final current = AnalysisPeriod.day(DateTime.now()).withKind(period.kind);
    period = candidate.startMs > current.startMs ? current : candidate;
    data = null;
    notifyListeners();
    await refresh();
  }

  Future<void> selectDate(DateTime date) async {
    if (busy) return;
    period = period.withAnchor(date);
    data = null;
    notifyListeners();
    await refresh();
  }

  Future<void> setHormonePreference(
    HormoneTrackingPreference preference,
  ) async {
    hormonePreference = preference;
    notifyListeners();
    try {
      await AndroidStorageBridge.setHormonePreference(preference);
    } on PlatformException catch (error) {
      errorMessage = _friendlyPlatformError(error);
      notifyListeners();
    }
  }

  String _friendlyPlatformError(PlatformException error) {
    switch (error.code) {
      case 'NO_DIRECTORY':
        return 'Le dossier AAPS doit être sélectionné.';
      case 'PERMISSION_LOST':
        directory = null;
        return 'Android a perdu l’autorisation du dossier. Sélectionnez Documents/AAPS à nouveau.';
      case 'NOT_A_DIRECTORY':
        return 'Le dossier choisi n’est plus disponible.';
      default:
        return error.message ?? 'Erreur Android (${error.code}).';
    }
  }
}
