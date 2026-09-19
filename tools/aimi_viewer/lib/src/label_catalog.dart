enum LabelDomain { decision, patientMode, physioState, safetyGate, cyclePhase }

const Map<String, String> _decisionLabels = <String, String>{
  'BASAL_MODULATION': 'Modulation de la basale',
  'SMB_DELIVERY': 'Micro-bolus SMB',
  'NO_ACTION': 'Aucune action',
  'SMB': 'Micro-bolus SMB',
  'TBR_UP': 'Basale temporaire augmentée',
  'TBR_DOWN': 'Basale temporaire réduite',
  'SUSPEND': 'Suspension de la basale',
  'NONE': 'Aucun changement',
};

const Map<String, String> _patientModeLabels = <String, String>{
  'DAWN_ENDOGENOUS': 'Phénomène de l’aube',
  'MEAL': 'Repas',
  'FAST_MEAL': 'Repas à absorption rapide',
  'MEAL_UNDECLARED': 'Repas non déclaré',
  'PROLONGED_MEAL': 'Repas à absorption prolongée',
  'STABLE_BASELINE': 'Mode de base — aucun scénario dominant',
  'STRESS_RESISTANCE': 'Résistance liée au stress',
  'POST_HYPO_RECOVERY': 'Récupération après hypoglycémie',
  'EXERCISE_AFTERBURN': 'Effet prolongé de l’activité',
  'PROTECTIVE': 'Mode protecteur',
  'RESISTANCE_PROBABLE': 'Résistance probable',
};

const Map<String, String> _physioLabels = <String, String>{
  'OPTIMAL': 'Aucun signal d’alerte détecté',
  'UNKNOWN': 'État non déterminé',
  'RECOVERY_NEEDED': 'Besoin de récupération estimé',
  'STRESS_DETECTED': 'Stress physiologique probable',
  'INFECTION_RISK': 'Anomalies physiologiques multiples',
  'RESTING': 'Repos',
  'STRESS': 'Stress physiologique',
  'ACTIVE': 'Activité en cours',
  'SLEEPING': 'Sommeil',
  'MALE_CIRCADIAN_HORMONAL': 'Rythme circadien et hormonal',
  'INTER_WAVE': 'Entre deux vagues d’absorption',
  'FIRST_WAVE': 'Première vague d’absorption',
  'SECOND_WAVE': 'Deuxième vague d’absorption',
  'LATE_FAT': 'Effet tardif des graisses/protéines',
};

const Map<String, String> _safetyLabels = <String, String>{
  'SAFETYPASS': 'Aucune protection hypo déclenchée',
  'SAFETYLGS_T1': 'Protection maximale — glycémie basse',
  'SAFETYLGS_T2': 'Protection préventive — baisse prédite',
  'SAFETYLGS_T3': 'Protection préventive — baisse à terme',
  'SAFETYNOISE': 'Protection — signal capteur bruité',
};

const Map<String, String> _safetyExplanations = <String, String>{
  'SAFETYPASS':
      'Aucune règle LGS de cette étape n’a réduit l’insuline. Cela ne résume pas toutes les sécurités AIMI.',
  'SAFETYLGS_T1':
      'La glycémie actuelle est sous le seuil de protection : basale suspendue (0 U/h) pendant 30 minutes.',
  'SAFETYLGS_T2':
      'Une baisse sous le seuil est prédite : basale limitée à 25 % pendant 30 minutes.',
  'SAFETYLGS_T3':
      'La glycémie à terme passe sous le seuil : basale limitée à 50 % pendant 15 minutes.',
  'SAFETYNOISE':
      'Le signal du capteur est très bruité : basale suspendue pendant 30 minutes par prudence.',
};

const Map<String, String> _cycleLabels = <String, String>{
  'MENSTRUATION': 'Menstruation',
  'FOLLICULAR': 'Phase folliculaire',
  'OVULATION': 'Ovulation',
  'LUTEAL': 'Phase lutéale',
};

String labelFor(LabelDomain domain, String raw) {
  final key = raw.trim().toUpperCase();
  final catalog = switch (domain) {
    LabelDomain.decision => _decisionLabels,
    LabelDomain.patientMode => _patientModeLabels,
    LabelDomain.physioState => _physioLabels,
    LabelDomain.safetyGate => _safetyLabels,
    LabelDomain.cyclePhase => _cycleLabels,
  };
  return catalog[key] ?? _prettify(raw);
}

String? safetyExplanation(String raw) =>
    _safetyExplanations[raw.trim().toUpperCase()];

String? labelExplanation(LabelDomain domain, String raw) {
  if (domain == LabelDomain.safetyGate) return safetyExplanation(raw);
  if (domain == LabelDomain.physioState &&
      raw.trim().toUpperCase() == 'UNKNOWN') {
    return 'Hormonitor n’a pas pu attribuer un état physiologique précis '
        'avec les données disponibles. Cela ne signifie pas qu’une anomalie '
        'a été détectée.';
  }
  if (domain == LabelDomain.physioState &&
      raw.trim().toUpperCase() == 'OPTIMAL') {
    return 'Aucun des signaux d’alerte recherchés n’a été détecté parmi les '
        'données disponibles ; il ne s’agit pas d’un diagnostic.';
  }
  return null;
}

bool isUsableCyclePhase(String raw) {
  final value = raw.trim().toUpperCase();
  return value.isNotEmpty && value != 'UNKNOWN' && value != 'UNKNOW';
}

String _prettify(String raw) {
  final text =
      raw
          .replaceAll(RegExp(r'(?<=[a-z0-9])(?=[A-Z])'), ' ')
          .replaceAll('_', ' ')
          .trim()
          .toLowerCase();
  if (text.isEmpty) return 'État non renseigné';
  return '${text[0].toUpperCase()}${text.substring(1)}';
}
