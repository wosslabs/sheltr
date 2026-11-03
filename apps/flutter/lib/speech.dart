import 'package:flutter_tts/flutter_tts.dart';

/// English read-aloud for guidance text.
///
/// Waits for the engine, prefers an enhanced en-US voice, and pauses briefly
/// before [speak] so the first words are not clipped.
class SheltrSpeech {
  SheltrSpeech();

  FlutterTts? _engine;
  var _ready = false;

  Future<FlutterTts> _prepare() async {
    final speech = _engine ??= FlutterTts();
    if (_ready) {
      return speech;
    }
    await speech.awaitSpeakCompletion(true);
    await speech.setLanguage('en-US');
    await speech.setSpeechRate(0.42);
    await speech.setPitch(0.98);
    await speech.setVolume(1.0);
    try {
      await speech.setSharedInstance(true);
    } catch (_) {
      // Desktop and web engines do not always expose shared instance.
    }
    try {
      await speech.setIosAudioCategory(
        IosTextToSpeechAudioCategory.playback,
        [
          IosTextToSpeechAudioCategoryOptions.allowBluetooth,
          IosTextToSpeechAudioCategoryOptions.mixWithOthers,
        ],
        IosTextToSpeechAudioMode.voicePrompt,
      );
    } catch (_) {
      // Non-iOS platforms ignore this.
    }
    await _preferClearVoice(speech);
    // Engines often drop the start of the first utterance without a warm-up.
    await Future<void>.delayed(const Duration(milliseconds: 400));
    _ready = true;
    return speech;
  }

  Future<void> _preferClearVoice(FlutterTts speech) async {
    try {
      final raw = await speech.getVoices;
      if (raw is! List) {
        return;
      }
      final voices = <Map<String, String>>[
        for (final entry in raw)
          if (entry is Map)
            {
              for (final key in entry.keys)
                if (key != null && entry[key] != null)
                  '$key': '${entry[key]}',
            },
      ];
      Map<String, String>? chosen;
      for (final voice in voices) {
        final locale = (voice['locale'] ?? voice['Locale'] ?? '').toLowerCase();
        final name = (voice['name'] ?? voice['Name'] ?? '').toLowerCase();
        if (!locale.startsWith('en')) {
          continue;
        }
        final enhanced =
            name.contains('enhanced') ||
            name.contains('premium') ||
            name.contains('neural') ||
            name.contains('natural') ||
            name.contains('siri') ||
            name.contains('samuel') ||
            name.contains('karen') ||
            name.contains('daniel') ||
            name.contains('aria') ||
            name.contains('jenny');
        final us = locale.contains('us') || locale.contains('united');
        if (enhanced && us) {
          chosen = voice;
          break;
        }
        if (chosen == null && us) {
          chosen = voice;
        } else if (chosen == null && locale.startsWith('en')) {
          chosen = voice;
        } else if (enhanced && chosen != null) {
          final current = (chosen['name'] ?? '').toLowerCase();
          if (!current.contains('enhanced') &&
              !current.contains('premium') &&
              !current.contains('neural')) {
            chosen = voice;
          }
        }
      }
      if (chosen != null) {
        await speech.setVoice(chosen);
      }
    } catch (_) {
      // Keep the language default when voice enumeration fails.
    }
  }

  Future<void> speak(String text) async {
    final trimmed = text.trim();
    if (trimmed.isEmpty) {
      return;
    }
    final speech = await _prepare();
    await speech.stop();
    // Extra beat after stop so the next utterance starts cleanly.
    await Future<void>.delayed(const Duration(milliseconds: 250));
    await speech.speak(trimmed);
  }

  Future<void> stop() async {
    await _engine?.stop();
  }
}
