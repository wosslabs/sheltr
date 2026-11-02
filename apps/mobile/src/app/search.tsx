import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { useState } from 'react';
import { Pressable, StyleSheet, TextInput, View } from 'react-native';
import { DISASTER_TYPES } from '../core/retrieval/vocabulary';
import { AppButton, AppText, spacing } from '../ui/components';
import { useTheme } from '../ui/theme';
import { useT } from '../ui/useT';
import { hazardLabelKey } from '../ui/hazards';

const firstParam = (value: string | string[] | undefined): string | undefined =>
  Array.isArray(value) ? value[0] : value;

/** Search: type a question, optionally narrowed to one hazard, then go to the answer. */
export default function SearchScreen() {
  const params = useLocalSearchParams<{ query?: string; hazard?: string }>();
  const router = useRouter();
  const { t } = useT();
  const theme = useTheme();
  const initialHazard = firstParam(params.hazard);
  const [text, setText] = useState(firstParam(params.query) ?? '');
  const [hazard, setHazard] = useState<string | null>(
    initialHazard !== undefined && (DISASTER_TYPES as readonly string[]).includes(initialHazard) ? initialHazard : null,
  );
  const canSubmit = text.trim() !== '';

  const submit = () => {
    const trimmed = text.trim();
    if (trimmed === '') return;
    router.push({ pathname: '/answer', params: hazard === null ? { text: trimmed } : { text: trimmed, hazard } });
  };

  return (
    <View testID="search-screen" style={styles.container}>
      {hazard !== null ? (
        <Pressable
          testID="search-hazard-chip"
          onPress={() => setHazard(null)}
          accessibilityRole="button"
          accessibilityLabel={`${t('search_active_hazard_chip_description')}: ${t(hazardLabelKey(hazard))}`}
          style={[styles.chip, { borderColor: theme.colors.primary, borderWidth: theme.borderWidth }]}
        >
          <AppText size={14}>{t('search_hazard_chip', { p1: t(hazardLabelKey(hazard)) })}</AppText>
          <MaterialCommunityIcons name="close-circle" size={theme.fs(20)} color={theme.colors.primary} />
        </Pressable>
      ) : null}
      <TextInput
        testID="search-input"
        value={text}
        onChangeText={setText}
        onSubmitEditing={submit}
        returnKeyType="search"
        autoFocus
        placeholder={t('search_placeholder')}
        placeholderTextColor={theme.colors.mutedText}
        accessibilityLabel={t('search_placeholder')}
        style={[
          styles.input,
          { color: theme.colors.text, fontSize: theme.fs(18), borderColor: theme.colors.border, borderWidth: theme.borderWidth, backgroundColor: theme.colors.surface },
        ]}
      />
      <AppButton
        testID="search-submit"
        label={t('search_submit_description')}
        icon="magnify"
        onPress={submit}
        disabled={!canSubmit}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { padding: spacing.m, gap: spacing.m },
  chip: { alignSelf: 'flex-start', flexDirection: 'row', alignItems: 'center', gap: 8, minHeight: 48, paddingHorizontal: 16, borderRadius: 24 },
  input: { minHeight: 56, borderRadius: 12, paddingHorizontal: 16 },
});
