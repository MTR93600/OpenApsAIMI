import 'dart:convert';
import 'dart:io';

import 'package:aimi_viewer/src/export_parser.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test(
    'fusionne les décisions, le PK/PD et Hormonitor sur 24 heures',
    () async {
      final directory = await Directory.systemTemp.createTemp(
        'aimi_viewer_test_',
      );
      addTearDown(() => directory.delete(recursive: true));

      final now = DateTime.utc(2026, 8, 26, 12).millisecondsSinceEpoch;
      final t1 = now - const Duration(hours: 2).inMilliseconds;
      final t2 = now - const Duration(hours: 1).inMilliseconds;
      final old = now - const Duration(hours: 30).inMilliseconds;

      final decisions = File('${directory.path}/$decisions24hFile');
      await decisions.writeAsString(
        <String>[
          _decision(old, bg: 55, smb: 1),
          _decision(t1, bg: 110, smb: 0.1, mode: 'MEAL'),
          _decision(t2, bg: 195, smb: 0.2, mode: 'STRESS_RESISTANCE'),
        ].join('\n'),
      );

      final pkpd = File('${directory.path}/$pkpdFile');
      await pkpd.writeAsString(
        <String>[
          _pkpdRow(
            t1,
            bg: 110,
            iob: 1.2,
            fusedIsf: 42,
            profileIsf: 48,
            smb: 0.1,
          ),
          _pkpdRow(
            t2,
            bg: 195,
            iob: 1.4,
            fusedIsf: 44,
            profileIsf: 48,
            smb: 0.2,
          ),
        ].join('\n'),
      );

      final events = File('${directory.path}/$hormonitorEventsFile');
      await events.writeAsString(
        <String>[
          _hormonitor(
            t1,
            mode: 'MEAL',
            physio: 'RESTING',
            safety: 'SafetyPass',
          ),
          _hormonitor(
            t2,
            mode: 'STRESS_RESISTANCE',
            physio: 'STRESS',
            safety: 'SafetyPass',
          ),
        ].join('\n'),
      );

      final daily = File('${directory.path}/$hormonitorDailyFile');
      await daily.writeAsString(
        jsonEncode(<String, Object>{
          'generated_at':
              DateTime.fromMillisecondsSinceEpoch(
                t2,
                isUtc: true,
              ).toIso8601String(),
          'day_local': '2026-08-26',
          'tdd_24h_total_u': 31.5,
        }),
      );

      final result = await parseExportsInBackground(<String, Object?>{
        'nowMs': now,
        'files': <Map<String, Object>>[
          _metadata(decisions24hFile, decisions),
          _metadata(pkpdFile, pkpd),
          _metadata(hormonitorEventsFile, events),
          _metadata(hormonitorDailyFile, daily),
        ],
      });

      expect(result['decisionCount'], 2);
      expect(result['hormonitorEventCount'], 2);
      expect(result['latestBgMgdl'], 195);
      expect(result['tirPct'], closeTo(50, 0.001));
      expect(result['highPct'], closeTo(50, 0.001));
      expect(result['totalSmbU'], closeTo(0.3, 0.0001));
      // At equal timestamps the AIMI decision baseline is the canonical source,
      // ahead of the PK/PD observation and the Hormonitor mirror.
      expect(result['latestIobU'], 1.1);
      expect(result['meanFusedIsf'], 43);
      expect(result['dailyTddU'], 31.5);
      expect(result['patientStoryCoverage'], 100);
      expect((result['patientModes'] as Map)['MEAL'], 1);
      expect((result['patientModes'] as Map)['STRESS_RESISTANCE'], 1);
      expect((result['glucose'] as List), hasLength(2));
      expect((result['timeline'] as List), hasLength(2));

      final sources = (result['sources'] as List).cast<Map>();
      final decisionSource = sources.firstWhere(
        (source) => source['name'] == decisions24hFile,
      );
      expect(decisionSource['present'], isTrue);
      expect(decisionSource['recordsInWindow'], 2);
    },
  );

  test(
    'ignore une ligne JSONL malformée sans perdre les autres événements',
    () async {
      final directory = await Directory.systemTemp.createTemp(
        'aimi_viewer_malformed_',
      );
      addTearDown(() => directory.delete(recursive: true));
      final now = DateTime.utc(2026, 8, 26, 12).millisecondsSinceEpoch;
      final file = File('${directory.path}/$hormonitorEventsFile');
      await file.writeAsString(
        '{invalide}\n${_hormonitor(now - 60000, mode: 'RESTING', physio: 'RESTING', safety: 'SafetyPass')}',
      );

      final result = await parseExportsInBackground(<String, Object?>{
        'nowMs': now,
        'files': <Map<String, Object>>[_metadata(hormonitorEventsFile, file)],
      });
      final source = (result['sources'] as List).cast<Map>().firstWhere(
        (entry) => entry['name'] == hormonitorEventsFile,
      );

      expect(result['hormonitorEventCount'], 1);
      expect(source['malformedLines'], 1);
    },
  );
}

Map<String, Object> _metadata(String name, File file) => <String, Object>{
  'name': name,
  'path': file.path,
  'sourceSize': file.lengthSync(),
  'stagedSize': file.lengthSync(),
  'lastModifiedMs': file.lastModifiedSync().millisecondsSinceEpoch,
  'truncated': false,
};

String _decision(
  int timestamp, {
  required double bg,
  required double smb,
  String mode = 'RESTING',
}) {
  return jsonEncode(<String, Object>{
    'event_id': 'event-$timestamp',
    'timestamp': timestamp,
    'baseline_state': <String, Object>{
      'current_bg_mgdl': bg,
      'iob_u': 1.1,
      'cob_g': 5,
    },
    'adjustments': <String, Object>{
      'patient_mode': <String, Object>{'mode': mode},
      'safety_risk': <String, Object>{'safety_gate': 'SafetyPass'},
    },
    'outcome': <String, Object>{
      'decision': smb > 0 ? 'SMB_Delivery' : 'No_Action',
      'amount': smb,
    },
  });
}

String _hormonitor(
  int timestamp, {
  required String mode,
  required String physio,
  required String safety,
}) {
  return jsonEncode(<String, Object>{
    'event_id': 'horm-$timestamp',
    'timestamp': timestamp,
    'current_bg_mgdl': 120,
    'iob_u': 1.0,
    'cob_g': 3,
    'physio_state': physio,
    'safety_gate': safety,
    'cycle_phase': 'LUTEAL',
    'final_loop_decision_type': 'smb',
    'patient_story': <String, Object>{
      'patient_mode': mode,
      'patient_mode_confidence': 0.9,
    },
  });
}

String _pkpdRow(
  int timestamp, {
  required double bg,
  required double iob,
  required double fusedIsf,
  required double profileIsf,
  required double smb,
}) {
  final epochMin = timestamp ~/ 60000;
  return <Object?>[
    DateTime.fromMillisecondsSinceEpoch(
      timestamp,
      isUtc: true,
    ).toIso8601String(),
    epochMin,
    bg,
    1.0,
    iob,
    3.0,
    60,
    6.0,
    75.0,
    fusedIsf,
    45.0,
    profileIsf,
    0.3,
    smb,
    smb,
    1.0,
    1.0,
    1.0,
    false,
    false,
    0.05,
    'RISING',
    0.0,
    0.0,
    0.0,
  ].join(',');
}
