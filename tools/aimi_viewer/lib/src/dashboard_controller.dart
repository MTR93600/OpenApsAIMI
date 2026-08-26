import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'export_parser.dart';
import 'models.dart';
import 'storage_bridge.dart';

class DashboardController extends ChangeNotifier {
  DirectoryGrant? directory;
  DashboardData? data;
  bool busy = false;
  String? errorMessage;

  Future<void> initialize() async {
    try {
      directory = await AndroidStorageBridge.currentDirectory();
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
      final staged = await AndroidStorageBridge.stageFiles();
      final parsed = await compute(parseExportsInBackground, <String, Object?>{
        'nowMs': DateTime.now().millisecondsSinceEpoch,
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
