import 'dart:async';
import 'dart:convert';
import 'dart:io';

const String decisions24hFile = 'AIMI_Decisions_Last24h.jsonl';
const String decisionsFile = 'AIMI_Decisions.jsonl';
const String pkpdFile = 'oapsaimi_pkpd_records.csv';
const String hormonitorEventsFile = 'AIMI_HORMONITOR_event_stream_v1.jsonl';
const String hormonitorDailyFile = 'AIMI_HORMONITOR_daily_outcomes_v1.jsonl';
const String hormonitorQaFile = 'AIMI_HORMONITOR_dataset_qa_v1.jsonl';
const String hormonitorShadowFile =
    'AIMI_HORMONITOR_shadow_contributions_v1.jsonl';
const String hormonitorBlackboxFile = 'AIMI_HORMONITOR_loop_blackbox_v1.jsonl';
const String hormonitorStateFile = 'AIMI_HORMONITOR_daily_state_v1.json';

const List<String> expectedExportNames = <String>[
  decisions24hFile,
  decisionsFile,
  pkpdFile,
  hormonitorEventsFile,
  hormonitorDailyFile,
  hormonitorQaFile,
  hormonitorShadowFile,
  hormonitorBlackboxFile,
  hormonitorStateFile,
];

/// Top-level callback suitable for Flutter's [compute].
Future<Map<String, Object?>> parseExportsInBackground(
  Map<String, Object?> input,
) async {
  final nowMs = _asInt(input['nowMs']) ?? DateTime.now().millisecondsSinceEpoch;
  final rawFiles = input['files'];
  final files =
      rawFiles is List
          ? rawFiles
              .whereType<Map>()
              .map((item) => Map<String, Object?>.from(item))
              .toList()
          : <Map<String, Object?>>[];
  return _ExportParser(nowMs: nowMs, stagedFiles: files).parse();
}

class _ExportParser {
  _ExportParser({required this.nowMs, required this.stagedFiles})
    : windowStartMs = nowMs - const Duration(hours: 24).inMilliseconds {
    for (final name in expectedExportNames) {
      sources[name] = _SourceAccumulator(name: name);
    }
    for (final metadata in stagedFiles) {
      final name = metadata['name']?.toString() ?? '';
      if (name.isEmpty) continue;
      filesByName[name] = metadata['path']?.toString() ?? '';
      sources.putIfAbsent(name, () => _SourceAccumulator(name: name));
      sources[name]!
        ..present = true
        ..sourceSize = _asInt(metadata['sourceSize']) ?? 0
        ..truncated = metadata['truncated'] == true;
    }
  }

  final int nowMs;
  final int windowStartMs;
  final List<Map<String, Object?>> stagedFiles;
  final Map<String, String> filesByName = <String, String>{};
  final Map<String, _SourceAccumulator> sources =
      <String, _SourceAccumulator>{};

  final List<Map<String, Object?>> decisionGlucose = <Map<String, Object?>>[];
  final List<Map<String, Object?>> pkpdGlucose = <Map<String, Object?>>[];
  final List<Map<String, Object?>> hormonitorGlucose = <Map<String, Object?>>[];
  final List<Map<String, Object?>> decisionTimeline = <Map<String, Object?>>[];
  final List<Map<String, Object?>> hormonitorTimeline =
      <Map<String, Object?>>[];

  final Map<String, int> decisionTypes = <String, int>{};
  final Map<String, int> decisionModes = <String, int>{};
  final Map<String, int> hormonitorModes = <String, int>{};
  final Map<String, int> physioStates = <String, int>{};
  final Map<String, int> safetyGates = <String, int>{};
  final Map<String, int> cyclePhases = <String, int>{};

  int decisionCount = 0;
  int hormonitorEventCount = 0;
  int patientStoryCount = 0;
  double decisionSmbTotal = 0;
  double pkpdSmbTotal = 0;
  double? dailyTddU;
  int dailyTddTimestamp = 0;
  final List<double> fusedIsfValues = <double>[];
  final List<double> profileIsfValues = <double>[];
  _TimedValue? latestIob;
  _TimedValue? latestCob;

  Future<Map<String, Object?>> parse() async {
    final chosenDecisions =
        filesByName.containsKey(decisions24hFile)
            ? decisions24hFile
            : decisionsFile;
    if (filesByName.containsKey(chosenDecisions)) {
      await _parseDecisions(chosenDecisions);
    }
    if (filesByName.containsKey(pkpdFile)) await _parsePkpd();
    if (filesByName.containsKey(hormonitorEventsFile)) {
      await _parseHormonitorEvents();
    }
    if (filesByName.containsKey(hormonitorDailyFile)) {
      await _parseHormonitorDaily();
    }

    for (final name in <String>[
      hormonitorQaFile,
      hormonitorShadowFile,
      hormonitorBlackboxFile,
    ]) {
      if (filesByName.containsKey(name)) await _countJsonl(name);
    }
    if (filesByName.containsKey(hormonitorStateFile)) {
      await _countJsonDocument(hormonitorStateFile);
    }

    final selectedGlucose =
        decisionGlucose.isNotEmpty
            ? decisionGlucose
            : pkpdGlucose.isNotEmpty
            ? pkpdGlucose
            : hormonitorGlucose;
    selectedGlucose.sort(
      (a, b) => (_asInt(a['timestampMs']) ?? 0).compareTo(
        _asInt(b['timestampMs']) ?? 0,
      ),
    );
    final validBg =
        selectedGlucose
            .map((point) => _asDouble(point['valueMgdl']))
            .whereType<double>()
            .where((value) => value >= 20 && value <= 600)
            .toList();
    final low = validBg.where((value) => value < 70).length;
    final high = validBg.where((value) => value > 180).length;
    final inRange = validBg.length - low - high;

    final timeline =
        decisionTimeline.isNotEmpty ? decisionTimeline : hormonitorTimeline;
    timeline.sort(
      (a, b) => (_asInt(b['timestampMs']) ?? 0).compareTo(
        _asInt(a['timestampMs']) ?? 0,
      ),
    );

    return <String, Object?>{
      'generatedAtMs': DateTime.now().millisecondsSinceEpoch,
      'windowStartMs': windowStartMs,
      'windowEndMs': nowMs,
      'decisionCount': decisionCount,
      'hormonitorEventCount': hormonitorEventCount,
      'latestBgMgdl':
          selectedGlucose.isEmpty ? null : selectedGlucose.last['valueMgdl'],
      'meanBgMgdl': _mean(validBg),
      'tirPct': _pct(inRange, validBg.length),
      'lowPct': _pct(low, validBg.length),
      'highPct': _pct(high, validBg.length),
      'totalSmbU': decisionCount > 0 ? decisionSmbTotal : pkpdSmbTotal,
      'latestIobU': latestIob?.value,
      'latestCobG': latestCob?.value,
      'meanFusedIsf': _mean(fusedIsfValues),
      'meanProfileIsf': _mean(profileIsfValues),
      'dailyTddU': dailyTddU,
      'patientStoryCoverage': _pct(patientStoryCount, hormonitorEventCount),
      'glucose': _downsample(selectedGlucose, 320),
      'timeline': timeline.take(80).toList(),
      'decisionTypes': _sortedCounts(decisionTypes),
      'patientModes': _sortedCounts(
        hormonitorModes.isNotEmpty ? hormonitorModes : decisionModes,
      ),
      'physioStates': _sortedCounts(physioStates),
      'safetyGates': _sortedCounts(safetyGates),
      'cyclePhases': _sortedCounts(cyclePhases),
      'sources': sources.values.map((source) => source.toMap()).toList(),
    };
  }

  Future<void> _parseDecisions(String name) async {
    final source = sources[name]!;
    await for (final raw in _lines(filesByName[name]!)) {
      final line = raw.trim();
      if (line.isEmpty) continue;
      final root = _decodeObject(line);
      if (root == null) {
        source.malformedLines++;
        continue;
      }
      final timestamp = _timestamp(root['timestamp']);
      source.observeTimestamp(timestamp, windowStartMs, nowMs);
      if (!_inWindow(timestamp)) continue;

      decisionCount++;
      final baseline = _map(root['baseline_state']);
      final adjustments = _map(root['adjustments']);
      final outcome = _map(root['outcome']);
      final bg = _asDouble(baseline?['current_bg_mgdl']);
      final iob = _asDouble(baseline?['iob_u']);
      final cob = _asDouble(baseline?['cob_g']);
      final smb =
          _asDouble(outcome?['amount']) ?? _asDouble(outcome?['dosage_u']) ?? 0;
      final decision =
          _text(outcome?['decision']) ??
          _text(outcome?['clinical_decision']) ??
          'Décision AIMI';
      final patientMode =
          _text(_map(adjustments?['patient_mode'])?['mode']) ??
          _text(root['patient_mode']);
      final safetyGate = _text(
        _map(adjustments?['safety_risk'])?['safety_gate'],
      );

      if (bg != null && bg >= 20 && bg <= 600) {
        decisionGlucose.add(<String, Object?>{
          'timestampMs': timestamp!,
          'valueMgdl': bg,
        });
      }
      decisionSmbTotal += smb > 0 ? smb : 0;
      _bump(decisionTypes, decision);
      if (patientMode != null) _bump(decisionModes, patientMode);
      if (safetyGate != null) _bump(safetyGates, safetyGate);
      _observeLatest(timestamp!, iob, isIob: true, sourcePriority: 3);
      _observeLatest(timestamp, cob, isIob: false, sourcePriority: 3);
      decisionTimeline.add(<String, Object?>{
        'timestampMs': timestamp,
        'bgMgdl': bg,
        'iobU': iob,
        'cobG': cob,
        'smbU': smb,
        'decision': decision,
        'patientMode': patientMode,
        'safetyGate': safetyGate,
      });
    }
  }

  Future<void> _parsePkpd() async {
    final source = sources[pkpdFile]!;
    await for (final raw in _lines(filesByName[pkpdFile]!)) {
      final line = raw.trim();
      if (line.isEmpty) continue;
      final columns = _splitCsv(line);
      if (columns.length < 15) {
        source.malformedLines++;
        continue;
      }
      // Current schema: dateStr, epochMin, bg, delta5, iob, carbsActive,
      // windowMin, diaH, peakMin, fusedIsf, tddIsf, profileIsf, tailFrac,
      // smbProposed, smbFinal, followed by optional audit columns.
      final epochMin = int.tryParse(columns[1].trim());
      final timestamp = epochMin == null ? null : epochMin * 60000;
      source.observeTimestamp(timestamp, windowStartMs, nowMs);
      if (!_inWindow(timestamp)) continue;
      final bg = double.tryParse(columns[2].trim());
      final iob = double.tryParse(columns[4].trim());
      final fusedIsf = double.tryParse(columns[9].trim());
      final profileIsf = double.tryParse(columns[11].trim());
      final smbFinal = double.tryParse(columns[14].trim()) ?? 0;
      if (bg != null && bg >= 20 && bg <= 600) {
        pkpdGlucose.add(<String, Object?>{
          'timestampMs': timestamp!,
          'valueMgdl': bg,
        });
      }
      if (fusedIsf != null && fusedIsf.isFinite) fusedIsfValues.add(fusedIsf);
      if (profileIsf != null && profileIsf.isFinite) {
        profileIsfValues.add(profileIsf);
      }
      if (smbFinal > 0) pkpdSmbTotal += smbFinal;
      _observeLatest(timestamp!, iob, isIob: true, sourcePriority: 2);
    }
  }

  Future<void> _parseHormonitorEvents() async {
    final source = sources[hormonitorEventsFile]!;
    await for (final raw in _lines(filesByName[hormonitorEventsFile]!)) {
      final line = raw.trim();
      if (line.isEmpty) continue;
      final root = _decodeObject(line);
      if (root == null) {
        source.malformedLines++;
        continue;
      }
      final timestamp = _timestamp(root['timestamp']);
      source.observeTimestamp(timestamp, windowStartMs, nowMs);
      if (!_inWindow(timestamp)) continue;

      hormonitorEventCount++;
      final story = _map(root['patient_story']);
      final mode = _text(story?['patient_mode']) ?? _text(root['patient_mode']);
      final physio = _text(root['physio_state']);
      final safety = _text(root['safety_gate']);
      final cycle = _text(root['cycle_phase']);
      final decision =
          _text(root['final_loop_decision_type']) ?? 'Événement Hormonitor';
      final bg = _asDouble(root['current_bg_mgdl']);
      final iob = _asDouble(root['iob_u']);
      final cob = _asDouble(root['cob_g']);

      if (story != null && story.isNotEmpty) patientStoryCount++;
      if (mode != null) _bump(hormonitorModes, mode);
      if (physio != null) _bump(physioStates, physio);
      if (safety != null) _bump(safetyGates, safety);
      if (cycle != null) _bump(cyclePhases, cycle);
      if (decisionCount == 0) _bump(decisionTypes, decision);
      if (bg != null && bg >= 20 && bg <= 600) {
        hormonitorGlucose.add(<String, Object?>{
          'timestampMs': timestamp!,
          'valueMgdl': bg,
        });
      }
      _observeLatest(timestamp!, iob, isIob: true, sourcePriority: 1);
      _observeLatest(timestamp, cob, isIob: false, sourcePriority: 1);
      hormonitorTimeline.add(<String, Object?>{
        'timestampMs': timestamp,
        'bgMgdl': bg,
        'iobU': iob,
        'cobG': cob,
        'smbU': 0.0,
        'decision': decision,
        'patientMode': mode,
        'safetyGate': safety,
      });
    }
  }

  Future<void> _parseHormonitorDaily() async {
    final source = sources[hormonitorDailyFile]!;
    await for (final raw in _lines(filesByName[hormonitorDailyFile]!)) {
      final line = raw.trim();
      if (line.isEmpty) continue;
      final root = _decodeObject(line);
      if (root == null) {
        source.malformedLines++;
        continue;
      }
      final generatedAt =
          _timestamp(root['generated_at']) ?? _dayTimestamp(root['day_local']);
      source.observeTimestamp(generatedAt, windowStartMs, nowMs);
      if (!_inWindow(generatedAt)) continue;
      final tdd = _asDouble(root['tdd_24h_total_u']);
      if (tdd != null && generatedAt! >= dailyTddTimestamp) {
        dailyTddU = tdd;
        dailyTddTimestamp = generatedAt;
      }
    }
  }

  Future<void> _countJsonl(String name) async {
    final source = sources[name]!;
    await for (final raw in _lines(filesByName[name]!)) {
      final line = raw.trim();
      if (line.isEmpty) continue;
      final root = _decodeObject(line);
      if (root == null) {
        source.malformedLines++;
        continue;
      }
      final timestamp =
          _timestamp(root['timestamp']) ?? _timestamp(root['generated_at']);
      source.observeTimestamp(
        timestamp,
        windowStartMs,
        nowMs,
        countWithoutTimestamp: true,
      );
    }
  }

  Future<void> _countJsonDocument(String name) async {
    final source = sources[name]!;
    try {
      final decoded = jsonDecode(await File(filesByName[name]!).readAsString());
      if (decoded is! Map) {
        source.malformedLines++;
        return;
      }
      final root = Map<String, Object?>.from(decoded);
      final timestamp =
          _timestamp(root['timestamp']) ?? _timestamp(root['generated_at']);
      source.observeTimestamp(
        timestamp,
        windowStartMs,
        nowMs,
        countWithoutTimestamp: true,
      );
    } on FormatException {
      source.malformedLines++;
    } on FileSystemException {
      source.malformedLines++;
    }
  }

  bool _inWindow(int? timestamp) =>
      timestamp != null &&
      timestamp >= windowStartMs &&
      timestamp <= nowMs + 300000;

  void _observeLatest(
    int timestamp,
    double? value, {
    required bool isIob,
    required int sourcePriority,
  }) {
    if (value == null || !value.isFinite) return;
    final current = isIob ? latestIob : latestCob;
    if (current == null ||
        timestamp > current.timestampMs ||
        (timestamp == current.timestampMs &&
            sourcePriority > current.sourcePriority)) {
      final next = _TimedValue(
        timestampMs: timestamp,
        value: value,
        sourcePriority: sourcePriority,
      );
      if (isIob) {
        latestIob = next;
      } else {
        latestCob = next;
      }
    }
  }
}

class _SourceAccumulator {
  _SourceAccumulator({required this.name});

  final String name;
  bool present = false;
  int recordsInWindow = 0;
  int malformedLines = 0;
  int? latestTimestampMs;
  int sourceSize = 0;
  bool truncated = false;

  void observeTimestamp(
    int? timestamp,
    int startMs,
    int endMs, {
    bool countWithoutTimestamp = false,
  }) {
    if (timestamp != null) {
      if (latestTimestampMs == null || timestamp > latestTimestampMs!) {
        latestTimestampMs = timestamp;
      }
      if (timestamp >= startMs && timestamp <= endMs + 300000) {
        recordsInWindow++;
      }
    } else if (countWithoutTimestamp) {
      recordsInWindow++;
    }
  }

  Map<String, Object?> toMap() => <String, Object?>{
    'name': name,
    'present': present,
    'recordsInWindow': recordsInWindow,
    'malformedLines': malformedLines,
    'latestTimestampMs': latestTimestampMs,
    'sourceSize': sourceSize,
    'truncated': truncated,
  };
}

class _TimedValue {
  const _TimedValue({
    required this.timestampMs,
    required this.value,
    required this.sourcePriority,
  });
  final int timestampMs;
  final double value;
  final int sourcePriority;
}

Stream<String> _lines(String path) => File(path)
    .openRead()
    .transform(const Utf8Decoder(allowMalformed: true))
    .transform(const LineSplitter());

Map<String, Object?>? _decodeObject(String text) {
  try {
    final decoded = jsonDecode(text);
    return decoded is Map ? Map<String, Object?>.from(decoded) : null;
  } on FormatException {
    return null;
  }
}

Map<String, Object?>? _map(Object? value) =>
    value is Map ? Map<String, Object?>.from(value) : null;

String? _text(Object? value) {
  final text = value?.toString().trim();
  return text == null || text.isEmpty || text.toLowerCase() == 'null'
      ? null
      : text;
}

int? _timestamp(Object? value) {
  if (value == null) return null;
  if (value is String && !RegExp(r'^\d+(\.\d+)?$').hasMatch(value.trim())) {
    return DateTime.tryParse(value)?.millisecondsSinceEpoch;
  }
  final numeric = _asDouble(value);
  if (numeric == null || !numeric.isFinite || numeric <= 0) return null;
  return numeric < 100000000000 ? (numeric * 1000).round() : numeric.round();
}

int? _dayTimestamp(Object? value) {
  final day = _text(value);
  return day == null
      ? null
      : DateTime.tryParse('${day}T12:00:00')?.millisecondsSinceEpoch;
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

void _bump(Map<String, int> counts, String label) =>
    counts[label] = (counts[label] ?? 0) + 1;

double? _mean(List<double> values) =>
    values.isEmpty ? null : values.reduce((a, b) => a + b) / values.length;

double? _pct(int part, int total) => total <= 0 ? null : part * 100 / total;

Map<String, int> _sortedCounts(Map<String, int> counts) {
  final entries =
      counts.entries.toList()..sort((a, b) => b.value.compareTo(a.value));
  return Map<String, int>.fromEntries(entries);
}

List<Map<String, Object?>> _downsample(
  List<Map<String, Object?>> values,
  int maxPoints,
) {
  if (values.length <= maxPoints) return values;
  final step = values.length / maxPoints;
  return List<Map<String, Object?>>.generate(maxPoints, (index) {
    final sourceIndex =
        (index * step).floor().clamp(0, values.length - 1).toInt();
    return values[sourceIndex];
  });
}

List<String> _splitCsv(String line) {
  final result = <String>[];
  final current = StringBuffer();
  var quoted = false;
  for (var index = 0; index < line.length; index++) {
    final char = line[index];
    if (char == '"') {
      if (quoted && index + 1 < line.length && line[index + 1] == '"') {
        current.write('"');
        index++;
      } else {
        quoted = !quoted;
      }
    } else if (char == ',' && !quoted) {
      result.add(current.toString());
      current.clear();
    } else {
      current.write(char);
    }
  }
  result.add(current.toString());
  return result;
}
