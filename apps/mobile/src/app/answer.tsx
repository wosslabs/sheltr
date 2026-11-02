import * as Linking from 'expo-linking';
import * as Speech from 'expo-speech';
import { useLocalSearchParams } from 'expo-router';
import { useEffect, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { citationOf } from '../core/retrieval/citations';
import type { Passage, RetrievalResult } from '../core/retrieval/retriever';
import { useServices } from '../platform/AppContext';
import { AppButton, AppText, Card, spacing } from '../ui/components';
import { useT } from '../ui/useT';

const firstParam = (value: string | string[] | undefined): string | undefined =>
  Array.isArray(value) ? value[0] : value;

/**
 * The corpus is English regardless of the app's display language, so it is always read aloud with an English
 * voice: an English text in a voice picked for the UI language would be mispronounced.
 */
const SPEECH_LANGUAGE = 'en-US';

/** Answer: the passages that answer the question, each with its source, or Sheltr's own refusal. */
export default function AnswerScreen() {
  const params = useLocalSearchParams<{ text?: string; hazard?: string }>();
  const { retriever, settings } = useServices();
  const { t } = useT();
  const text = firstParam(params.text);
  const hazard = firstParam(params.hazard);
  const [result, setResult] = useState<RetrievalResult | null>(null);
  const [speaking, setSpeaking] = useState(false);
  const [speechAvailable, setSpeechAvailable] = useState(true);

  // Retrieval is deterministic and local, so the route carries only the question: nothing needs to survive a
  // process restart except the route itself.
  useEffect(() => {
    if (text === undefined || text.trim() === '') return;
    let cancelled = false;
    retriever
      .retrieve({ text, country: settings.country, activeHazards: hazard ? new Set([hazard]) : new Set() })
      .then((r) => {
        if (!cancelled) setResult(r);
      });
    return () => {
      cancelled = true;
    };
  }, [retriever, text, hazard, settings.country]);

  useEffect(() => {
    let cancelled = false;
    Speech.getAvailableVoicesAsync()
      .then((voices) => {
        if (!cancelled) setSpeechAvailable(voices.some((v) => v.language.toLowerCase().startsWith('en')));
      })
      .catch(() => {
        if (!cancelled) setSpeechAvailable(false);
      });
    return () => {
      cancelled = true;
      Speech.stop().catch(() => undefined);
    };
  }, []);

  if (text === undefined || text.trim() === '') {
    return <Refusal />;
  }
  if (result === null) {
    return (
      <View testID="answer-loading" style={styles.center} accessibilityLiveRegion="polite">
        <ActivityIndicator size="large" />
        <AppText>{t('answer_loading')}</AppText>
      </View>
    );
  }
  if (result.kind === 'refusal') return <Refusal />;

  const readAloud = () => {
    if (speaking) {
      Speech.stop().catch(() => undefined);
      setSpeaking(false);
      return;
    }
    setSpeaking(true);
    Speech.speak(result.passages[0].chunk.content, {
      language: SPEECH_LANGUAGE,
      onDone: () => setSpeaking(false),
      onStopped: () => setSpeaking(false),
      onError: () => setSpeaking(false),
    });
  };

  return (
    <ScrollView testID="answer-screen" contentContainerStyle={styles.content}>
      <AppButton
        testID="answer-read-aloud-button"
        label={t('answer_read_aloud_description')}
        icon={speaking ? 'stop' : 'play'}
        onPress={readAloud}
        disabled={!speechAvailable}
      />
      {!speechAvailable ? <AppText size={13} muted>{t('answer_read_aloud_unavailable')}</AppText> : null}
      {result.passages.map((passage) => (
        <View key={passage.chunk.id} style={styles.passage}>
          <AppText size={18}>{passage.chunk.content}</AppText>
          <CitationBlock passage={passage} />
        </View>
      ))}
    </ScrollView>
  );
}

function CitationBlock({ passage }: { passage: Passage }) {
  const { t } = useT();
  const citation = citationOf(passage);
  return (
    <Card testID="answer-citation">
      <AppText size={13} weight="700">{t('answer_source_label')}</AppText>
      <AppText size={15}>{citation.sourceTitle}</AppText>
      <AppText size={13} muted>{passage.document.agency}</AppText>
      <AppText size={13} muted>{t('answer_source_published', { p1: passage.document.publishedDate })}</AppText>
      {citation.pageNumber !== null ? <AppText size={13} muted>{t('answer_source_page', { p1: citation.pageNumber })}</AppText> : null}
      <Pressable
        testID="answer-source-link"
        onPress={() => Linking.openURL(citation.sourceUrl).catch(() => undefined)}
        accessibilityRole="link"
        accessibilityLabel={citation.sourceUrl}
        style={styles.link}
      >
        <AppText size={13} style={styles.underline}>{citation.sourceUrl}</AppText>
      </Pressable>
    </Card>
  );
}

/** Sheltr's own wording, never the retriever's English constant: it is localised and says what to do next. */
function Refusal() {
  const { t } = useT();
  return (
    <View testID="answer-refusal" style={styles.content}>
      <AppText size={24} weight="700" accessibilityRole="header">{t('answer_refusal_title')}</AppText>
      <AppText size={18}>{t('answer_refusal_body')}</AppText>
    </View>
  );
}

const styles = StyleSheet.create({
  content: { padding: spacing.m, gap: spacing.m },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: spacing.s },
  passage: { gap: spacing.s },
  link: { minHeight: 48, justifyContent: 'center' },
  underline: { textDecorationLine: 'underline' },
});
