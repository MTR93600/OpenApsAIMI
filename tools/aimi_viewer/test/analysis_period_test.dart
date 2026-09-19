import 'package:aimi_viewer/src/analysis_period.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test(
    'le jour civil utilise deux minuits locaux et une borne de fin exclue',
    () {
      final period = AnalysisPeriod.day(DateTime(2026, 3, 29, 18, 42));

      expect(period.startLocal, DateTime(2026, 3, 29));
      expect(period.endLocal, DateTime(2026, 3, 30));
      expect(period.startLocal.hour, 0);
      expect(period.endLocal.hour, 0);
      expect(period.label, contains('29 mars 2026'));
    },
  );

  test('la semaine ISO commence lundi et se termine lundi suivant', () {
    final period = AnalysisPeriod.week(DateTime(2026, 8, 26));

    expect(period.startLocal, DateTime(2026, 8, 24));
    expect(period.endLocal, DateTime(2026, 8, 31));
    expect(period.label, 'Semaine du 24 au 30 août 2026');
  });

  test('navigation civile conserve le type de période', () {
    final day = AnalysisPeriod.day(DateTime(2026, 1, 1));
    expect(day.previous().startLocal, DateTime(2025, 12, 31));
    expect(day.next().startLocal, DateTime(2026, 1, 2));

    final week = AnalysisPeriod.week(DateTime(2026, 1, 1));
    expect(week.previous().startLocal, DateTime(2025, 12, 22));
    expect(week.next().startLocal, DateTime(2026, 1, 5));
  });

  test('la période courante s’arrête à l’instant du rafraîchissement', () {
    final now = DateTime(2026, 8, 29, 17, 31);
    final today = AnalysisPeriod.day(now);
    final currentWeek = AnalysisPeriod.week(now);
    final yesterday = AnalysisPeriod.day(DateTime(2026, 8, 28));

    expect(today.endMsAt(now), now.millisecondsSinceEpoch);
    expect(currentWeek.endMsAt(now), now.millisecondsSinceEpoch);
    expect(yesterday.endMsAt(now), yesterday.endMs);
  });
}
