import { MaterialCommunityIcons } from '@expo/vector-icons';
import { Stack, useRouter } from 'expo-router';
import { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, TextInput, View } from 'react-native';
import { DISASTER_TYPES } from '../core/retrieval/vocabulary';
import { AppText, spacing } from '../ui/components';
import { HAZARD_ICONS, hazardLabelKey } from '../ui/hazards';
import { useTheme } from '../ui/theme';
import { useT } from '../ui/useT';

/** Home: pick a hazard, or type a question. */
export default function HomeScreen() {
  const router = useRouter();
  const { t } = useT();
  const theme = useTheme();
  const [text, setText] = useState('');

  const submit = () => {
    const trimmed = text.trim();
    if (trimmed !== '') router.push({ pathname: '/search', params: { query: trimmed } });
  };

  return (
    <ScrollView testID="home-screen" contentContainerStyle={styles.content}>
      <Stack.Screen
        options={{
          headerRight: () => (
            <Pressable
              testID="home-settings-button"
              onPress={() => router.push('/settings')}
              accessibilityRole="button"
              accessibilityLabel={t('home_settings_button_label')}
              style={styles.headerButton}
            >
              <MaterialCommunityIcons name="cog" size={26} color={theme.colors.text} />
            </Pressable>
          ),
        }}
      />
      <AppText size={26} weight="700" accessibilityRole="header">
        {t('home_title')}
      </AppText>
      <View
        style={[styles.searchBox, { borderColor: theme.colors.border, borderWidth: theme.borderWidth, backgroundColor: theme.colors.surface }]}
      >
        <MaterialCommunityIcons name="magnify" size={theme.fs(24)} color={theme.colors.mutedText} />
        <TextInput
          testID="home-search-input"
          value={text}
          onChangeText={setText}
          onSubmitEditing={submit}
          returnKeyType="search"
          placeholder={t('home_search_placeholder')}
          placeholderTextColor={theme.colors.mutedText}
          accessibilityLabel={t('home_search_hint_description')}
          style={[styles.input, { color: theme.colors.text, fontSize: theme.fs(16) }]}
        />
      </View>
      <View style={styles.grid}>
        {DISASTER_TYPES.map((hazard) => (
          <Pressable
            key={hazard}
            testID={`hazard-tile-${hazard}`}
            onPress={() => router.push({ pathname: '/search', params: { hazard } })}
            accessibilityRole="button"
            accessibilityLabel={t(hazardLabelKey(hazard))}
            style={[styles.tile, { backgroundColor: theme.colors.surface, borderColor: theme.colors.border, borderWidth: theme.borderWidth }]}
          >
            <MaterialCommunityIcons name={HAZARD_ICONS[hazard]} size={theme.fs(32)} color={theme.colors.primary} />
            <AppText size={14} style={styles.tileLabel}>
              {t(hazardLabelKey(hazard))}
            </AppText>
          </Pressable>
        ))}
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  content: { padding: spacing.m, gap: spacing.m },
  headerButton: { minWidth: 48, minHeight: 48, alignItems: 'center', justifyContent: 'center' },
  searchBox: { flexDirection: 'row', alignItems: 'center', gap: 8, borderRadius: 28, paddingHorizontal: 16, minHeight: 52 },
  input: { flex: 1, minHeight: 48 },
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: 12 },
  tile: { width: '31%', flexGrow: 1, minHeight: 96, borderRadius: 12, alignItems: 'center', justifyContent: 'center', padding: 8, gap: 6 },
  tileLabel: { textAlign: 'center' },
});
