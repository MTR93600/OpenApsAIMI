import 'package:flutter/services.dart';

import 'models.dart';

class AndroidStorageBridge {
  AndroidStorageBridge._();

  static const MethodChannel _channel = MethodChannel(
    'app.aaps.aimiviewer/storage',
  );

  static Future<DirectoryGrant?> currentDirectory() async {
    final result = await _channel.invokeMapMethod<Object?, Object?>(
      'getDirectory',
    );
    return result == null ? null : DirectoryGrant.fromMap(result);
  }

  static Future<DirectoryGrant?> chooseDirectory() async {
    final result = await _channel.invokeMapMethod<Object?, Object?>(
      'chooseDirectory',
    );
    return result == null ? null : DirectoryGrant.fromMap(result);
  }

  static Future<List<StagedFile>> stageFiles() async {
    final raw =
        await _channel.invokeListMethod<Object?>('stageFiles') ?? <Object?>[];
    return raw
        .whereType<Map>()
        .map((item) => StagedFile.fromMap(Map<Object?, Object?>.from(item)))
        .where((file) => file.name.isNotEmpty && file.path.isNotEmpty)
        .toList();
  }
}
