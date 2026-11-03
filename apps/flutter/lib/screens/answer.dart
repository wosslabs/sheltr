import 'dart:async';

import 'package:flutter/material.dart';
import 'package:sheltr_core/sheltr_core.dart';

import '../format_passage.dart';
import '../passage_view.dart';
import '../scope.dart';
import '../speech.dart';

/// Passages that answer the question, each with its source, or Sheltr's refusal.
class AnswerScreen extends StatefulWidget {
  const AnswerScreen({super.key, this.query = '', this.hazard});

  /// Question from search. Empty when a test opens this route directly.
  final String query;

  /// Disaster type from the hazard chip, or null.
  final String? hazard;

  @override
  State<AnswerScreen> createState() => _AnswerScreenState();
}

class _AnswerScreenState extends State<AnswerScreen> {
  final _speech = SheltrSpeech();
  var _speechUnavailable = false;
  AnswerPresentation? _loaded;
  RetrievalResult? _vectorResult;
  var _asked = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final scope = SheltrScope.of(context);
    if (scope.embedQuery != null) {
      if (_asked) {
        return;
      }
      _asked = true;
      unawaited(_answerFromVectors(scope));
      return;
    }
    final model = scope.model;
    if (model == null || _asked) {
      return;
    }
    _asked = true;
    final result = _result(scope);
    final question = widget.query;
    final enabled = scope.settings.modelEnabled;
    unawaited(() async {
      AnswerPresentation presentation;
      try {
        presentation = await presentWithModel(
          model: model,
          question: question,
          retrieval: result,
          modelEnabled: enabled,
        );
      } catch (_) {
        presentation = present(
          retrieval: result,
          modelText: null,
          modelEnabled: enabled,
        );
      }
      if (!mounted) {
        return;
      }
      setState(() => _loaded = presentation);
    }());
  }

  Future<void> _answerFromVectors(SheltrScope scope) async {
    final embed = scope.embedQuery;
    final store = scope.store;
    final question = widget.query.trim();
    RetrievalResult result = const RetrievalResult.refuse();
    if (embed != null && store != null && question.isNotEmpty) {
      try {
        final vector = await embed(question);
        if (!mounted) {
          return;
        }
        final hazard = widget.hazard;
        result = Retriever(store).retrieveByVector(
          vector,
          RetrievalQuery(
            text: question,
            country: scope.settings.country,
            activeHazards: hazard == null ? const <String>{} : {hazard},
          ),
        );
      } catch (_) {
        result = const RetrievalResult.refuse();
      }
    }
    final model = scope.model;
    final enabled = scope.settings.modelEnabled;
    AnswerPresentation presentation;
    try {
      presentation = model == null
          ? present(
              retrieval: result,
              modelText: null,
              modelEnabled: enabled,
            )
          : await presentWithModel(
              model: model,
              question: question,
              retrieval: result,
              modelEnabled: enabled,
            );
    } catch (_) {
      presentation = present(
        retrieval: result,
        modelText: null,
        modelEnabled: enabled,
      );
    }
    if (!mounted) {
      return;
    }
    setState(() {
      _vectorResult = result;
      _loaded = presentation;
    });
  }

  @override
  void dispose() {
    _speech.stop();
    super.dispose();
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

  RetrievalResult _result(SheltrScope scope) {
    final query = widget.query.trim();
    if (query.isEmpty) {
      return scope.answer ?? const RetrievalResult.refuse();
    }
    final store = scope.store;
    if (store == null) {
      return const RetrievalResult.refuse();
    }
    final hazard = widget.hazard;
    return Retriever(store).retrieve(
      RetrievalQuery(
        text: query,
        country: scope.settings.country,
        activeHazards: hazard == null ? const <String>{} : {hazard},
      ),
    );
  }

  Freshness _freshness(SheltrScope scope, RetrievalResult result) {
    return assessFreshness(
      now: scope.now(),
      bundleVersion: scope.store?.bundleVersion(),
      lastSyncEpochSeconds: scope.store?.lastSyncEpochSeconds(),
      expiresOn: [
        for (final passage in result.passages) passage.document.expiresOn,
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final scope = SheltrScope.of(context);
    if (scope.embedQuery != null && _vectorResult == null) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const CircularProgressIndicator(),
            const SizedBox(height: 16),
            Text(scope.t('answer_loading')),
          ],
        ),
      );
    }
    final result = _vectorResult ?? _result(scope);
    final presentation =
        _loaded ??
        present(
          retrieval: result,
          modelText: null,
          modelEnabled: scope.settings.modelEnabled,
        );
    if (presentation.passages.isEmpty) {
      return const _Refusal();
    }
    return _AnswerBody(
      presentation: presentation,
      freshness: _freshness(scope, result),
      speechUnavailable: _speechUnavailable,
      onReadAloud: _readAloud,
    );
  }
}

class _AnswerBody extends StatelessWidget {
  const _AnswerBody({
    required this.presentation,
    required this.freshness,
    required this.speechUnavailable,
    required this.onReadAloud,
  });

  final AnswerPresentation presentation;
  final Freshness freshness;
  final bool speechUnavailable;
  final Future<void> Function(String text) onReadAloud;

  @override
  Widget build(BuildContext context) {
    final scope = SheltrScope.of(context);
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final warningKey = stalenessWarningKey(freshness);
    final lead = presentation.lead;
    final spoken = [
      if (lead != null) lead,
      for (final passage in presentation.passages)
        formatPassage(passage.chunk.content),
    ].join('\n\n');
    return SheltrPageBackground(
      child: ListView(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
        children: [
          if (freshness.clockUnsure) ...[
            _Notice(text: scope.t('answer_clock_unsure')),
            const SizedBox(height: 12),
          ],
          if (warningKey != null) ...[
            _Notice(text: scope.t(warningKey), tone: _NoticeTone.warn),
            const SizedBox(height: 12),
          ],
          Text(
            scope.t('answer_guidance_intro'),
            style: theme.textTheme.bodyMedium?.copyWith(
              color: scheme.onSurface.withValues(alpha: 0.78),
              height: 1.4,
            ),
          ),
          const SizedBox(height: 14),
          FilledButton.icon(
            onPressed: () => onReadAloud(spoken),
            icon: const Icon(Icons.volume_up),
            label: Text(scope.t('answer_read_aloud_description')),
          ),
          if (speechUnavailable) ...[
            const SizedBox(height: 8),
            Text(scope.t('answer_read_aloud_unavailable')),
          ],
          if (lead != null) ...[
            const SizedBox(height: 16),
            Card(
              color: scheme.primaryContainer,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(16),
                side: BorderSide(
                  color: scheme.primary.withValues(alpha: 0.25),
                ),
              ),
              child: Padding(
                padding: const EdgeInsets.all(18),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      scope.t('answer_lead_label'),
                      style: theme.textTheme.labelLarge?.copyWith(
                        color: scheme.onPrimaryContainer,
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      lead,
                      style: theme.textTheme.bodyLarge?.copyWith(
                        height: 1.5,
                        color: scheme.onPrimaryContainer,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
          for (final passage in presentation.passages) ...[
            const SizedBox(height: 20),
            Card(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 16, 16, 16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      scope.t('answer_guidance_label'),
                      style: theme.textTheme.labelLarge?.copyWith(
                        color: scheme.secondary,
                      ),
                    ),
                    const SizedBox(height: 10),
                    PassageView(passage.chunk.content),
                    const SizedBox(height: 16),
                    _Citation(passage: passage),
                  ],
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

enum _NoticeTone { info, warn }

class _Notice extends StatelessWidget {
  const _Notice({required this.text, this.tone = _NoticeTone.info});

  final String text;
  final _NoticeTone tone;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final warn = tone == _NoticeTone.warn;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: warn
            ? const Color(0xFFFFF3CD)
            : scheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(
          color: warn
              ? const Color(0xFFB08900)
              : scheme.outline.withValues(alpha: 0.4),
        ),
      ),
      child: Text(
        text,
        style: Theme.of(context).textTheme.bodyMedium?.copyWith(
          color: warn ? const Color(0xFF5C4500) : null,
        ),
      ),
    );
  }
}

class _Citation extends StatelessWidget {
  const _Citation({required this.passage});

  final Passage passage;

  @override
  Widget build(BuildContext context) {
    final scope = SheltrScope.of(context);
    final page = passage.page;
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: scheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            scope.t('answer_source_label'),
            style: theme.textTheme.labelLarge,
          ),
          const SizedBox(height: 4),
          Text(passage.title, style: theme.textTheme.titleSmall),
          Text(passage.agency),
          Text(
            scope.t('answer_source_published', {
              'p1': passage.publishedDate,
            }),
          ),
          if (page != null)
            Text(scope.t('answer_source_page', {'p1': page})),
          const SizedBox(height: 6),
          Text(
            scope.t('answer_source_link_label'),
            style: theme.textTheme.labelLarge?.copyWith(
              color: scheme.secondary,
            ),
          ),
        ],
      ),
    );
  }
}

class _Refusal extends StatelessWidget {
  const _Refusal();

  @override
  Widget build(BuildContext context) {
    final scope = SheltrScope.of(context);
    final theme = Theme.of(context);
    return SheltrPageBackground(
      child: ListView(
        padding: const EdgeInsets.fromLTRB(20, 24, 20, 32),
        children: [
          Semantics(
            header: true,
            child: Text(
              scope.t('answer_refusal_title'),
              style: theme.textTheme.headlineSmall,
            ),
          ),
          const SizedBox(height: 12),
          Text(
            scope.t('answer_refusal_body'),
            style: theme.textTheme.bodyLarge?.copyWith(height: 1.5),
          ),
        ],
      ),
    );
  }
}
