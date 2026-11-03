import 'package:flutter/material.dart';
import 'package:flutter_tts/flutter_tts.dart';
import 'package:sheltr_core/sheltr_core.dart';

import '../scope.dart';

/// Home: cited cards for a hazard, or the twelve-hazard grid when there are none.
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final _controller = TextEditingController();
  FlutterTts? _speech;
  var _speechUnavailable = false;

  @override
  void dispose() {
    _controller.dispose();
    _speech?.stop();
    super.dispose();
  }

  void _openSearch(String query) {
    final trimmed = query.trim();
    if (trimmed.isEmpty) {
      return;
    }
    Navigator.of(context).pushNamed('/search', arguments: {'query': trimmed});
  }

  /// Guidance text is English even when the menus are not, so the voice is English.
  Future<void> _readAloud(String text) async {
    try {
      final speech = _speech ??= FlutterTts();
      await speech.setLanguage('en-US');
      await speech.speak(text);
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
    final steps = _citedSteps(scope.store);
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Text(
          scope.t('home_title'),
          style: Theme.of(context).textTheme.headlineSmall,
        ),
        const SizedBox(height: 16),
        SheltrTextField(
          label: scope.t('home_search_hint_description'),
          hint: scope.t('home_search_placeholder'),
          controller: _controller,
          onSubmitted: _openSearch,
        ),
        const SizedBox(height: 16),
        if (steps.isEmpty)
          _HazardGrid(scope: scope)
        else
          ..._cardSections(context, scope, steps),
      ],
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
        children.add(const SizedBox(height: 12));
        children.add(
          Text(step.title, style: Theme.of(context).textTheme.titleMedium),
        );
        children.add(const SizedBox(height: 8));
        children.add(
          Text(step.text, style: Theme.of(context).textTheme.titleMedium),
        );
        children.add(const SizedBox(height: 8));
        children.add(
          FilledButton(
            onPressed: () => _readAloud(step.text),
            child: Text(scope.t('answer_read_aloud_description')),
          ),
        );
        if (_speechUnavailable) {
          children.add(const SizedBox(height: 8));
          children.add(Text(scope.t('answer_read_aloud_unavailable')));
        }
        children.add(const SizedBox(height: 8));
        children.add(_StepCitation(step: step));
      }
      children.add(const SizedBox(height: 16));
    }
    return children;
  }
}

/// Steps whose text is a verbatim substring of the cited chunk.
///
/// A card the publisher would have refused, or a chunk that is not installed,
/// is left out. When nothing remains, the home screen keeps the hazard grid.
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
  const _HazardGrid({required this.scope});

  final SheltrScope scope;

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
                child: FilledButton.tonal(
                  onPressed: () {
                    Navigator.of(context)
                        .pushNamed('/search', arguments: {'hazard': hazard});
                  },
                  child: Text(
                    scope.t('hazard_${hazard.toLowerCase()}'),
                    textAlign: TextAlign.center,
                  ),
                ),
              ),
          ],
        );
      },
    );
  }
}

class _StepCitation extends StatelessWidget {
  const _StepCitation({required this.step});

  final _CitedStep step;

  @override
  Widget build(BuildContext context) {
    final scope = SheltrScope.of(context);
    final page = step.page;
    return Card(
      child: SelectionArea(
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                scope.t('answer_source_label'),
                style: Theme.of(context).textTheme.labelLarge,
              ),
              Text(step.document.title),
              Text(step.document.agency),
              Text(
                scope.t('answer_source_published', {
                  'p1': step.document.publishedDate,
                }),
              ),
              if (page != null)
                Text(scope.t('answer_source_page', {'p1': page})),
              Text(step.document.sourceUri),
            ],
          ),
        ),
      ),
    );
  }
}
