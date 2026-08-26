class DirectoryGrant {
  const DirectoryGrant({required this.uri, required this.name});

  final String uri;
  final String name;

  factory DirectoryGrant.fromMap(Map<Object?, Object?> map) => DirectoryGrant(
    uri: map['uri']?.toString() ?? '',
    name: map['name']?.toString() ?? 'Documents/AAPS',
  );
}

class StagedFile {
  const StagedFile({
    required this.name,
    required this.path,
    required this.sourceSize,
    required this.stagedSize,
    required this.lastModifiedMs,
    required this.truncated,
  });

  final String name;
  final String path;
  final int sourceSize;
  final int stagedSize;
  final int lastModifiedMs;
  final bool truncated;

  factory StagedFile.fromMap(Map<Object?, Object?> map) => StagedFile(
    name: map['name']?.toString() ?? '',
    path: map['path']?.toString() ?? '',
    sourceSize: _asInt(map['sourceSize']) ?? 0,
    stagedSize: _asInt(map['stagedSize']) ?? 0,
    lastModifiedMs: _asInt(map['lastModifiedMs']) ?? 0,
    truncated: map['truncated'] == true,
  );

  Map<String, Object> toParserMap() => <String, Object>{
    'name': name,
    'path': path,
    'sourceSize': sourceSize,
    'stagedSize': stagedSize,
    'lastModifiedMs': lastModifiedMs,
    'truncated': truncated,
  };
}

class GlucosePoint {
  const GlucosePoint({required this.timestampMs, required this.valueMgdl});

  final int timestampMs;
  final double valueMgdl;

  factory GlucosePoint.fromMap(Map<Object?, Object?> map) => GlucosePoint(
    timestampMs: _asInt(map['timestampMs']) ?? 0,
    valueMgdl: _asDouble(map['valueMgdl']) ?? 0,
  );
}

class TimelineEntry {
  const TimelineEntry({
    required this.timestampMs,
    required this.bgMgdl,
    required this.iobU,
    required this.cobG,
    required this.smbU,
    required this.decision,
    required this.patientMode,
    required this.safetyGate,
  });

  final int timestampMs;
  final double? bgMgdl;
  final double? iobU;
  final double? cobG;
  final double smbU;
  final String decision;
  final String? patientMode;
  final String? safetyGate;

  factory TimelineEntry.fromMap(Map<Object?, Object?> map) => TimelineEntry(
    timestampMs: _asInt(map['timestampMs']) ?? 0,
    bgMgdl: _asDouble(map['bgMgdl']),
    iobU: _asDouble(map['iobU']),
    cobG: _asDouble(map['cobG']),
    smbU: _asDouble(map['smbU']) ?? 0,
    decision: map['decision']?.toString() ?? 'Décision AIMI',
    patientMode: _asNullableString(map['patientMode']),
    safetyGate: _asNullableString(map['safetyGate']),
  );
}

class SourceStatus {
  const SourceStatus({
    required this.name,
    required this.present,
    required this.recordsInWindow,
    required this.malformedLines,
    required this.latestTimestampMs,
    required this.sourceSize,
    required this.truncated,
  });

  final String name;
  final bool present;
  final int recordsInWindow;
  final int malformedLines;
  final int? latestTimestampMs;
  final int sourceSize;
  final bool truncated;

  factory SourceStatus.fromMap(Map<Object?, Object?> map) => SourceStatus(
    name: map['name']?.toString() ?? '',
    present: map['present'] == true,
    recordsInWindow: _asInt(map['recordsInWindow']) ?? 0,
    malformedLines: _asInt(map['malformedLines']) ?? 0,
    latestTimestampMs: _asInt(map['latestTimestampMs']),
    sourceSize: _asInt(map['sourceSize']) ?? 0,
    truncated: map['truncated'] == true,
  );
}

class DashboardData {
  const DashboardData({
    required this.generatedAtMs,
    required this.windowStartMs,
    required this.windowEndMs,
    required this.decisionCount,
    required this.hormonitorEventCount,
    required this.latestBgMgdl,
    required this.meanBgMgdl,
    required this.tirPct,
    required this.lowPct,
    required this.highPct,
    required this.totalSmbU,
    required this.latestIobU,
    required this.latestCobG,
    required this.meanFusedIsf,
    required this.meanProfileIsf,
    required this.dailyTddU,
    required this.patientStoryCoverage,
    required this.glucose,
    required this.timeline,
    required this.decisionTypes,
    required this.patientModes,
    required this.physioStates,
    required this.safetyGates,
    required this.cyclePhases,
    required this.sources,
  });

  final int generatedAtMs;
  final int windowStartMs;
  final int windowEndMs;
  final int decisionCount;
  final int hormonitorEventCount;
  final double? latestBgMgdl;
  final double? meanBgMgdl;
  final double? tirPct;
  final double? lowPct;
  final double? highPct;
  final double totalSmbU;
  final double? latestIobU;
  final double? latestCobG;
  final double? meanFusedIsf;
  final double? meanProfileIsf;
  final double? dailyTddU;
  final double? patientStoryCoverage;
  final List<GlucosePoint> glucose;
  final List<TimelineEntry> timeline;
  final Map<String, int> decisionTypes;
  final Map<String, int> patientModes;
  final Map<String, int> physioStates;
  final Map<String, int> safetyGates;
  final Map<String, int> cyclePhases;
  final List<SourceStatus> sources;

  bool get hasData =>
      decisionCount > 0 || hormonitorEventCount > 0 || glucose.isNotEmpty;

  factory DashboardData.fromMap(Map<Object?, Object?> map) => DashboardData(
    generatedAtMs: _asInt(map['generatedAtMs']) ?? 0,
    windowStartMs: _asInt(map['windowStartMs']) ?? 0,
    windowEndMs: _asInt(map['windowEndMs']) ?? 0,
    decisionCount: _asInt(map['decisionCount']) ?? 0,
    hormonitorEventCount: _asInt(map['hormonitorEventCount']) ?? 0,
    latestBgMgdl: _asDouble(map['latestBgMgdl']),
    meanBgMgdl: _asDouble(map['meanBgMgdl']),
    tirPct: _asDouble(map['tirPct']),
    lowPct: _asDouble(map['lowPct']),
    highPct: _asDouble(map['highPct']),
    totalSmbU: _asDouble(map['totalSmbU']) ?? 0,
    latestIobU: _asDouble(map['latestIobU']),
    latestCobG: _asDouble(map['latestCobG']),
    meanFusedIsf: _asDouble(map['meanFusedIsf']),
    meanProfileIsf: _asDouble(map['meanProfileIsf']),
    dailyTddU: _asDouble(map['dailyTddU']),
    patientStoryCoverage: _asDouble(map['patientStoryCoverage']),
    glucose: _mapList(map['glucose'], GlucosePoint.fromMap),
    timeline: _mapList(map['timeline'], TimelineEntry.fromMap),
    decisionTypes: _asIntMap(map['decisionTypes']),
    patientModes: _asIntMap(map['patientModes']),
    physioStates: _asIntMap(map['physioStates']),
    safetyGates: _asIntMap(map['safetyGates']),
    cyclePhases: _asIntMap(map['cyclePhases']),
    sources: _mapList(map['sources'], SourceStatus.fromMap),
  );
}

List<T> _mapList<T>(Object? value, T Function(Map<Object?, Object?>) convert) {
  if (value is! List) return <T>[];
  return value
      .whereType<Map>()
      .map((item) => convert(Map<Object?, Object?>.from(item)))
      .toList();
}

Map<String, int> _asIntMap(Object? value) {
  if (value is! Map) return <String, int>{};
  return value.map((key, item) => MapEntry(key.toString(), _asInt(item) ?? 0));
}

String? _asNullableString(Object? value) {
  final text = value?.toString().trim();
  return text == null || text.isEmpty || text == 'null' ? null : text;
}

int? _asInt(Object? value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value?.toString() ?? '');
}

double? _asDouble(Object? value) {
  if (value is num) return value.toDouble();
  return double.tryParse(value?.toString() ?? '');
}
