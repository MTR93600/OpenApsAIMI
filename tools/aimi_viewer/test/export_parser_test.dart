import 'dart:convert';
import 'dart:io';

import 'package:aimi_viewer/src/export_parser.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  late Directory directory;

  setUp(() async {
    directory = await Directory.systemTemp.createTemp('aimi_viewer_test_');
  });

  tearDown(() async {
    await directory.delete(recursive: true);
  });

  test('respecte strictement la fenêtre civile [début, fin)', () async {
    final start = DateTime(2026, 8, 26).millisecondsSinceEpoch;
    final end = DateTime(2026, 8, 27).millisecondsSinceEpoch;
    final decisions = await _writeFile(directory, decisionsFile, <String>[
      _decision(start - 1, bg: 55, smb: 1),
      _decision(start, bg: 110, smb: 0.1),
      _decision(end - 1, bg: 195, smb: 0.2),
      _decision(end, bg: 240, smb: 1),
    ]);

    final result = await _parse(start, end, <Map<String, Object>>[
      _metadata(decisionsFile, decisions),
    ]);

    expect(result['decisionCount'], 2);
    expect(result['latestBgMgdl'], 195);
    expect(result['totalSmbU'], closeTo(0.3, 0.0001));
  });

  test('sépare auditor_followup et ne classe pas un outcome absent', () async {
    final start = DateTime(2026, 8, 26).millisecondsSinceEpoch;
    final end = DateTime(2026, 8, 27).millisecondsSinceEpoch;
    final decisions = await _writeFile(directory, decisionsFile, <String>[
      _decision(start + 1000, bg: 100, smb: 0.1),
      jsonEncode(<String, Object>{
        'record_type': 'auditor_followup',
        'timestamp': start + 2000,
        'parent_event_id': 'event-1',
      }),
      jsonEncode(<String, Object>{
        'event_id': 'event-without-outcome',
        'timestamp': start + 3000,
        'baseline_state': <String, Object>{'current_bg_mgdl': 101},
      }),
    ]);

    final result = await _parse(start, end, <Map<String, Object>>[
      _metadata(decisionsFile, decisions),
    ]);

    expect(result['decisionCount'], 2);
    expect(result['auditorFollowupCount'], 1);
    expect((result['timeline'] as List), hasLength(2));
    expect(
      (result['decisionTypes'] as Map).values.fold<int>(
        0,
        (a, b) => a + b as int,
      ),
      1,
    );
  });

  test('priorise la sécurité Hormonitor sans doubler les décisions', () async {
    final start = DateTime(2026, 8, 26).millisecondsSinceEpoch;
    final end = DateTime(2026, 8, 27).millisecondsSinceEpoch;
    final decisions = await _writeFile(directory, decisionsFile, <String>[
      _decision(start + 1000, bg: 100, smb: 0, safety: 'SafetyPass'),
      _decision(start + 2000, bg: 101, smb: 0, safety: 'SafetyPass'),
    ]);
    final events = await _writeFile(directory, hormonitorEventsFile, <String>[
      _hormonitor(start + 1000, safety: 'SafetyLGS_T2', cycle: 'UNKNOWN'),
      _hormonitor(start + 2000, safety: 'SafetyLGS_T1', cycle: 'LUTEAL'),
    ]);

    final result = await _parse(start, end, <Map<String, Object>>[
      _metadata(decisionsFile, decisions),
      _metadata(hormonitorEventsFile, events),
    ]);

    expect(result['safetyGates'], <String, int>{
      'SafetyLGS_T2': 1,
      'SafetyLGS_T1': 1,
    });
    expect(result['physioStates'], <String, int>{'OPTIMAL': 2});
    expect((result['cyclePhases'] as Map), <String, int>{'LUTEAL': 1});
  });

  test('conserve UNKNOWN comme état physiologique à expliquer', () async {
    final start = DateTime(2026, 8, 26).millisecondsSinceEpoch;
    final end = DateTime(2026, 8, 27).millisecondsSinceEpoch;
    final events = await _writeFile(directory, hormonitorEventsFile, <String>[
      _hormonitor(
        start + 1000,
        safety: 'SafetyPass',
        cycle: 'UNKNOWN',
        physio: 'UNKNOWN',
      ),
    ]);

    final result = await _parse(start, end, <Map<String, Object>>[
      _metadata(hormonitorEventsFile, events),
    ]);

    expect(result['physioStates'], <String, int>{'UNKNOWN': 1});
    expect(result['cyclePhases'], isEmpty);
  });

  test(
    'garde la dernière issue par day_local et calcule la TDD moyenne',
    () async {
      final start = DateTime(2026, 8, 24).millisecondsSinceEpoch;
      final end = DateTime(2026, 8, 31).millisecondsSinceEpoch;
      final daily = await _writeFile(directory, hormonitorDailyFile, <String>[
        _daily('2026-08-24', 20, generatedHour: 8),
        _daily('2026-08-24', 30, generatedHour: 20),
        _daily('2026-08-25', 40, generatedHour: 20),
        _daily('2026-08-31', 100, generatedHour: 8),
      ]);

      final result = await _parse(start, end, <Map<String, Object>>[
        _metadata(hormonitorDailyFile, daily),
      ]);

      expect(result['dailyTddDays'], 2);
      expect(result['dailyTddU'], 35);
    },
  );

  test('expose une seule source logique décisions et sa couverture', () async {
    final start = DateTime(2026, 8, 26).millisecondsSinceEpoch;
    final end = DateTime(2026, 8, 27).millisecondsSinceEpoch;
    final fallback = await _writeFile(
      directory,
      decisions24hSourceFile,
      <String>[_decision(start + 1000, bg: 100, smb: 0)],
    );

    final metadata =
        _metadata(decisionsFile, fallback)
          ..['sourceName'] = decisions24hSourceFile
          ..['coverageStartMs'] = start
          ..['coverageEndMs'] = end
          ..['coverageComplete'] = false
          ..['extractionMode'] = 'last24h_fallback';
    final result = await _parse(start, end, <Map<String, Object>>[metadata]);
    final sources = (result['sources'] as List).cast<Map>();

    expect(
      sources.where((item) => item['name'] == decisionsFile),
      hasLength(1),
    );
    expect(
      sources.any((item) => item['name'] == decisions24hSourceFile),
      isFalse,
    );
    expect(
      sources.any((item) => item['name'].toString().contains('daily_state')),
      isFalse,
    );
    final source = sources.firstWhere((item) => item['name'] == decisionsFile);
    expect(source['sourceName'], decisions24hSourceFile);
    expect(source['coverageComplete'], isFalse);
  });

  test(
    'borne les buffers glucose à 320 et journal à timelineCapacity',
    () async {
      final start = DateTime(2026, 8, 26).millisecondsSinceEpoch;
      final end = DateTime(2026, 8, 27).millisecondsSinceEpoch;
      final lines = List<String>.generate(1000, (index) {
        final timestamp = start + index * 60 * 1000;
        return _decision(timestamp, bg: 80 + (index % 100).toDouble(), smb: 0);
      });
      final decisions = await _writeFile(directory, decisionsFile, lines);

      final result = await _parse(start, end, <Map<String, Object>>[
        _metadata(decisionsFile, decisions),
      ]);

      expect((result['glucose'] as List).length, lessThanOrEqualTo(320));
      expect((result['timeline'] as List), hasLength(timelineCapacity));
    },
  );

  test('expose la narrative de décision et les séries IOB/COB/SMB', () async {
    final start = DateTime(2026, 8, 26).millisecondsSinceEpoch;
    final end = DateTime(2026, 8, 27).millisecondsSinceEpoch;
    final decisions = await _writeFile(directory, decisionsFile, <String>[
      _decision(
        start + 1000,
        bg: 130,
        smb: 0.4,
        narrative: 'IOB haut, SMB réduit par prudence.',
      ),
    ]);

    final result = await _parse(start, end, <Map<String, Object>>[
      _metadata(decisionsFile, decisions),
    ]);

    final timeline = (result['timeline'] as List).cast<Map>();
    expect(timeline.single['narrative'], 'IOB haut, SMB réduit par prudence.');
    expect((result['iobSeries'] as List), isNotEmpty);
    expect((result['cobSeries'] as List), isNotEmpty);
    expect((result['smbSeries'] as List), isNotEmpty);
    final smbPoint = (result['smbSeries'] as List).cast<Map>().single;
    expect(smbPoint['value'], closeTo(0.4, 0.0001));
  });
}

Future<File> _writeFile(
  Directory directory,
  String name,
  List<String> lines,
) async {
  final file = File('${directory.path}/$name');
  await file.writeAsString(lines.join('\n'));
  return file;
}

Future<Map<String, Object?>> _parse(
  int start,
  int end,
  List<Map<String, Object>> files,
) => parseExportsInBackground(<String, Object?>{
  'windowStartMs': start,
  'windowEndMs': end,
  'files': files,
});

Map<String, Object> _metadata(String name, File file) => <String, Object>{
  'name': name,
  'sourceName': name,
  'path': file.path,
  'sourceSize': file.lengthSync(),
  'stagedSize': file.lengthSync(),
  'lastModifiedMs': file.lastModifiedSync().millisecondsSinceEpoch,
  'truncated': false,
  'coverageComplete': true,
  'extractionMode': 'test',
};

String _decision(
  int timestamp, {
  required double bg,
  required double smb,
  String safety = 'SafetyPass',
  String? narrative,
}) => jsonEncode(<String, Object>{
  'event_id': 'event-$timestamp',
  'timestamp': timestamp,
  'baseline_state': <String, Object>{
    'current_bg_mgdl': bg,
    'iob_u': 1.1,
    'cob_g': 5,
  },
  'adjustments': <String, Object>{
    'patient_mode': <String, Object>{'mode': 'RESTING'},
    'safety_risk': <String, Object>{'safety_gate': safety},
  },
  'outcome': <String, Object>{
    'decision': smb > 0 ? 'SMB_Delivery' : 'No_Action',
    'amount': smb,
    if (narrative != null) 'narrative': narrative,
  },
});

String _hormonitor(
  int timestamp, {
  required String safety,
  required String cycle,
  String physio = 'OPTIMAL',
}) => jsonEncode(<String, Object>{
  'event_id': 'horm-$timestamp',
  'timestamp': timestamp,
  'current_bg_mgdl': 120,
  'iob_u': 1.0,
  'cob_g': 3,
  'physio_state': physio,
  'safety_gate': safety,
  'cycle_phase': cycle,
  'final_loop_decision_type': 'smb',
  'patient_story': <String, Object>{'patient_mode': 'FAST_MEAL'},
});

String _daily(String day, double tdd, {required int generatedHour}) =>
    jsonEncode(<String, Object>{
      'day_local': day,
      'generated_at':
          '${day}T${generatedHour.toString().padLeft(2, '0')}:00:00Z',
      'tdd_24h_total_u': tdd,
    });
