enum AnalysisPeriodKind { day, week }

/// A local civil analysis period. Boundaries are rebuilt from calendar fields
/// instead of adding 24-hour durations, so DST days stay aligned to midnight.
class AnalysisPeriod {
  AnalysisPeriod._(this.kind, DateTime anchor)
    : anchor = DateTime(anchor.year, anchor.month, anchor.day);

  factory AnalysisPeriod.day(DateTime anchor) =>
      AnalysisPeriod._(AnalysisPeriodKind.day, anchor);

  factory AnalysisPeriod.week(DateTime anchor) =>
      AnalysisPeriod._(AnalysisPeriodKind.week, anchor);

  final AnalysisPeriodKind kind;
  final DateTime anchor;

  DateTime get startLocal {
    if (kind == AnalysisPeriodKind.day) return anchor;
    return DateTime(anchor.year, anchor.month, anchor.day - anchor.weekday + 1);
  }

  DateTime get endLocal {
    final start = startLocal;
    return DateTime(
      start.year,
      start.month,
      start.day + (kind == AnalysisPeriodKind.day ? 1 : 7),
    );
  }

  int get startMs => startLocal.millisecondsSinceEpoch;
  int get endMs => endLocal.millisecondsSinceEpoch;

  /// End of the actually observable part of this civil period.
  ///
  /// Past periods keep their civil end. The current day/week stops at the
  /// refresh instant so charts and coverage never imply knowledge of future
  /// samples.
  int endMsAt(DateTime now) {
    final nowMs = now.millisecondsSinceEpoch;
    return isCurrentAt(now) ? nowMs.clamp(startMs + 1, endMs) : endMs;
  }

  AnalysisPeriod previous() {
    final start = startLocal;
    return AnalysisPeriod._(
      kind,
      DateTime(
        start.year,
        start.month,
        start.day - (kind == AnalysisPeriodKind.day ? 1 : 7),
      ),
    );
  }

  AnalysisPeriod next() {
    final start = startLocal;
    return AnalysisPeriod._(
      kind,
      DateTime(
        start.year,
        start.month,
        start.day + (kind == AnalysisPeriodKind.day ? 1 : 7),
      ),
    );
  }

  AnalysisPeriod withKind(AnalysisPeriodKind nextKind) =>
      AnalysisPeriod._(nextKind, anchor);

  AnalysisPeriod withAnchor(DateTime nextAnchor) =>
      AnalysisPeriod._(kind, nextAnchor);

  bool isCurrentAt(DateTime now) {
    final value = now.millisecondsSinceEpoch;
    return value >= startMs && value < endMs;
  }

  String get label {
    if (kind == AnalysisPeriodKind.day) return _longDate(startLocal);
    final endInclusive = DateTime(
      endLocal.year,
      endLocal.month,
      endLocal.day - 1,
    );
    if (startLocal.year == endInclusive.year &&
        startLocal.month == endInclusive.month) {
      return 'Semaine du ${startLocal.day} au ${endInclusive.day} '
          '${_months[endInclusive.month - 1]} ${endInclusive.year}';
    }
    if (startLocal.year == endInclusive.year) {
      return 'Semaine du ${startLocal.day} ${_months[startLocal.month - 1]} '
          'au ${endInclusive.day} ${_months[endInclusive.month - 1]} '
          '${endInclusive.year}';
    }
    return 'Semaine du ${startLocal.day} ${_months[startLocal.month - 1]} '
        '${startLocal.year} au ${endInclusive.day} '
        '${_months[endInclusive.month - 1]} ${endInclusive.year}';
  }

  String get compactLabel {
    if (kind == AnalysisPeriodKind.day) {
      return '${startLocal.day.toString().padLeft(2, '0')}/'
          '${startLocal.month.toString().padLeft(2, '0')}/${startLocal.year}';
    }
    final endInclusive = DateTime(
      endLocal.year,
      endLocal.month,
      endLocal.day - 1,
    );
    return '${startLocal.day.toString().padLeft(2, '0')}/'
        '${startLocal.month.toString().padLeft(2, '0')} – '
        '${endInclusive.day.toString().padLeft(2, '0')}/'
        '${endInclusive.month.toString().padLeft(2, '0')}';
  }

  static const List<String> _months = <String>[
    'janvier',
    'février',
    'mars',
    'avril',
    'mai',
    'juin',
    'juillet',
    'août',
    'septembre',
    'octobre',
    'novembre',
    'décembre',
  ];

  static const List<String> _weekdays = <String>[
    'lundi',
    'mardi',
    'mercredi',
    'jeudi',
    'vendredi',
    'samedi',
    'dimanche',
  ];

  static String _longDate(DateTime value) =>
      '${_weekdays[value.weekday - 1]} ${value.day} '
      '${_months[value.month - 1]} ${value.year}';

  @override
  bool operator ==(Object other) =>
      other is AnalysisPeriod &&
      other.kind == kind &&
      other.startMs == startMs &&
      other.endMs == endMs;

  @override
  int get hashCode => Object.hash(kind, startMs, endMs);
}
