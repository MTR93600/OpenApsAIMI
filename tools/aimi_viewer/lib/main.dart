import 'dart:math' as math;

import 'package:flutter/material.dart';

import 'src/analysis_period.dart';
import 'src/dashboard_controller.dart';
import 'src/export_parser.dart';
import 'src/label_catalog.dart';
import 'src/models.dart';
import 'src/widgets/series_chart.dart';
import 'src/widgets/timeline_detail_sheet.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const AimiViewerApp());
}

class AimiViewerApp extends StatefulWidget {
  const AimiViewerApp({super.key});

  @override
  State<AimiViewerApp> createState() => _AimiViewerAppState();
}

class _AimiViewerAppState extends State<AimiViewerApp> {
  final DashboardController controller = DashboardController();

  @override
  void initState() {
    super.initState();
    controller.initialize();
  }

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    const seed = Color(0xFF48D7C2);
    final scheme = ColorScheme.fromSeed(
      seedColor: seed,
      brightness: Brightness.dark,
    ).copyWith(
      surface: const Color(0xFF101B24),
      surfaceContainer: const Color(0xFF172630),
      primary: seed,
      secondary: const Color(0xFFF5B84B),
      error: const Color(0xFFFF6B78),
    );
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'AIMI Viewer',
      theme: ThemeData(
        colorScheme: scheme,
        scaffoldBackgroundColor: const Color(0xFF09131B),
        useMaterial3: true,
        cardTheme: const CardThemeData(margin: EdgeInsets.zero, elevation: 0),
        navigationBarTheme: NavigationBarThemeData(
          backgroundColor: const Color(0xFF101B24),
          indicatorColor: seed.withValues(alpha: 0.18),
          labelTextStyle: WidgetStateProperty.all(
            const TextStyle(fontSize: 11),
          ),
        ),
      ),
      home: HomeScreen(controller: controller),
    );
  }
}

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.controller});

  final DashboardController controller;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  int tab = 0;
  DateTime? lastResumeRefresh;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed ||
        widget.controller.directory == null) {
      return;
    }
    final now = DateTime.now();
    if (lastResumeRefresh == null ||
        now.difference(lastResumeRefresh!) > const Duration(minutes: 1)) {
      lastResumeRefresh = now;
      widget.controller.refresh();
    }
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.controller,
      builder: (context, _) {
        final controller = widget.controller;
        return Scaffold(
          appBar: AppBar(
            titleSpacing: 20,
            title: const Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'AIMI Viewer',
                  style: TextStyle(fontWeight: FontWeight.w700),
                ),
                Text(
                  'Analyse locale · lecture seule',
                  style: TextStyle(fontSize: 11, color: Color(0xFF9EB1BE)),
                ),
              ],
            ),
            actions: [
              if (controller.directory != null)
                IconButton(
                  tooltip: 'Actualiser',
                  onPressed: controller.busy ? null : controller.refresh,
                  icon: const Icon(Icons.refresh_rounded),
                ),
              IconButton(
                tooltip: 'Choisir le dossier',
                onPressed: controller.busy ? null : controller.chooseDirectory,
                icon: const Icon(Icons.folder_open_rounded),
              ),
              const SizedBox(width: 6),
            ],
          ),
          body: SafeArea(
            child: Stack(
              children: [
                if (controller.directory == null)
                  _Onboarding(onChoose: controller.chooseDirectory)
                else
                  _DashboardContent(
                    controller: controller,
                    tab: tab,
                    onChooseDirectory: controller.chooseDirectory,
                  ),
                if (controller.busy)
                  const Align(
                    alignment: Alignment.topCenter,
                    child: LinearProgressIndicator(minHeight: 2),
                  ),
              ],
            ),
          ),
          bottomNavigationBar:
              controller.directory == null
                  ? null
                  : NavigationBar(
                    selectedIndex: tab,
                    onDestinationSelected:
                        (value) => setState(() => tab = value),
                    destinations: const [
                      NavigationDestination(
                        icon: Icon(Icons.monitor_heart_outlined),
                        selectedIcon: Icon(Icons.monitor_heart),
                        label: 'Analyse',
                      ),
                      NavigationDestination(
                        icon: Icon(Icons.hub_outlined),
                        selectedIcon: Icon(Icons.hub),
                        label: 'Hormonitor',
                      ),
                      NavigationDestination(
                        icon: Icon(Icons.timeline_outlined),
                        selectedIcon: Icon(Icons.timeline),
                        label: 'Journal',
                      ),
                      NavigationDestination(
                        icon: Icon(Icons.folder_copy_outlined),
                        selectedIcon: Icon(Icons.folder_copy),
                        label: 'Fichiers',
                      ),
                    ],
                  ),
        );
      },
    );
  }
}

class _Onboarding extends StatelessWidget {
  const _Onboarding({required this.onChoose});
  final VoidCallback onChoose;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(28),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 520),
          child: Column(
            children: [
              Container(
                width: 92,
                height: 92,
                decoration: BoxDecoration(
                  color: Theme.of(
                    context,
                  ).colorScheme.primary.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(28),
                ),
                child: Icon(
                  Icons.folder_open_rounded,
                  size: 48,
                  color: Theme.of(context).colorScheme.primary,
                ),
              ),
              const SizedBox(height: 26),
              const Text(
                'Connecter les exports AIMI',
                style: TextStyle(fontSize: 25, fontWeight: FontWeight.w700),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 12),
              const Text(
                'Sélectionnez Documents/AAPS une seule fois. Android mémorisera l’accès et AIMI Viewer retrouvera les fichiers à chaque ouverture.',
                style: TextStyle(height: 1.45, color: Color(0xFFB8C7D0)),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 26),
              FilledButton.icon(
                onPressed: onChoose,
                icon: const Icon(Icons.folder_rounded),
                label: const Text('Choisir Documents/AAPS'),
              ),
              const SizedBox(height: 28),
              const _SafetyNotice(),
            ],
          ),
        ),
      ),
    );
  }
}

class _DashboardContent extends StatelessWidget {
  const _DashboardContent({
    required this.controller,
    required this.tab,
    required this.onChooseDirectory,
  });

  final DashboardController controller;
  final int tab;
  final VoidCallback onChooseDirectory;

  @override
  Widget build(BuildContext context) {
    final data = controller.data;
    return Column(
      children: [
        if (controller.errorMessage != null)
          _ErrorBanner(
            message: controller.errorMessage!,
            onRetry: controller.refresh,
          ),
        _PeriodSelector(controller: controller),
        Expanded(
          child:
              data == null
                  ? _LoadingOrEmpty(
                    busy: controller.busy,
                    onRefresh: controller.refresh,
                  )
                  : IndexedStack(
                    index: tab,
                    children: [
                      _OverviewTab(
                        data: data,
                        directory: controller.directory!,
                        period: controller.period,
                      ),
                      _HormonitorTab(
                        data: data,
                        preference: controller.hormonePreference,
                        onPreferenceChanged: controller.setHormonePreference,
                      ),
                      _TimelineTab(data: data, period: controller.period),
                      _FilesTab(
                        data: data,
                        directory: controller.directory!,
                        onChooseDirectory: onChooseDirectory,
                      ),
                    ],
                  ),
        ),
      ],
    );
  }
}

class _PeriodSelector extends StatelessWidget {
  const _PeriodSelector({required this.controller});
  final DashboardController controller;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 9),
      decoration: const BoxDecoration(
        color: Color(0xFF101B24),
        border: Border(bottom: BorderSide(color: Color(0xFF263943))),
      ),
      child: Column(
        children: [
          SegmentedButton<AnalysisPeriodKind>(
            segments: const [
              ButtonSegment(
                value: AnalysisPeriodKind.day,
                label: Text('Jour'),
                icon: Icon(Icons.today_outlined, size: 17),
              ),
              ButtonSegment(
                value: AnalysisPeriodKind.week,
                label: Text('Semaine'),
                icon: Icon(Icons.date_range_outlined, size: 17),
              ),
            ],
            selected: <AnalysisPeriodKind>{controller.period.kind},
            onSelectionChanged:
                controller.busy
                    ? null
                    : (selection) => controller.setPeriodKind(selection.first),
            showSelectedIcon: false,
            style: const ButtonStyle(
              visualDensity: VisualDensity.compact,
              textStyle: WidgetStatePropertyAll(TextStyle(fontSize: 12)),
            ),
          ),
          const SizedBox(height: 6),
          Row(
            children: [
              IconButton(
                tooltip: 'Période précédente',
                onPressed: controller.busy ? null : controller.previousPeriod,
                icon: const Icon(Icons.chevron_left_rounded),
                visualDensity: VisualDensity.compact,
              ),
              Expanded(
                child: TextButton.icon(
                  onPressed:
                      controller.busy
                          ? null
                          : () async {
                            final selected = await showDatePicker(
                              context: context,
                              initialDate: controller.period.anchor,
                              firstDate: DateTime(2020),
                              lastDate: DateTime.now(),
                              helpText: 'Choisir une date d’analyse',
                              cancelText: 'Annuler',
                              confirmText: 'Choisir',
                            );
                            if (selected != null) {
                              await controller.selectDate(selected);
                            }
                          },
                  icon: const Icon(Icons.calendar_month_outlined, size: 18),
                  label: Text(
                    controller.period.label,
                    textAlign: TextAlign.center,
                    maxLines: 2,
                  ),
                ),
              ),
              IconButton(
                tooltip: 'Période suivante',
                onPressed:
                    controller.busy || !controller.canGoNext
                        ? null
                        : controller.nextPeriod,
                icon: const Icon(Icons.chevron_right_rounded),
                visualDensity: VisualDensity.compact,
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _LoadingOrEmpty extends StatelessWidget {
  const _LoadingOrEmpty({required this.busy, required this.onRefresh});
  final bool busy;
  final VoidCallback onRefresh;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (busy)
              const CircularProgressIndicator()
            else
              const Icon(Icons.find_in_page_outlined, size: 52),
            const SizedBox(height: 18),
            Text(busy ? 'Lecture des exports…' : 'Aucune analyse disponible'),
            if (!busy) ...[
              const SizedBox(height: 12),
              OutlinedButton.icon(
                onPressed: onRefresh,
                icon: const Icon(Icons.refresh),
                label: const Text('Réessayer'),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _OverviewTab extends StatelessWidget {
  const _OverviewTab({
    required this.data,
    required this.directory,
    required this.period,
  });
  final DashboardData data;
  final DirectoryGrant directory;
  final AnalysisPeriod period;

  @override
  Widget build(BuildContext context) {
    final incompleteSources = data.sources.where(
      (source) =>
          source.present &&
          !source.coverageComplete &&
          <String>{
            decisionsFile,
            pkpdFile,
            hormonitorEventsFile,
            hormonitorDailyFile,
          }.contains(source.name),
    );
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 28),
      children: [
        _FolderHeader(directory: directory, refreshedAtMs: data.generatedAtMs),
        const SizedBox(height: 12),
        const _SafetyNotice(compact: true),
        if (incompleteSources.isNotEmpty) ...[
          const SizedBox(height: 12),
          const _SectionCard(
            title: 'Couverture partielle',
            child: Text(
              'Au moins une source ne couvre pas entièrement la période. '
              'Les chiffres affichés décrivent uniquement les données '
              'disponibles ; consultez l’onglet Fichiers pour le détail.',
              style: TextStyle(
                color: Color(0xFFFFB56B),
                fontSize: 12,
                height: 1.35,
              ),
            ),
          ),
        ],
        const SizedBox(height: 18),
        Text(
          period.kind == AnalysisPeriodKind.day
              ? 'Analyse de la journée'
              : 'Analyse de la semaine',
          style: Theme.of(
            context,
          ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700),
        ),
        Text(period.label, style: const TextStyle(color: Color(0xFF9EB1BE))),
        const SizedBox(height: 12),
        if (!data.hasData) const _NoWindowData(),
        _MetricGrid(data: data, period: period),
        const SizedBox(height: 14),
        _SectionCard(
          title: 'Glycémie',
          subtitle:
              '${data.glucose.length} points exploitables dans la période',
          child:
              data.glucose.isEmpty
                  ? const _InlineEmpty(
                    'Aucune glycémie détectée dans la période sélectionnée.',
                  )
                  : GlucoseChart(
                    points: data.glucose,
                    startMs: data.windowStartMs,
                    endMs: data.windowEndMs,
                  ),
        ),
        const SizedBox(height: 14),
        _SectionCard(
          title: 'Répartition glycémique',
          subtitle: 'Calculée sur les échantillons disponibles',
          child: _TirBar(
            low: data.lowPct,
            inRange: data.tirPct,
            high: data.highPct,
          ),
        ),
        if (data.iobSeries.isNotEmpty || data.cobSeries.isNotEmpty) ...[
          const SizedBox(height: 14),
          _SectionCard(
            title: 'Insuline et glucides actifs',
            subtitle: 'IOB et COB estimés sur la période',
            child: MultiSeriesChart(
              series: [
                ChartSeries(
                  label: 'IOB',
                  unit: 'U',
                  color: const Color(0xFF67A8FF),
                  points: data.iobSeries,
                ),
                ChartSeries(
                  label: 'COB',
                  unit: 'g',
                  color: const Color(0xFF48D7C2),
                  points: data.cobSeries,
                ),
              ],
              startMs: data.windowStartMs,
              endMs: data.windowEndMs,
            ),
          ),
        ],
        if (data.smbSeries.isNotEmpty) ...[
          const SizedBox(height: 14),
          _SectionCard(
            title: 'Micro-bolus SMB',
            subtitle: 'Chaque barre est une dose délivrée',
            child: SmbBarChart(
              points: data.smbSeries,
              startMs: data.windowStartMs,
              endMs: data.windowEndMs,
            ),
          ),
        ],
        const SizedBox(height: 14),
        _DistributionCard(
          title: 'Actions décidées par AIMI',
          counts: data.decisionTypes,
          color: Theme.of(context).colorScheme.secondary,
          domain: LabelDomain.decision,
        ),
        if (data.auditorFollowupCount > 0) ...[
          const SizedBox(height: 14),
          _SectionCard(
            title: 'Suivis de l’auditeur',
            subtitle: '${data.auditorFollowupCount} observations',
            child: const Text(
              'Ces observations différées servent à vérifier une décision '
              'antérieure. Elles ne correspondent pas à une nouvelle '
              'commande de pompe et ne sont pas incluses dans les '
              'pourcentages des actions AIMI.',
              style: TextStyle(
                color: Color(0xFF9EB1BE),
                fontSize: 12,
                height: 1.35,
              ),
            ),
          ),
        ],
      ],
    );
  }
}

class _HormonitorTab extends StatelessWidget {
  const _HormonitorTab({
    required this.data,
    required this.preference,
    required this.onPreferenceChanged,
  });
  final DashboardData data;
  final HormoneTrackingPreference preference;
  final ValueChanged<HormoneTrackingPreference> onPreferenceChanged;

  @override
  Widget build(BuildContext context) {
    final hasHormonitor = data.hormonitorEventCount > 0;
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 14, 16, 28),
      children: [
        Text(
          'Hormonitor',
          style: Theme.of(
            context,
          ).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w700),
        ),
        const SizedBox(height: 6),
        Text(
          '${data.hormonitorEventCount} événements · couverture patient_story ${_pctText(data.patientStoryCoverage)}',
          style: const TextStyle(color: Color(0xFF9EB1BE)),
        ),
        const SizedBox(height: 16),
        if (!hasHormonitor)
          const _SectionCard(
            title: 'Pas de données Hormonitor dans cette période',
            child: _InlineEmpty(
              'Vérifiez la présence de AIMI_HORMONITOR_event_stream_v1.jsonl dans Documents/AAPS.',
            ),
          )
        else ...[
          _DistributionCard(
            title: 'Modes patient',
            counts: data.patientModes,
            color: const Color(0xFF48D7C2),
            domain: LabelDomain.patientMode,
          ),
          const SizedBox(height: 14),
          _DistributionCard(
            title: 'Contexte physiologique estimé',
            counts: data.physioStates,
            color: const Color(0xFF67A8FF),
            domain: LabelDomain.physioState,
            emptyMessage:
                'Aucun état physiologique n’est présent dans cette période.',
          ),
          const SizedBox(height: 14),
          const _SafetyExplanation(),
          const SizedBox(height: 10),
          _DistributionCard(
            title: 'Protection préventive contre l’hypoglycémie',
            counts: data.safetyGates,
            color: const Color(0xFFFF9D6C),
            domain: LabelDomain.safetyGate,
          ),
          const SizedBox(height: 14),
          _HormoneSection(
            counts: data.cyclePhases,
            preference: preference,
            onChanged: onPreferenceChanged,
          ),
        ],
        if (!hasHormonitor) ...[
          const SizedBox(height: 14),
          _HormoneSection(
            counts: data.cyclePhases,
            preference: preference,
            onChanged: onPreferenceChanged,
          ),
        ],
      ],
    );
  }
}

class _TimelineTab extends StatefulWidget {
  const _TimelineTab({required this.data, required this.period});
  final DashboardData data;
  final AnalysisPeriod period;

  @override
  State<_TimelineTab> createState() => _TimelineTabState();
}

class _TimelineTabState extends State<_TimelineTab> {
  final TextEditingController _searchController = TextEditingController();
  String _query = '';
  final Set<String> _decisionFilter = <String>{};
  final Set<String> _modeFilter = <String>{};
  final Set<String> _safetyFilter = <String>{};

  @override
  void didUpdateWidget(covariant _TimelineTab oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.data != widget.data) {
      _searchController.clear();
      _query = '';
      _decisionFilter.clear();
      _modeFilter.clear();
      _safetyFilter.clear();
    }
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  List<TimelineEntry> get _filteredEntries {
    return widget.data.timeline.where((entry) {
      if (_decisionFilter.isNotEmpty &&
          !_decisionFilter.contains(entry.decision)) {
        return false;
      }
      if (_modeFilter.isNotEmpty &&
          (entry.patientMode == null ||
              !_modeFilter.contains(entry.patientMode))) {
        return false;
      }
      if (_safetyFilter.isNotEmpty &&
          (entry.safetyGate == null ||
              !_safetyFilter.contains(entry.safetyGate))) {
        return false;
      }
      if (_query.isEmpty) return true;
      final haystack =
          '${labelFor(LabelDomain.decision, entry.decision)} ${entry.narrative ?? ''}'
              .toLowerCase();
      return haystack.contains(_query);
    }).toList();
  }

  @override
  Widget build(BuildContext context) {
    final entries = _filteredEntries;
    final decisionOptions =
        widget.data.timeline.map((entry) => entry.decision).toSet().toList()
          ..sort();
    final modeOptions =
        widget.data.timeline
            .map((entry) => entry.patientMode)
            .whereType<String>()
            .toSet()
            .toList()
          ..sort();
    final safetyOptions =
        widget.data.timeline
            .map((entry) => entry.safetyGate)
            .whereType<String>()
            .toSet()
            .toList()
          ..sort();
    final hasActiveFilters =
        _query.isNotEmpty ||
        _decisionFilter.isNotEmpty ||
        _modeFilter.isNotEmpty ||
        _safetyFilter.isNotEmpty;
    final hasFilterOptions =
        decisionOptions.isNotEmpty ||
        modeOptions.isNotEmpty ||
        safetyOptions.isNotEmpty;

    return Column(
      children: [
        if (widget.data.timeline.isNotEmpty)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
            child: TextField(
              controller: _searchController,
              onChanged:
                  (value) =>
                      setState(() => _query = value.trim().toLowerCase()),
              decoration: InputDecoration(
                hintText: 'Rechercher une décision ou une explication…',
                isDense: true,
                filled: true,
                fillColor: const Color(0xFF13212B),
                prefixIcon: const Icon(Icons.search_rounded, size: 20),
                suffixIcon:
                    _query.isEmpty
                        ? null
                        : IconButton(
                          icon: const Icon(Icons.close_rounded, size: 18),
                          onPressed: () {
                            _searchController.clear();
                            setState(() => _query = '');
                          },
                        ),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                  borderSide: const BorderSide(color: Color(0xFF263943)),
                ),
              ),
            ),
          ),
        if (hasFilterOptions)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 10, 16, 0),
            child: SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(
                children: [
                  ..._filterChips(
                    decisionOptions,
                    _decisionFilter,
                    LabelDomain.decision,
                  ),
                  ..._filterChips(
                    modeOptions,
                    _modeFilter,
                    LabelDomain.patientMode,
                  ),
                  ..._filterChips(
                    safetyOptions,
                    _safetyFilter,
                    LabelDomain.safetyGate,
                  ),
                ],
              ),
            ),
          ),
        Expanded(
          child: ListView.separated(
            padding: const EdgeInsets.fromLTRB(16, 14, 16, 28),
            itemCount: entries.isEmpty ? 2 : entries.length + 1,
            separatorBuilder: (_, _) => const SizedBox(height: 8),
            itemBuilder: (context, index) {
              if (index == 0) {
                return Padding(
                  padding: const EdgeInsets.only(bottom: 6),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Journal des décisions',
                        style: Theme.of(
                          context,
                        ).textTheme.headlineSmall?.copyWith(
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      const SizedBox(height: 5),
                      Text(
                        hasActiveFilters
                            ? '${entries.length} résultat(s) sur ${widget.data.timeline.length}'
                            : '${widget.period.label} · ${widget.data.timeline.length >= timelineCapacity ? '$timelineCapacity événements les plus récents' : 'événements les plus récents'} en premier',
                        style: const TextStyle(color: Color(0xFF9EB1BE)),
                      ),
                    ],
                  ),
                );
              }
              if (entries.isEmpty) {
                return _InlineEmpty(
                  widget.data.timeline.isEmpty
                      ? 'Aucune décision dans la période sélectionnée.'
                      : 'Aucune décision ne correspond à la recherche ou aux filtres.',
                );
              }
              return _TimelineTile(entry: entries[index - 1]);
            },
          ),
        ),
      ],
    );
  }

  List<Widget> _filterChips(
    List<String> options,
    Set<String> selected,
    LabelDomain domain,
  ) {
    return options
        .map(
          (option) => Padding(
            padding: const EdgeInsets.only(right: 8),
            child: FilterChip(
              label: Text(
                labelFor(domain, option),
                style: const TextStyle(fontSize: 12),
              ),
              selected: selected.contains(option),
              visualDensity: VisualDensity.compact,
              onSelected:
                  (value) => setState(() {
                    if (value) {
                      selected.add(option);
                    } else {
                      selected.remove(option);
                    }
                  }),
            ),
          ),
        )
        .toList();
  }
}

class _FilesTab extends StatelessWidget {
  const _FilesTab({
    required this.data,
    required this.directory,
    required this.onChooseDirectory,
  });
  final DashboardData data;
  final DirectoryGrant directory;
  final VoidCallback onChooseDirectory;

  @override
  Widget build(BuildContext context) {
    final statuses =
        data.sources.toList()..sort((a, b) {
          if (a.present != b.present) return a.present ? -1 : 1;
          return a.name.compareTo(b.name);
        });
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 14, 16, 28),
      children: [
        Text(
          'Sources locales',
          style: Theme.of(
            context,
          ).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w700),
        ),
        const SizedBox(height: 12),
        _SectionCard(
          title: directory.name,
          subtitle: 'Autorisation Android persistante en lecture seule',
          trailing: TextButton.icon(
            onPressed: onChooseDirectory,
            icon: const Icon(Icons.swap_horiz),
            label: const Text('Changer'),
          ),
          child: const Text(
            'Les fichiers sources ne sont jamais modifiés. Android extrait uniquement la période demandée dans le cache privé ; le journal complet n’est jamais copié en entier.',
          ),
        ),
        const SizedBox(height: 14),
        ...statuses.map(
          (status) => Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: _SourceTile(status: status),
          ),
        ),
      ],
    );
  }
}

class _FolderHeader extends StatelessWidget {
  const _FolderHeader({required this.directory, required this.refreshedAtMs});
  final DirectoryGrant directory;
  final int refreshedAtMs;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Container(
          width: 40,
          height: 40,
          decoration: BoxDecoration(
            color: const Color(0xFF48D7C2).withValues(alpha: 0.12),
            borderRadius: BorderRadius.circular(12),
          ),
          child: const Icon(Icons.folder_rounded, color: Color(0xFF48D7C2)),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                directory.name,
                style: const TextStyle(fontWeight: FontWeight.w600),
              ),
              Text(
                'Actualisé ${_formatDateTime(refreshedAtMs)}',
                style: const TextStyle(fontSize: 12, color: Color(0xFF9EB1BE)),
              ),
            ],
          ),
        ),
        const _StatusPill(label: 'Local', color: Color(0xFF48D7C2)),
      ],
    );
  }
}

class _MetricGrid extends StatelessWidget {
  const _MetricGrid({required this.data, required this.period});
  final DashboardData data;
  final AnalysisPeriod period;

  @override
  Widget build(BuildContext context) {
    final isCurrentPeriod = period.isCurrentAt(DateTime.now());
    final metrics = <_MetricValue>[
      _MetricValue(
        isCurrentPeriod
            ? 'Dernière glycémie'
            : 'Dernière glycémie de la période',
        _number(data.latestBgMgdl, 0),
        'mg/dL',
        Icons.water_drop_outlined,
        const Color(0xFF67A8FF),
      ),
      _MetricValue(
        'Dans la cible',
        _number(data.tirPct, 1),
        '%',
        Icons.adjust_rounded,
        const Color(0xFF48D7C2),
      ),
      _MetricValue(
        'SMB cumulés',
        _number(data.totalSmbU, 2),
        'U',
        Icons.bolt_rounded,
        const Color(0xFFF5B84B),
      ),
      _MetricValue(
        isCurrentPeriod ? 'IOB récent' : 'Dernier IOB de la période',
        _number(data.latestIobU, 2),
        'U',
        Icons.hourglass_bottom_rounded,
        const Color(0xFFFF9D6C),
      ),
      _MetricValue(
        'Décisions',
        data.decisionCount.toString(),
        'ticks',
        Icons.psychology_alt_outlined,
        const Color(0xFFD79CFF),
      ),
      _MetricValue(
        period.kind == AnalysisPeriodKind.week
            ? 'TDD moyen / jour'
            : 'TDD de la journée',
        _number(data.dailyTddU, 1),
        'U',
        Icons.insights_rounded,
        const Color(0xFF76D6FF),
      ),
    ];
    return LayoutBuilder(
      builder: (context, constraints) {
        final columns = constraints.maxWidth >= 700 ? 3 : 2;
        final width = (constraints.maxWidth - (columns - 1) * 10) / columns;
        return Wrap(
          spacing: 10,
          runSpacing: 10,
          children:
              metrics
                  .map(
                    (metric) => SizedBox(
                      width: width,
                      child: _MetricCard(metric: metric),
                    ),
                  )
                  .toList(),
        );
      },
    );
  }
}

class _MetricValue {
  const _MetricValue(this.label, this.value, this.unit, this.icon, this.color);
  final String label;
  final String value;
  final String unit;
  final IconData icon;
  final Color color;
}

class _MetricCard extends StatelessWidget {
  const _MetricCard({required this.metric});
  final _MetricValue metric;

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 116,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: const Color(0xFF13212B),
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: const Color(0xFF263943)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(metric.icon, size: 20, color: metric.color),
          const Spacer(),
          Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Flexible(
                child: Text(
                  metric.value,
                  style: const TextStyle(
                    fontSize: 25,
                    fontWeight: FontWeight.w700,
                    height: 1,
                  ),
                ),
              ),
              if (metric.unit.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(left: 5, bottom: 2),
                  child: Text(
                    metric.unit,
                    style: const TextStyle(
                      fontSize: 11,
                      color: Color(0xFF9EB1BE),
                    ),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 7),
          Text(
            metric.label,
            style: const TextStyle(fontSize: 12, color: Color(0xFF9EB1BE)),
          ),
        ],
      ),
    );
  }
}

class _SectionCard extends StatelessWidget {
  const _SectionCard({
    required this.title,
    required this.child,
    this.subtitle,
    this.trailing,
  });
  final String title;
  final String? subtitle;
  final Widget? trailing;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: const Color(0xFF13212B),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: const Color(0xFF263943)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style: const TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    if (subtitle != null) ...[
                      const SizedBox(height: 3),
                      Text(
                        subtitle!,
                        style: const TextStyle(
                          fontSize: 12,
                          color: Color(0xFF9EB1BE),
                        ),
                      ),
                    ],
                  ],
                ),
              ),
              if (trailing != null) trailing!,
            ],
          ),
          const SizedBox(height: 16),
          child,
        ],
      ),
    );
  }
}

class _DistributionCard extends StatelessWidget {
  const _DistributionCard({
    required this.title,
    required this.counts,
    required this.color,
    required this.domain,
    this.emptyMessage = 'Aucune donnée structurée disponible.',
  });
  final String title;
  final Map<String, int> counts;
  final Color color;
  final LabelDomain domain;
  final String emptyMessage;

  @override
  Widget build(BuildContext context) {
    final entries = counts.entries.take(8).toList();
    final total = counts.values.fold<int>(0, (sum, value) => sum + value);
    return _SectionCard(
      title: title,
      subtitle: total == 0 ? null : '$total observations',
      child:
          entries.isEmpty
              ? _InlineEmpty(emptyMessage)
              : Column(
                children:
                    entries.map((entry) {
                      final share = total == 0 ? 0.0 : entry.value / total;
                      return Padding(
                        padding: const EdgeInsets.only(bottom: 12),
                        child: Column(
                          children: [
                            Row(
                              children: [
                                Expanded(
                                  child: Text(
                                    labelFor(domain, entry.key),
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: const TextStyle(fontSize: 13),
                                  ),
                                ),
                                Text(
                                  '${(share * 100).toStringAsFixed(0)} %',
                                  style: const TextStyle(
                                    fontSize: 12,
                                    color: Color(0xFFB8C7D0),
                                  ),
                                ),
                                const SizedBox(width: 8),
                                SizedBox(
                                  width: 28,
                                  child: Text(
                                    '${entry.value}',
                                    textAlign: TextAlign.right,
                                    style: const TextStyle(
                                      fontSize: 11,
                                      color: Color(0xFF7F929E),
                                    ),
                                  ),
                                ),
                              ],
                            ),
                            if (labelExplanation(domain, entry.key) !=
                                null) ...[
                              const SizedBox(height: 5),
                              Align(
                                alignment: Alignment.centerLeft,
                                child: Text(
                                  labelExplanation(domain, entry.key)!,
                                  style: const TextStyle(
                                    fontSize: 10.5,
                                    height: 1.3,
                                    color: Color(0xFF8FA2AE),
                                  ),
                                ),
                              ),
                            ],
                            const SizedBox(height: 6),
                            ClipRRect(
                              borderRadius: BorderRadius.circular(4),
                              child: LinearProgressIndicator(
                                value: share,
                                minHeight: 5,
                                color: color,
                                backgroundColor: const Color(0xFF24343E),
                              ),
                            ),
                          ],
                        ),
                      );
                    }).toList(),
              ),
    );
  }
}

class _SafetyExplanation extends StatelessWidget {
  const _SafetyExplanation();

  @override
  Widget build(BuildContext context) => const _InlineEmpty(
    'État de la protection LGS évalué à chaque calcul de boucle. Les '
    'pourcentages représentent des observations, pas une durée ni un nombre '
    'de doses. « Aucune protection déclenchée » ne résume pas toutes les '
    'sécurités AIMI.',
  );
}

class _HormoneSection extends StatelessWidget {
  const _HormoneSection({
    required this.counts,
    required this.preference,
    required this.onChanged,
  });

  final Map<String, int> counts;
  final HormoneTrackingPreference preference;
  final ValueChanged<HormoneTrackingPreference> onChanged;

  @override
  Widget build(BuildContext context) {
    final hasPhase = counts.isNotEmpty;
    final message = switch (preference) {
      HormoneTrackingPreference.notApplicable when hasPhase =>
        'Des phases sont présentes dans les exports malgré le choix local '
            '« Non applicable ». Elles restent affichées sans modifier AAPS.',
      HormoneTrackingPreference.notApplicable =>
        'Suivi du cycle : non applicable. Ce choix reste uniquement sur ce téléphone.',
      HormoneTrackingPreference.enabledInAaps when !hasPhase =>
        'Phase indisponible : vérifiez l’activation de WCycle et le premier jour du cycle dans AAPS.',
      HormoneTrackingPreference.unspecified when !hasPhase =>
        'Aucune phase hormonale exploitable. Le suivi peut être désactivé, non applicable ou incomplet dans AAPS.',
      _ => 'Phases transmises par AAPS pour la période sélectionnée.',
    };
    return _SectionCard(
      title: 'Suivi du cycle',
      subtitle: 'Préférence d’affichage locale, sans sexe, genre ni âge',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          DropdownButtonFormField<HormoneTrackingPreference>(
            initialValue: preference,
            decoration: const InputDecoration(
              labelText: 'Affichage du suivi du cycle',
              border: OutlineInputBorder(),
              isDense: true,
            ),
            items: const [
              DropdownMenuItem(
                value: HormoneTrackingPreference.unspecified,
                child: Text('Non renseigné'),
              ),
              DropdownMenuItem(
                value: HormoneTrackingPreference.notApplicable,
                child: Text('Non applicable'),
              ),
              DropdownMenuItem(
                value: HormoneTrackingPreference.enabledInAaps,
                child: Text('Suivi activé dans AAPS'),
              ),
            ],
            onChanged: (value) {
              if (value != null) onChanged(value);
            },
          ),
          const SizedBox(height: 12),
          Text(
            message,
            style: const TextStyle(
              fontSize: 12,
              height: 1.35,
              color: Color(0xFF9EB1BE),
            ),
          ),
          if (hasPhase) ...[
            const SizedBox(height: 14),
            _DistributionCard(
              title: 'Phases hormonales observées',
              counts: counts,
              color: const Color(0xFFD79CFF),
              domain: LabelDomain.cyclePhase,
            ),
          ],
        ],
      ),
    );
  }
}

class _TirBar extends StatelessWidget {
  const _TirBar({required this.low, required this.inRange, required this.high});
  final double? low;
  final double? inRange;
  final double? high;

  @override
  Widget build(BuildContext context) {
    if (low == null || inRange == null || high == null) {
      return const _InlineEmpty(
        'Pas assez de glycémies pour calculer la répartition.',
      );
    }
    return Column(
      children: [
        ClipRRect(
          borderRadius: BorderRadius.circular(8),
          child: SizedBox(
            height: 18,
            child: Row(
              children: [
                if (low! > 0)
                  Expanded(
                    flex: math.max(1, low!.round()),
                    child: Container(color: const Color(0xFFFF6B78)),
                  ),
                if (inRange! > 0)
                  Expanded(
                    flex: math.max(1, inRange!.round()),
                    child: Container(color: const Color(0xFF48D7C2)),
                  ),
                if (high! > 0)
                  Expanded(
                    flex: math.max(1, high!.round()),
                    child: Container(color: const Color(0xFFF5B84B)),
                  ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 13),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            _Legend(
              color: const Color(0xFFFF6B78),
              label: '< 70',
              value: '${low!.toStringAsFixed(1)} %',
            ),
            _Legend(
              color: const Color(0xFF48D7C2),
              label: '70–180',
              value: '${inRange!.toStringAsFixed(1)} %',
            ),
            _Legend(
              color: const Color(0xFFF5B84B),
              label: '> 180',
              value: '${high!.toStringAsFixed(1)} %',
            ),
          ],
        ),
      ],
    );
  }
}

class _Legend extends StatelessWidget {
  const _Legend({
    required this.color,
    required this.label,
    required this.value,
  });
  final Color color;
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Container(
              width: 7,
              height: 7,
              decoration: BoxDecoration(color: color, shape: BoxShape.circle),
            ),
            const SizedBox(width: 5),
            Text(
              label,
              style: const TextStyle(fontSize: 11, color: Color(0xFF9EB1BE)),
            ),
          ],
        ),
        const SizedBox(height: 3),
        Text(value, style: const TextStyle(fontWeight: FontWeight.w600)),
      ],
    );
  }
}

class _TimelineTile extends StatelessWidget {
  const _TimelineTile({required this.entry});
  final TimelineEntry entry;

  @override
  Widget build(BuildContext context) {
    final hasSmb = entry.smbU > 0.0001;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: () => showTimelineDetailSheet(context, entry),
        child: Container(
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
            color: const Color(0xFF13212B),
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: const Color(0xFF263943)),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
          Container(
            width: 44,
            padding: const EdgeInsets.symmetric(vertical: 7),
            decoration: BoxDecoration(
              color: (hasSmb
                      ? const Color(0xFFF5B84B)
                      : const Color(0xFF48D7C2))
                  .withValues(alpha: 0.12),
              borderRadius: BorderRadius.circular(10),
            ),
            child: Text(
              _formatTime(entry.timestampMs),
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w700,
                color:
                    hasSmb ? const Color(0xFFF5B84B) : const Color(0xFF48D7C2),
              ),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  labelFor(LabelDomain.decision, entry.decision),
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
                const SizedBox(height: 5),
                Wrap(
                  spacing: 10,
                  runSpacing: 4,
                  children: [
                    if (entry.bgMgdl != null)
                      Text(
                        '${entry.bgMgdl!.toStringAsFixed(0)} mg/dL',
                        style: const TextStyle(
                          fontSize: 12,
                          color: Color(0xFFB8C7D0),
                        ),
                      ),
                    if (entry.iobU != null)
                      Text(
                        'IOB ${entry.iobU!.toStringAsFixed(2)} U',
                        style: const TextStyle(
                          fontSize: 12,
                          color: Color(0xFFB8C7D0),
                        ),
                      ),
                    if (hasSmb)
                      Text(
                        'SMB ${entry.smbU.toStringAsFixed(2)} U',
                        style: const TextStyle(
                          fontSize: 12,
                          color: Color(0xFFF5B84B),
                        ),
                      ),
                  ],
                ),
                if (entry.patientMode != null || entry.safetyGate != null) ...[
                  const SizedBox(height: 7),
                  Text(
                    [
                      if (entry.patientMode != null)
                        labelFor(LabelDomain.patientMode, entry.patientMode!),
                      if (entry.safetyGate != null)
                        labelFor(LabelDomain.safetyGate, entry.safetyGate!),
                    ].join(' · '),
                    style: const TextStyle(
                      fontSize: 11,
                      color: Color(0xFF7F929E),
                    ),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
                ],
              ],
            ),
          ),
        ],
          ),
        ),
      ),
    );
  }
}

class _SourceTile extends StatelessWidget {
  const _SourceTile({required this.status});
  final SourceStatus status;

  @override
  Widget build(BuildContext context) {
    final color =
        status.present ? const Color(0xFF48D7C2) : const Color(0xFF71838E);
    return Container(
      padding: const EdgeInsets.all(13),
      decoration: BoxDecoration(
        color: const Color(0xFF13212B),
        borderRadius: BorderRadius.circular(15),
        border: Border.all(color: const Color(0xFF263943)),
      ),
      child: Row(
        children: [
          Icon(
            status.present
                ? Icons.check_circle_rounded
                : Icons.remove_circle_outline,
            color: color,
            size: 21,
          ),
          const SizedBox(width: 11),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  _sourceLabel(status.name),
                  style: const TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                  ),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                const SizedBox(height: 3),
                Text(
                  status.present
                      ? '${_formatBytes(status.sourceSize)} · ${status.recordsInWindow} lignes/objets dans la période'
                      : 'Non trouvé dans le dossier',
                  style: const TextStyle(
                    fontSize: 11,
                    color: Color(0xFF8FA2AE),
                  ),
                ),
                if (status.present)
                  Text(
                    _sourceCoverage(status),
                    style: TextStyle(
                      fontSize: 10.5,
                      color:
                          status.coverageComplete
                              ? const Color(0xFF7F929E)
                              : const Color(0xFFFFB56B),
                    ),
                  ),
                if (status.malformedLines > 0)
                  Text(
                    '${status.malformedLines} lignes illisibles ignorées',
                    style: const TextStyle(
                      fontSize: 11,
                      color: Color(0xFFFF9D6C),
                    ),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _SafetyNotice extends StatelessWidget {
  const _SafetyNotice({this.compact = false});
  final bool compact;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: EdgeInsets.all(compact ? 12 : 16),
      decoration: BoxDecoration(
        color: const Color(0xFF67A8FF).withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: const Color(0xFF67A8FF).withValues(alpha: 0.25),
        ),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(Icons.shield_outlined, size: 20, color: Color(0xFF76D6FF)),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              compact
                  ? 'Observation uniquement : aucune commande de pompe et aucune modification des exports.'
                  : 'AIMI Viewer fonctionne hors ligne et en lecture seule. Il ne pilote pas la pompe et ne remplace pas les décisions thérapeutiques prises avec votre équipe médicale.',
              style: const TextStyle(
                fontSize: 12,
                height: 1.35,
                color: Color(0xFFB8C7D0),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _ErrorBanner extends StatelessWidget {
  const _ErrorBanner({required this.message, required this.onRetry});
  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: const Color(0xFF5C2530),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 9, 8, 9),
        child: Row(
          children: [
            const Icon(Icons.warning_amber_rounded, size: 19),
            const SizedBox(width: 9),
            Expanded(
              child: Text(message, style: const TextStyle(fontSize: 12)),
            ),
            TextButton(onPressed: onRetry, child: const Text('Réessayer')),
          ],
        ),
      ),
    );
  }
}

class _NoWindowData extends StatelessWidget {
  const _NoWindowData();
  @override
  Widget build(BuildContext context) => const Padding(
    padding: EdgeInsets.only(bottom: 12),
    child: _InlineEmpty(
      'Les fichiers ont été trouvés, mais aucune donnée horodatée ne se situe dans la période sélectionnée.',
    ),
  );
}

class _InlineEmpty extends StatelessWidget {
  const _InlineEmpty(this.message);
  final String message;

  @override
  Widget build(BuildContext context) => Container(
    width: double.infinity,
    padding: const EdgeInsets.all(14),
    decoration: BoxDecoration(
      color: const Color(0xFF0E1A22),
      borderRadius: BorderRadius.circular(12),
    ),
    child: Text(
      message,
      style: const TextStyle(
        fontSize: 12,
        height: 1.35,
        color: Color(0xFF9EB1BE),
      ),
    ),
  );
}

class _StatusPill extends StatelessWidget {
  const _StatusPill({required this.label, required this.color});
  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
    decoration: BoxDecoration(
      color: color.withValues(alpha: 0.12),
      borderRadius: BorderRadius.circular(20),
    ),
    child: Text(
      label,
      style: TextStyle(fontSize: 10, fontWeight: FontWeight.w700, color: color),
    ),
  );
}

class GlucoseChart extends StatelessWidget {
  const GlucoseChart({
    super.key,
    required this.points,
    required this.startMs,
    required this.endMs,
  });
  final List<GlucosePoint> points;
  final int startMs;
  final int endMs;

  @override
  Widget build(BuildContext context) => SizedBox(
    height: 230,
    width: double.infinity,
    child: CustomPaint(
      painter: _GlucosePainter(points: points, startMs: startMs, endMs: endMs),
    ),
  );
}

class _GlucosePainter extends CustomPainter {
  _GlucosePainter({
    required this.points,
    required this.startMs,
    required this.endMs,
  });
  final List<GlucosePoint> points;
  final int startMs;
  final int endMs;

  @override
  void paint(Canvas canvas, Size size) {
    if (points.isEmpty || endMs <= startMs) return;
    const left = 38.0;
    const top = 8.0;
    const right = 8.0;
    const bottom = 24.0;
    final rect = Rect.fromLTRB(
      left,
      top,
      size.width - right,
      size.height - bottom,
    );
    final observedMax = points.map((point) => point.valueMgdl).reduce(math.max);
    final observedMin = points.map((point) => point.valueMgdl).reduce(math.min);
    final yMin = math.min(40.0, observedMin - 10);
    final yMax = math.max(250.0, observedMax + 20);
    double x(int timestamp) =>
        rect.left + (timestamp - startMs) / (endMs - startMs) * rect.width;
    double y(double value) =>
        rect.bottom - (value - yMin) / (yMax - yMin) * rect.height;

    final targetTop = y(180).clamp(rect.top, rect.bottom).toDouble();
    final targetBottom = y(70).clamp(rect.top, rect.bottom).toDouble();
    canvas.drawRect(
      Rect.fromLTRB(rect.left, targetTop, rect.right, targetBottom),
      Paint()..color = const Color(0xFF48D7C2).withValues(alpha: 0.08),
    );

    for (final value in <double>[70, 180, 250]) {
      if (value > yMax) continue;
      final dy = y(value);
      canvas.drawLine(
        Offset(rect.left, dy),
        Offset(rect.right, dy),
        Paint()
          ..color = const Color(0xFF31424C)
          ..strokeWidth = 1,
      );
      _paintLabel(
        canvas,
        value.toInt().toString(),
        Offset(0, dy - 7),
        width: left - 5,
      );
    }

    final path = Path();
    var started = false;
    for (final point in points) {
      final dx = x(point.timestampMs).clamp(rect.left, rect.right).toDouble();
      final dy = y(point.valueMgdl).clamp(rect.top, rect.bottom).toDouble();
      if (!started) {
        path.moveTo(dx, dy);
        started = true;
      } else {
        path.lineTo(dx, dy);
      }
    }
    canvas.drawPath(
      path,
      Paint()
        ..color = const Color(0xFF67A8FF)
        ..strokeWidth = 2.2
        ..style = PaintingStyle.stroke
        ..strokeCap = StrokeCap.round
        ..strokeJoin = StrokeJoin.round,
    );
    final last = points.last;
    canvas.drawCircle(
      Offset(
        x(last.timestampMs).clamp(rect.left, rect.right).toDouble(),
        y(last.valueMgdl).clamp(rect.top, rect.bottom).toDouble(),
      ),
      4.2,
      Paint()..color = const Color(0xFF76D6FF),
    );
    _paintLabel(
      canvas,
      _formatShortDate(startMs),
      Offset(rect.left, rect.bottom + 7),
      width: 55,
      align: TextAlign.left,
    );
    _paintLabel(
      canvas,
      _formatShortDate(endMs - 1),
      Offset(rect.right - 65, rect.bottom + 7),
      width: 65,
      align: TextAlign.right,
    );
  }

  void _paintLabel(
    Canvas canvas,
    String text,
    Offset offset, {
    required double width,
    TextAlign align = TextAlign.right,
  }) {
    final painter = TextPainter(
      text: TextSpan(
        text: text,
        style: const TextStyle(fontSize: 10, color: Color(0xFF7F929E)),
      ),
      textDirection: TextDirection.ltr,
      textAlign: align,
    )..layout(maxWidth: width);
    painter.paint(canvas, offset);
  }

  @override
  bool shouldRepaint(covariant _GlucosePainter oldDelegate) =>
      oldDelegate.points != points ||
      oldDelegate.startMs != startMs ||
      oldDelegate.endMs != endMs;
}

String _number(double? value, int decimals) =>
    value == null || !value.isFinite ? '—' : value.toStringAsFixed(decimals);

String _pctText(double? value) =>
    value == null ? '—' : '${value.toStringAsFixed(0)} %';

String _formatTime(int timestampMs) {
  final date = DateTime.fromMillisecondsSinceEpoch(timestampMs);
  return '${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}';
}

String _formatDateTime(int timestampMs) {
  final date = DateTime.fromMillisecondsSinceEpoch(timestampMs);
  return '${date.day.toString().padLeft(2, '0')}/${date.month.toString().padLeft(2, '0')} à ${_formatTime(timestampMs)}';
}

String _formatBytes(int bytes) {
  if (bytes <= 0) return '0 o';
  if (bytes < 1024) return '$bytes o';
  if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} Ko';
  return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} Mo';
}

String _sourceLabel(String name) {
  switch (name) {
    case decisionsFile:
      return 'Décisions AIMI';
    case pkpdFile:
      return 'Observations PK/PD';
    case hormonitorEventsFile:
      return 'Hormonitor · événements';
    case hormonitorDailyFile:
      return 'Hormonitor · résultats journaliers';
    case hormonitorQaFile:
      return 'Hormonitor · qualité du dataset';
    case hormonitorShadowFile:
      return 'Hormonitor · contributions shadow';
    case hormonitorBlackboxFile:
      return 'Hormonitor · blackbox de boucle';
    default:
      return name;
  }
}

String _formatShortDate(int timestampMs) {
  final date = DateTime.fromMillisecondsSinceEpoch(timestampMs);
  return '${date.day.toString().padLeft(2, '0')}/'
      '${date.month.toString().padLeft(2, '0')}';
}

String _sourceCoverage(SourceStatus status) {
  if (status.extractionMode == 'metadata_only') {
    return 'présent · métadonnées uniquement, non utilisé pour les indicateurs';
  }
  final sourceHint =
      status.sourceName == decisions24hSourceFile
          ? 'repli export 24 h'
          : status.extractionMode.replaceAll('_', ' ');
  final start = status.coverageStartMs;
  final end = status.coverageEndMs;
  final range =
      start == null || end == null
          ? 'plage non fournie'
          : '${_formatDateTime(start)} → ${_formatDateTime(end - 1)}';
  final quality =
      status.coverageComplete ? 'couverture complète' : 'couverture partielle';
  return '$quality · $range · $sourceHint';
}
