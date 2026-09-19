import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'label_catalog.dart';

/// Logical decision source. Android maps the Last24h fallback to this name so
/// the UI never reports a second, falsely missing decision export.
const String decisionsFile = 'AIMI_Decisions.jsonl';
const String decisions24hSourceFile = 'AIMI_Decisions_Last24h.jsonl';
const String pkpdFile = 'oapsaimi_pkpd_records.csv';
const String hormonitorEventsFile = 'AIMI_HORMONITOR_event_stream_v1.jsonl';
const String hormonitorDailyFile = 'AIMI_HORMONITOR_daily_outcomes_v1.jsonl';
const String hormonitorQaFile = 'AIMI_HORMONITOR_dataset_qa_v1.jsonl';
const String hormonitorShadowFile =
    'AIMI_HORMONITOR_shadow_contributions_v1.jsonl';
const String hormonitorBlackboxFile = 'AIMI_HORMONITOR_loop_blackbox_v1.jsonl';

/// Most recent decision-journal entries kept in memory for the Journal tab.
const int timelineCapacity = 300;

const List<String> expectedExportNames = <String>[
  decisionsFile,
  pkpdFile,
  hormonitorEventsFile,
  hormonitorDailyFile,
  hormonitorQaFile,
  hormonitorShadowFile,
  hormonitorBlackboxFile,
];

/// Top-level callback suitable for Flutter's [compute].
Future<Map<String, Object?>> parseExportsInBackground(
  Map<String, Object?> input,
) async {
  final fallbackEnd = DateTime.now().millisecondsSinceEpoch;
  final windowEndMs = _asInt(input['windowEndMs']) ?? fallbackEnd;
  final windowStartMs =
      _asInt(input['windowStartMs']) ??
      windowEndMs - const Duration(hours: 24).inMilliseconds;
  final rawFiles = input['files'];
  final files =
      rawFiles is List
          ? rawFiles
              .whereType<Map>()
              .map((item) => Map<String, Object?>.from(item))
              .toList()
          : <Map<String, Object?>>[];
  return _ExportParser(
    windowStartMs: windowStartMs,
    windowEndMs: windowEndMs,
    stagedFiles: files,
  ).parse();
}

class _ExportParser {
  _ExportParser({
    required this.windowStartMs,
    required this.windowEndMs,
    required List<Map<String, Object?>> stagedFiles,
  }) {
    for (final name in expectedExportNames) {
      sources[name] = _SourceAccumulator(name: name);
    }
    for (final metadata in stagedFiles) {
      final rawName = metadata['name']?.toString() ?? '';
      final name = rawName == decisions24hSourceFile ? decisionsFile : rawName;
      if (name.isEmpty) continue;
      final path = metadata['path']?.toString() ?? '';
      if (path.isNotEmpty) filesByName[name] = path;
      final source = sources.putIfAbsent(
        name,
        () => _SourceAccumulator(name: name),
      );
      source
        ..present = true
        ..sourceName = metadata['sourceName']?.toString() ?? rawName
        ..sourceSize = _asInt(metadata['sourceSize']) ?? 0
        ..truncated = metadata['truncated'] == true
        ..coverageStartMs = _asInt(metadata['coverageStartMs'])
        ..coverageEndMs = _asInt(metadata['coverageEndMs'])
        ..coverageComplete = metadata['coverageComplete'] == true
        ..extractionMode = metadata['extractionMode']?.toString() ?? 'stream';
    }
  }

  final int windowStartMs;
  final int windowEndMs;
  final Map<String, String> filesByName = <String, String>{};
  final Map<String, _SourceAccumulator> sources =
      <String, _SourceAccumulator>{};

  final _PointBuffer decisionGlucose = _PointBuffer(320);
  final _PointBuffer pkpdGlucose = _PointBuffer(320);
  final _PointBuffer hormonitorGlucose = _PointBuffer(320);
  final _PointBuffer decisionIob = _PointBuffer(200, valueKey: 'value');
  final _PointBuffer decisionCob = _PointBuffer(200, valueKey: 'value');
  final _PointBuffer decisionSmb = _PointBuffer(200, valueKey: 'value');
  final _PointBuffer pkpdIob = _PointBuffer(200, valueKey: 'value');
  final _PointBuffer pkpdSmb = _PointBuffer(200, valueKey: 'value');
  final _PointBuffer hormonitorIob = _PointBuffer(200, valueKey: 'value');
  final _PointBuffer hormonitorCob = _PointBuffer(200, valueKey: 'value');
  final _RecentTimeline decisionTimeline = _RecentTimeline(timelineCapacity);
  final _RecentTimeline hormonitorTimeline = _RecentTimeline(timelineCapacity);
  final _GlucoseStats decisionGlucoseStats = _GlucoseStats();
  final _GlucoseStats pkpdGlucoseStats = _GlucoseStats();
  final _GlucoseStats hormonitorGlucoseStats = _GlucoseStats();

  final Map<String, int> decisionTypes = <String, int>{};
  final Map<String, int> decisionModes = <String, int>{};
  final Map<String, int> hormonitorModes = <String, int>{};
  final Map<String, int> physioStates = <String, int>{};
  final Map<String, int> decisionSafetyGates = <String, int>{};
  final Map<String, int> hormonitorSafetyGates = <String, int>{};
  final Map<String, int> cyclePhases = <String, int>{};
  final Map<String, _DailyTdd> tddByDay = <String, _DailyTdd>{};

  int decisionCount = 0;
  int auditorFollowupCount = 0;
  int hormonitorEventCount = 0;
  int patientStoryCount = 0;
  double decisionSmbTotal = 0;
  double pkpdSmbTotal = 0;
  final _RunningMean fusedIsf = _RunningMean();
  final _RunningMean profileIsf = _RunningMean();
  _TimedValue? latestIob;
  _TimedValue? latestCob;

  Future<Map<String, Object?>> parse() async {
    if (filesByName.containsKey(decisionsFile)) await _parseDecisions();
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

    final selectedPoints =
        decisionGlucose.isNotEmpty
            ? decisionGlucose
            : pkpdGlucose.isNotEmpty
            ? pkpdGlucose
            : hormonitorGlucose;
    final selectedStats =
        decisionGlucoseStats.count > 0
            ? decisionGlucoseStats
            : pkpdGlucoseStats.count > 0
            ? pkpdGlucoseStats
            : hormonitorGlucoseStats;
    final selectedTimeline =
        decisionTimeline.isNotEmpty ? decisionTimeline : hormonitorTimeline;
    final selectedIob =
        decisionIob.isNotEmpty
            ? decisionIob
            : pkpdIob.isNotEmpty
            ? pkpdIob
            : hormonitorIob;
    final selectedCob = decisionCob.isNotEmpty ? decisionCob : hormonitorCob;
    final selectedSmb = decisionSmb.isNotEmpty ? decisionSmb : pkpdSmb;
    final selectedSafety =
        hormonitorSafetyGates.isNotEmpty
            ? hormonitorSafetyGates
            : decisionSafetyGates;
    final tddValues = tddByDay.values.map((item) => item.value).toList();

    return <String, Object?>{
      'generatedAtMs': DateTime.now().millisecondsSinceEpoch,
      'windowStartMs': windowStartMs,
      'windowEndMs': windowEndMs,
      'decisionCount': decisionCount,
      'auditorFollowupCount': auditorFollowupCount,
      'hormonitorEventCount': hormonitorEventCount,
      'latestBgMgdl': selectedStats.latestValue,
      'meanBgMgdl': selectedStats.mean,
      'tirPct': selectedStats.inRangePct,
      'lowPct': selectedStats.lowPct,
      'highPct': selectedStats.highPct,
      'totalSmbU': decisionCount > 0 ? decisionSmbTotal : pkpdSmbTotal,
      'latestIobU': latestIob?.value,
      'latestCobG': latestCob?.value,
      'meanFusedIsf': fusedIsf.mean,
      'meanProfileIsf': profileIsf.mean,
      'dailyTddU': _mean(tddValues),
      'dailyTddDays': tddValues.length,
      'patientStoryCoverage': _pct(patientStoryCount, hormonitorEventCount),
      'glucose': selectedPoints.sorted,
      'iobSeries': selectedIob.sorted,
      'cobSeries': selectedCob.sorted,
      'smbSeries': selectedSmb.sorted,
      'timeline': selectedTimeline.sortedNewestFirst,
      'decisionTypes': _sortedCounts(decisionTypes),
      'patientModes': _sortedCounts(
        hormonitorModes.isNotEmpty ? hormonitorModes : decisionModes,
      ),
      'physioStates': _sortedCounts(physioStates),
      'safetyGates': _sortedCounts(selectedSafety),
      'cyclePhases': _sortedCounts(cyclePhases),
      'sources': sources.values.map((source) => source.toMap()).toList(),
    };
  }

  Future<void> _parseDecisions() async {
    final source = sources[decisionsFile]!;
    await for (final raw in _lines(filesByName[decisionsFile]!)) {
      final line = raw.trim();
      if (line.isEmpty) continue;
      final root = _decodeObject(line);
      if (root == null) {
        source.malformedLines++;
        continue;
      }
      final timestamp = _timestamp(root['timestamp']);
      source.observeTimestamp(timestamp, windowStartMs, windowEndMs);
      if (!_inWindow(timestamp)) continue;

      // An async auditor follow-up mirrors an already committed tick.
      if (_text(root['record_type'])?.toLowerCase() == 'auditor_followup') {
        auditorFollowupCount++;
        continue;
      }

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
          _text(outcome?['decision']) ?? _text(outcome?['clinical_decision']);
      final patientMode =
          _text(_map(adjustments?['patient_mode'])?['mode']) ??
          _text(root['patient_mode']);
      final safetyGate = _text(
        _map(adjustments?['safety_risk'])?['safety_gate'],
      );
      final narrative = _text(outcome?['narrative']);

      _observeGlucose(timestamp!, bg, decisionGlucose, decisionGlucoseStats);
      if (iob != null) decisionIob.add(timestamp, iob, windowStartMs, windowEndMs);
      if (cob != null) decisionCob.add(timestamp, cob, windowStartMs, windowEndMs);
      if (smb > 0) decisionSmb.add(timestamp, smb, windowStartMs, windowEndMs);
      decisionSmbTotal += smb > 0 ? smb : 0;
      if (decision != null) _bump(decisionTypes, decision);
      if (patientMode != null) _bump(decisionModes, patientMode);
      if (safetyGate != null) _bump(decisionSafetyGates, safetyGate);
      _observeLatest(timestamp, iob, isIob: true, sourcePriority: 3);
      _observeLatest(timestamp, cob, isIob: false, sourcePriority: 3);
      decisionTimeline.add(<String, Object?>{
        'timestampMs': timestamp,
        'bgMgdl': bg,
        'iobU': iob,
        'cobG': cob,
        'smbU': smb,
        'decision': decision ?? 'Décision non renseignée',
        'patientMode': patientMode,
        'safetyGate': safetyGate,
        'narrative': narrative,
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
      final epochMin = int.tryParse(columns[1].trim());
      final timestamp = epochMin == null ? null : epochMin * 60000;
      source.observeTimestamp(timestamp, windowStartMs, windowEndMs);
      if (!_inWindow(timestamp)) continue;
      final bg = double.tryParse(columns[2].trim());
      final iob = double.tryParse(columns[4].trim());
      fusedIsf.add(double.tryParse(columns[9].trim()));
      profileIsf.add(double.tryParse(columns[11].trim()));
      final smbFinal = double.tryParse(columns[14].trim()) ?? 0;
      _observeGlucose(timestamp!, bg, pkpdGlucose, pkpdGlucoseStats);
      if (iob != null) pkpdIob.add(timestamp, iob, windowStartMs, windowEndMs);
      if (smbFinal > 0) {
        pkpdSmbTotal += smbFinal;
        pkpdSmb.add(timestamp, smbFinal, windowStartMs, windowEndMs);
      }
      _observeLatest(timestamp, iob, isIob: true, sourcePriority: 2);
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
      source.observeTimestamp(timestamp, windowStartMs, windowEndMs);
      if (!_inWindow(timestamp)) continue;

      hormonitorEventCount++;
      final story = _map(root['patient_story']);
      final mode = _text(story?['patient_mode']) ?? _text(root['patient_mode']);
      final physio = _text(root['physio_state']);
      final safety = _text(root['safety_gate']);
      final cycle = _text(root['cycle_phase']);
      final decision = _text(root['final_loop_decision_type']);
      final bg = _asDouble(root['current_bg_mgdl']);
      final iob = _asDouble(root['iob_u']);
      final cob = _asDouble(root['cob_g']);

      if (story != null && story.isNotEmpty) patientStoryCount++;
      if (mode != null) _bump(hormonitorModes, mode);
      if (physio != null) _bump(physioStates, physio);
      if (safety != null) _bump(hormonitorSafetyGates, safety);
      if (cycle != null && isUsableCyclePhase(cycle)) {
        _bump(cyclePhases, cycle);
      }
      if (decisionCount == 0 && decision != null) {
        _bump(decisionTypes, decision);
      }
      _observeGlucose(
        timestamp!,
        bg,
        hormonitorGlucose,
        hormonitorGlucoseStats,
      );
      if (iob != null) {
        hormonitorIob.add(timestamp, iob, windowStartMs, windowEndMs);
      }
      if (cob != null) {
        hormonitorCob.add(timestamp, cob, windowStartMs, windowEndMs);
      }
      _observeLatest(timestamp, iob, isIob: true, sourcePriority: 1);
      _observeLatest(timestamp, cob, isIob: false, sourcePriority: 1);
      hormonitorTimeline.add(<String, Object?>{
        'timestampMs': timestamp,
        'bgMgdl': bg,
        'iobU': iob,
        'cobG': cob,
        'smbU': 0.0,
        'decision': decision ?? 'Événement Hormonitor',
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
      final day = _text(root['day_local']);
      final dayTimestamp = _dayTimestamp(day);
      final generatedAt = _timestamp(root['generated_at']) ?? dayTimestamp;
      source.observeTimestamp(dayTimestamp, windowStartMs, windowEndMs);
      if (day == null || !_inWindow(dayTimestamp)) continue;
      final tdd = _asDouble(root['tdd_24h_total_u']);
      if (tdd == null || !tdd.isFinite) continue;
      final previous = tddByDay[day];
      final rank = generatedAt ?? 0;
      if (previous == null || rank >= previous.generatedAtMs) {
        tddByDay[day] = _DailyTdd(value: tdd, generatedAtMs: rank);
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
      source.observeTimestamp(timestamp, windowStartMs, windowEndMs);
    }
  }

  bool _inWindow(int? timestamp) =>
      timestamp != null &&
      timestamp >= windowStartMs &&
      timestamp < windowEndMs;

  void _observeGlucose(
    int timestamp,
    double? value,
    _PointBuffer buffer,
    _GlucoseStats stats,
  ) {
    if (value == null || !value.isFinite || value < 20 || value > 600) return;
    buffer.add(timestamp, value, windowStartMs, windowEndMs);
    stats.add(timestamp, value);
  }

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
  _SourceAccumulator({required this.name}) : sourceName = name;

  final String name;
  bool present = false;
  String sourceName;
  int recordsInWindow = 0;
  int malformedLines = 0;
  int? latestTimestampMs;
  int sourceSize = 0;
  bool truncated = false;
  int? coverageStartMs;
  int? coverageEndMs;
  bool coverageComplete = false;
  String extractionMode = 'stream';

  void observeTimestamp(int? timestamp, int startMs, int endMs) {
    if (timestamp == null) return;
    if (latestTimestampMs == null || timestamp > latestTimestampMs!) {
      latestTimestampMs = timestamp;
    }
    if (timestamp >= startMs && timestamp < endMs) recordsInWindow++;
  }

  Map<String, Object?> toMap() => <String, Object?>{
    'name': name,
    'present': present,
    'sourceName': sourceName,
    'recordsInWindow': recordsInWindow,
    'malformedLines': malformedLines,
    'latestTimestampMs': latestTimestampMs,
    'sourceSize': sourceSize,
    'truncated': truncated,
    'coverageStartMs': coverageStartMs,
    'coverageEndMs': coverageEndMs,
    'coverageComplete': coverageComplete,
    'extractionMode': extractionMode,
  };
}

class _PointBuffer {
  _PointBuffer(this.capacity, {this.valueKey = 'valueMgdl'});
  final int capacity;
  final String valueKey;
  final Map<int, Map<String, Object?>> _buckets = <int, Map<String, Object?>>{};

  bool get isNotEmpty => _buckets.isNotEmpty;

  void add(int timestamp, double value, int startMs, int endMs) {
    final span = endMs - startMs;
    if (span <= 0) return;
    final bucket = (((timestamp - startMs) * capacity) ~/ span).clamp(
      0,
      capacity - 1,
    );
    _buckets[bucket] = <String, Object?>{
      'timestampMs': timestamp,
      valueKey: value,
    };
  }

  List<Map<String, Object?>> get sorted {
    final result = _buckets.values.toList();
    result.sort(
      (a, b) => (_asInt(a['timestampMs']) ?? 0).compareTo(
        _asInt(b['timestampMs']) ?? 0,
      ),
    );
    return result;
  }
}

class _RecentTimeline {
  _RecentTimeline(this.capacity);
  final int capacity;
  final List<Map<String, Object?>> _values = <Map<String, Object?>>[];

  bool get isNotEmpty => _values.isNotEmpty;

  void add(Map<String, Object?> value) {
    _values.add(value);
    if (_values.length <= capacity) return;
    var oldest = 0;
    for (var index = 1; index < _values.length; index++) {
      if ((_asInt(_values[index]['timestampMs']) ?? 0) <
          (_asInt(_values[oldest]['timestampMs']) ?? 0)) {
        oldest = index;
      }
    }
    _values.removeAt(oldest);
  }

  List<Map<String, Object?>> get sortedNewestFirst {
    final result = _values.toList();
    result.sort(
      (a, b) => (_asInt(b['timestampMs']) ?? 0).compareTo(
        _asInt(a['timestampMs']) ?? 0,
      ),
    );
    return result;
  }
}

class _GlucoseStats {
  int count = 0;
  int low = 0;
  int high = 0;
  double sum = 0;
  int latestTimestamp = -1;
  double? latestValue;

  void add(int timestamp, double value) {
    count++;
    sum += value;
    if (value < 70) {
      low++;
    } else if (value > 180) {
      high++;
    }
    if (timestamp >= latestTimestamp) {
      latestTimestamp = timestamp;
      latestValue = value;
    }
  }

  double? get mean => count == 0 ? null : sum / count;
  double? get lowPct => _pct(low, count);
  double? get highPct => _pct(high, count);
  double? get inRangePct => _pct(count - low - high, count);
}

class _RunningMean {
  int count = 0;
  double sum = 0;

  void add(double? value) {
    if (value == null || !value.isFinite) return;
    count++;
    sum += value;
  }

  double? get mean => count == 0 ? null : sum / count;
}

class _DailyTdd {
  const _DailyTdd({required this.value, required this.generatedAtMs});
  final double value;
  final int generatedAtMs;
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
  if (day == null) return null;
  final parts = day.split('-').map(int.tryParse).toList();
  if (parts.length != 3 || parts.any((part) => part == null)) return null;
  return DateTime(parts[0]!, parts[1]!, parts[2]!).millisecondsSinceEpoch;
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
