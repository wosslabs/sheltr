import { useEffect, useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';
import type { StoredDocument } from '../core/store/types';
import { useServices } from '../platform/AppContext';
import { AppText, spacing } from '../ui/components';
import { useT } from '../ui/useT';

/**
 * What the app is, that it is not an official warning system, what it does with data, and the source and licence
 * of every installed document. The document list comes from the installed bundle, so it stays accurate when the
 * corpus changes.
 */
export default function AboutScreen() {
  const { store } = useServices();
  const { t } = useT();
  const [documents, setDocuments] = useState<StoredDocument[]>([]);

  useEffect(() => {
    let cancelled = false;
    store.documents().then((d) => {
      if (!cancelled) setDocuments(d);
    });
    return () => {
      cancelled = true;
    };
  }, [store]);

  return (
    <ScrollView testID="about-screen" contentContainerStyle={styles.content}>
      <AppText size={24} weight="700" accessibilityRole="header">{t('about_title')}</AppText>
      <AppText>{t('about_description')}</AppText>

      <Section testID="about-disclaimer" title={t('about_disclaimer_title')}>
        <AppText>{t('about_disclaimer')}</AppText>
      </Section>
      <Section testID="about-privacy" title={t('about_privacy_title')}>
        <AppText>{t('about_privacy')}</AppText>
      </Section>
      <Section testID="about-sources" title={t('about_sources_title')}>
        {documents.length === 0 ? (
          <AppText>{t('about_sources_empty')}</AppText>
        ) : (
          <>
            <AppText>{t('about_sources_intro')}</AppText>
            {documents.map((d) => (
              <View key={d.id} testID={`about-source-${d.id}`} style={styles.source}>
                <AppText size={16} weight="600">{d.title}</AppText>
                <AppText size={13} muted>{t('about_source_details', { p1: d.agency, p2: d.publishedDate, p3: d.country ?? 'GLOBAL' })}</AppText>
                <AppText size={13} muted>{t('about_source_licence', { p1: d.licence })}</AppText>
                <AppText size={13} muted selectable>{t('about_source_link', { p1: d.sourceUri })}</AppText>
              </View>
            ))}
          </>
        )}
      </Section>
      <AppText size={13} muted>{t('about_code_licence')}</AppText>
    </ScrollView>
  );
}

function Section({ title, testID, children }: { title: string; testID: string; children: React.ReactNode }) {
  return (
    <View testID={testID} style={styles.section}>
      <AppText size={18} weight="700" accessibilityRole="header">{title}</AppText>
      {children}
    </View>
  );
}

const styles = StyleSheet.create({
  content: { padding: spacing.m, gap: spacing.m },
  section: { gap: spacing.s, marginTop: spacing.s },
  source: { gap: 2, marginTop: spacing.s },
});
