import 'package:aimi_viewer/src/label_catalog.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('les protections LGS ont des libellés et effets fidèles', () {
    expect(
      labelFor(LabelDomain.safetyGate, 'SafetyPass'),
      'Aucune protection hypo déclenchée',
    );
    expect(
      labelFor(LabelDomain.safetyGate, 'SafetyLGS_T2'),
      'Protection préventive — baisse prédite',
    );
    expect(safetyExplanation('SafetyLGS_T2'), contains('limitée à 25 %'));
    expect(safetyExplanation('SafetyLGS_T1'), contains('0 U/h'));
  });

  test('UNKNOWN n’est pas une phase de cycle exploitable', () {
    expect(isUsableCyclePhase('UNKNOWN'), isFalse);
    expect(isUsableCyclePhase('unknown'), isFalse);
    expect(isUsableCyclePhase('LUTEAL'), isTrue);
  });

  test('UNKNOWN physiologique est traduit et explicitement neutre', () {
    expect(labelFor(LabelDomain.physioState, 'UNKNOWN'), 'État non déterminé');
    expect(
      labelExplanation(LabelDomain.physioState, 'UNKNOWN'),
      contains('ne signifie pas qu’une anomalie'),
    );
  });
}
