import 'package:flutter/material.dart';
import 'package:sheltr_core/sheltr_core.dart';

import '../format_passage.dart';
import '../passage_view.dart';
import '../scope.dart';
import '../speech.dart';

/// Home: ask a question, or open search narrowed to one hazard.
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final _controller = TextEditingController();
  final _speech = SheltrSpeech();
  var _speechUnavailable = false;
  String? _hazard;

  @override
  void dispose() {
    _controller.dispose();
    _speech.stop();
    super.dispose();
  }

  void _ask({String? query, String? hazard}) {
    final trimmed = (query ?? _controller.text).trim();
    if (trimmed.isNotEmpty) {
      Navigator.of(context).pushNamed(
        '/answer',
        arguments: {
          'text': trimmed,
          if (hazard != null) 'hazard': hazard,
        },
      );
      return;
    }
    Navigator.of(context).pushNamed(
      '/search',
      arguments: {
        if (hazard != null) 'hazard': hazard,
      },
    );
  }

  Future<void> _readAloud(String text) async {
    try {
      await _speech.speak(formatPassage(text));
    } on Exception {
      if (!mounted) {
        return;
      }
      setState(() => _speechUnavailable = true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scope = SheltrScope.of(context);
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final hazard = _hazard;
    final steps = [
      for (final step in _citedSteps(scope.store))
        if (step.hazard == hazard) step,
    ];
    return SheltrPageBackground(
      child: ListView(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
        children: [
          Semantics(
            header: true,
            headingLevel: 1,
            child: Text(
              scope.t('home_title'),
              style: theme.textTheme.headlineMedium,
            ),
          ),
          const SizedBox(height: 10),
          Text(
            scope.t('home_intro'),
            style: theme.textTheme.bodyLarge?.copyWith(
              color: scheme.onSurface.withValues(alpha: 0.82),
            ),
          ),
          const SizedBox(height: 16),
          const CountryLine(),
          const SizedBox(height: 20),
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: SheltrTextField(
                  label: scope.t('home_search_label'),
                  hint: scope.t('home_search_placeholder'),
                  controller: _controller,
                  onSubmitted: (value) => _ask(query: value),
                ),
              ),
              const SizedBox(width: 10),
              FilledButton(
                onPressed: () => _ask(query: _controller.text),
                child: Text(scope.t('search_submit_description')),
              ),
            ],
          ),
          if (hazard != null && steps.isEmpty) ...[
            const SizedBox(height: 20),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      scope.t('hazard_empty', {
                        'p1': scope.t('hazard_${hazard.toLowerCase()}'),
                      }),
                      style: theme.textTheme.bodyLarge,
                    ),
                    const SizedBox(height: 12),
                    FilledButton(
                      onPressed: () => _ask(hazard: hazard),
                      child: Text(scope.t('hazard_search_instead')),
                    ),
                  ],
                ),
              ),
            ),
          ],
          if (steps.isNotEmpty) ...[
            const SizedBox(height: 24),
            ..._cardSections(context, scope, steps),
          ],
          const SizedBox(height: 28),
          Semantics(
            header: true,
            headingLevel: 2,
            child: Text(
              scope.t('hazard_section'),
              style: theme.textTheme.titleLarge,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            scope.t('hazard_section_hint'),
            style: theme.textTheme.bodyMedium?.copyWith(
              color: scheme.onSurface.withValues(alpha: 0.75),
            ),
          ),
          const SizedBox(height: 14),
          _HazardGrid(
            scope: scope,
            onChosen: (chosen) => setState(() => _hazard = chosen),
          ),
        ],
      ),
    );
  }

  List<Widget> _cardSections(
    BuildContext context,
    SheltrScope scope,
    List<_CitedStep> steps,
  ) {
    final groups = <String, List<_CitedStep>>{};
    for (final step in steps) {
      groups.putIfAbsent(step.hazard, () => []).add(step);
    }
    final children = <Widget>[];
    for (final entry in groups.entries) {
      children.add(
        Text(
          scope.t('hazard_${entry.key.toLowerCase()}'),
          style: Theme.of(context).textTheme.titleLarge,
        ),
      );
      for (final step in entry.value) {
        final freshness = _stepFreshness(scope, step.document);
        final warningKey = stalenessWarningKey(freshness);
        children.add(const SizedBox(height: 12));
        children.add(
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    step.title,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  if (freshness.clockUnsure) ...[
                    const SizedBox(height: 8),
                    Text(scope.t('answer_clock_unsure')),
                  ],
                  if (warningKey != null) ...[
                    const SizedBox(height: 8),
                    Text(scope.t(warningKey)),
                  ],
                  const SizedBox(height: 10),
                  PassageView(step.text),
                  const SizedBox(height: 12),
                  FilledButton.tonal(
                    onPressed: () => _readAloud(step.text),
                    child: Text(scope.t('answer_read_aloud_description')),
                  ),
                  if (_speechUnavailable) ...[
                    const SizedBox(height: 8),
                    Text(scope.t('answer_read_aloud_unavailable')),
                  ],
                  const SizedBox(height: 12),
                  _StepCitation(step: step),
                ],
              ),
            ),
          ),
        );
      }
      children.add(const SizedBox(height: 16));
    }
    return children;
  }
}

/// Steps whose text is a verbatim substring of the cited chunk.
List<_CitedStep> _citedSteps(ContentStore? store) {
  if (store == null) return const [];
  final shown = <_CitedStep>[];
  for (final card in store.cards()) {
    for (final step in card.steps) {
      StoredChunk? chunk;
      for (final candidate in store.chunks(step.documentId)) {
        if (candidate.id == step.chunkId) {
          chunk = candidate;
          break;
        }
      }
      final document = store.document(step.documentId);
      if (chunk == null ||
          document == null ||
          step.text.isEmpty ||
          !chunk.content.contains(step.text)) {
        continue;
      }
      shown.add(
        _CitedStep(
          hazard: card.hazard,
          title: card.title,
          text: step.text,
          document: document,
          page: chunk.pageNumber,
        ),
      );
    }
  }
  return shown;
}

Freshness _stepFreshness(SheltrScope scope, StoredDocument document) {
  return assessFreshness(
    now: scope.now(),
    bundleVersion: scope.store?.bundleVersion(),
    lastSyncEpochSeconds: scope.store?.lastSyncEpochSeconds(),
    expiresOn: [document.expiresOn],
  );
}

class _CitedStep {
  const _CitedStep({
    required this.hazard,
    required this.title,
    required this.text,
    required this.document,
    required this.page,
  });

  final String hazard;
  final String title;
  final String text;
  final StoredDocument document;
  final int? page;
}

class _HazardGrid extends StatelessWidget {
  const _HazardGrid({required this.scope, required this.onChosen});

  final SheltrScope scope;
  final ValueChanged<String> onChosen;

  @override
  Widget build(BuildContext context) {
    final hazards = Rules.packaged().disasterTypes;
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = (constraints.maxWidth - 12) / 2;
        return Wrap(
          spacing: 12,
          runSpacing: 12,
          children: [
            for (final hazard in hazards)
              SizedBox(
                width: width,
                child: _HazardTile(
                  name: scope.t('hazard_${hazard.toLowerCase()}'),
                  hint: scope.t('hazard_open'),
                  icon: _hazardIcon(hazard),
                  onPressed: () => onChosen(hazard),
                ),
              ),
          ],
        );
      },
    );
  }
}

class _HazardTile extends StatelessWidget {
  const _HazardTile({
    required this.name,
    required this.hint,
    required this.icon,
    required this.onPressed,
  });

  final String name;
  final String hint;
  final IconData icon;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return Semantics(
      button: true,
      label: '$name\n$hint',
      onTap: onPressed,
      excludeSemantics: true,
      child: Material(
        color: scheme.primary,
        borderRadius: BorderRadius.circular(16),
        elevation: 1.5,
        shadowColor: scheme.primary.withValues(alpha: 0.35),
        child: InkWell(
          onTap: onPressed,
          borderRadius: BorderRadius.circular(16),
          child: ConstrainedBox(
            constraints: const BoxConstraints(minHeight: 88),
            child: Padding(
              padding: const EdgeInsets.fromLTRB(14, 14, 14, 12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(icon, color: scheme.onPrimary, size: 22),
                  const SizedBox(height: 10),
                  Text(
                    name,
                    style: theme.textTheme.titleSmall?.copyWith(
                      color: scheme.onPrimary,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    hint,
                    style: theme.textTheme.labelMedium?.copyWith(
                      color: scheme.onPrimary.withValues(alpha: 0.88),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

IconData _hazardIcon(String hazard) {
  switch (hazard) {
    case 'EARTHQUAKE':
      return Icons.landscape;
    case 'FLOOD':
      return Icons.water;
    case 'WILDFIRE':
      return Icons.local_fire_department;
    case 'TROPICAL_CYCLONE':
      return Icons.cyclone;
    case 'TORNADO':
      return Icons.tornado;
    case 'TSUNAMI':
      return Icons.tsunami;
    case 'VOLCANO':
      return Icons.volcano;
    case 'LANDSLIDE':
      return Icons.terrain;
    case 'DROUGHT':
      return Icons.wb_sunny;
    case 'EXTREME_HEAT':
      return Icons.thermostat;
    case 'WINTER_STORM':
      return Icons.ac_unit;
    case 'GLACIAL_LAKE_FLOOD':
      return Icons.waves;
    default:
      return Icons.warning_amber;
  }
}

class _StepCitation extends StatelessWidget {
  const _StepCitation({required this.step});

  final _CitedStep step;

  @override
  Widget build(BuildContext context) {
    final scope = SheltrScope.of(context);
    final page = step.page;
    final theme = Theme.of(context);
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(12),
      ),
      child: SelectionArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              scope.t('answer_source_label'),
              style: theme.textTheme.labelLarge,
            ),
            const SizedBox(height: 4),
            Text(step.document.title, style: theme.textTheme.titleSmall),
            Text(step.document.agency),
            Text(
              scope.t('answer_source_published', {
                'p1': step.document.publishedDate,
              }),
            ),
            if (page != null)
              Text(scope.t('answer_source_page', {'p1': page})),
            const SizedBox(height: 4),
            Text(
              scope.t('answer_source_link_label'),
              style: theme.textTheme.labelMedium?.copyWith(
                color: theme.colorScheme.secondary,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
