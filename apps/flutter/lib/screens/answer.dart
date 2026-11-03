import 'package:flutter/material.dart';
import 'package:flutter_tts/flutter_tts.dart';
import 'package:sheltr_core/sheltr_core.dart';

import '../scope.dart';

/// Passages that answer the question, each with its source, or Sheltr's refusal.
class AnswerScreen extends StatefulWidget {
  const AnswerScreen({super.key});

  @override
  State<AnswerScreen> createState() => _AnswerScreenState();
}

class _AnswerScreenState extends State<AnswerScreen> {
  FlutterTts? _speech;
  var _speechUnavailable = false;

  @override
  void dispose() {
    _speech?.stop();
    super.dispose();
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
    final result = scope.answer;
    if (result == null ||
        result.kind != 'passages' ||
        result.passages.isEmpty) {
      return const _Refusal();
    }
    final spoken = result.passages
        .map((passage) => passage.chunk.content)
        .join('\n\n');
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        FilledButton(
          onPressed: () => _readAloud(spoken),
          child: Text(scope.t('answer_read_aloud_description')),
        ),
        if (_speechUnavailable) ...[
          const SizedBox(height: 8),
          Text(scope.t('answer_read_aloud_unavailable')),
        ],
        for (final passage in result.passages) ...[
          const SizedBox(height: 16),
          Text(
            passage.chunk.content,
            style: Theme.of(context).textTheme.titleMedium,
          ),
          const SizedBox(height: 8),
          _Citation(passage: passage),
        ],
      ],
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
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              scope.t('answer_source_label'),
              style: Theme.of(context).textTheme.labelLarge,
            ),
            Text(passage.title),
            Text(passage.agency),
            Text(
              scope.t('answer_source_published', {'p1': passage.publishedDate}),
            ),
            if (page != null) Text(scope.t('answer_source_page', {'p1': page})),
            TextButton(onPressed: () {}, child: Text(passage.sourceLink)),
          ],
        ),
      ),
    );
  }
}

/// Sheltr's own wording. The retriever does not supply a localised refusal.
class _Refusal extends StatelessWidget {
  const _Refusal();

  @override
  Widget build(BuildContext context) {
    final scope = SheltrScope.of(context);
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Text(
          scope.t('answer_refusal_title'),
          style: Theme.of(context).textTheme.headlineSmall,
        ),
        const SizedBox(height: 12),
        Text(scope.t('answer_refusal_body')),
      ],
    );
  }
}
