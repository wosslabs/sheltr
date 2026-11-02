import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { useMemo, useState } from 'react';
import { ActivityIndicator, FlatList, Modal, Pressable, ScrollView, StyleSheet, Switch, TextInput, View } from 'react-native';
import {
  LANGUAGES, SYNC_FREQUENCIES, TEXT_SCALES, type Language, type SyncFrequency, type TextScale,
} from '../core/settings/appSettings';
import { COUNTRY_NAMES } from '../core/settings/countries';
import type { SyncOutcome } from '../platform/AppContext';
import { useServices } from '../platform/AppContext';
import type { MessageKey } from '../i18n';
import { AppButton, AppText, Card, RadioRow, spacing } from '../ui/components';
import { useTheme } from '../ui/theme';
import { useT, type TFunction } from '../ui/useT';

const TEXT_SIZE_KEYS: Record<TextScale, MessageKey> = {
  NORMAL: 'settings_text_size_normal',
  LARGE: 'settings_text_size_large',
  EXTRA_LARGE: 'settings_text_size_extra_large',
};

const FREQUENCY_KEYS: Record<SyncFrequency, MessageKey> = {
  MANUAL: 'sync_frequency_manual',
  DAILY: 'sync_frequency_daily',
  WEEKLY: 'sync_frequency_weekly',
  MONTHLY: 'sync_frequency_monthly',
};

/** Each language names itself and is never translated into the current display language. */
const LANGUAGE_ENDONYMS: Record<Exclude<Language, 'system'>, string> = { en: 'English', es: 'Español', fr: 'Français' };

/** Country, language, text size, contrast, sync, and a way to the About screen. */
export default function SettingsScreen() {
  const { settings, updateSettings, lastSyncEpochSeconds, syncAvailable, syncNow } = useServices();
  const { t, locale } = useT();
  const router = useRouter();
  const [syncing, setSyncing] = useState(false);
  const [outcome, setOutcome] = useState<SyncOutcome | null>(null);
  const [countryOpen, setCountryOpen] = useState(false);

  const runSync = async () => {
    setSyncing(true);
    setOutcome(null);
    try {
      setOutcome(await syncNow());
    } finally {
      setSyncing(false);
    }
  };

  const lastSyncText =
    lastSyncEpochSeconds === null ? t('settings_last_sync_never') : new Date(lastSyncEpochSeconds * 1000).toLocaleString(locale);
  const countryName = settings.country === null ? t('settings_country_not_set') : (COUNTRY_NAMES[settings.country]?.[locale] ?? settings.country);

  return (
    <ScrollView testID="settings-screen" contentContainerStyle={styles.content}>
      <Section title={t('settings_country_label')}>
        <AppButton
          testID="settings-country-button"
          variant="outline"
          label={countryName}
          accessibilityLabel={`${t('settings_country_label')}: ${countryName}`}
          onPress={() => setCountryOpen(true)}
        />
      </Section>
      <CountryPicker
        visible={countryOpen}
        selected={settings.country}
        onClose={() => setCountryOpen(false)}
        onSelect={(code) => {
          setCountryOpen(false);
          void updateSettings({ country: code });
        }}
      />

      <Section title={t('settings_language_label')}>
        <RadioRow
          testID="settings-language-system"
          label={t('settings_language_system_default')}
          selected={settings.language === 'system'}
          onPress={() => void updateSettings({ language: 'system' })}
        />
        {LANGUAGES.filter((l): l is Exclude<Language, 'system'> => l !== 'system').map((l) => (
          <RadioRow
            key={l}
            testID={`settings-language-${l}`}
            label={LANGUAGE_ENDONYMS[l]}
            selected={settings.language === l}
            onPress={() => void updateSettings({ language: l })}
          />
        ))}
        <AppText size={13} muted>{t('settings_language_notice')}</AppText>
      </Section>

      <Section title={t('settings_text_size_label')}>
        {TEXT_SCALES.map((scale) => (
          <RadioRow
            key={scale}
            testID={`settings-text-size-${scale}`}
            label={t(TEXT_SIZE_KEYS[scale])}
            selected={settings.textScale === scale}
            onPress={() => void updateSettings({ textScale: scale })}
          />
        ))}
      </Section>

      <Section title={t('settings_high_contrast_label')}>
        <SwitchRow
          testID="settings-high-contrast"
          label={t('settings_high_contrast_label')}
          value={settings.highContrast}
          onValueChange={(highContrast) => void updateSettings({ highContrast })}
        />
      </Section>

      <Section title={t('settings_sync_frequency_label')}>
        {SYNC_FREQUENCIES.map((frequency) => (
          <RadioRow
            key={frequency}
            testID={`settings-sync-frequency-${frequency}`}
            label={t(FREQUENCY_KEYS[frequency])}
            selected={settings.syncFrequency === frequency}
            onPress={() => void updateSettings({ syncFrequency: frequency })}
          />
        ))}
        <SwitchRow
          testID="settings-mobile-data"
          label={t('settings_mobile_data_label')}
          value={settings.allowMobileData}
          onValueChange={(allowMobileData) => void updateSettings({ allowMobileData })}
        />
        <AppText size={13} muted>{t('settings_mobile_data_hint')}</AppText>
      </Section>

      <Section title={t('settings_sync_now_label')}>
        <AppText>{t('settings_last_sync_label', { p1: lastSyncText })}</AppText>
        <AppButton
          testID="settings-sync-now-button"
          label={t('settings_sync_now_label')}
          onPress={() => void runSync()}
          disabled={syncing}
        >
          {syncing ? <ActivityIndicator testID="settings-sync-spinner" /> : undefined}
        </AppButton>
        {!syncAvailable ? <AppText size={13} muted>{t('settings_sync_unavailable_message')}</AppText> : null}
        {outcome !== null ? <SyncMessage outcome={outcome} t={t} onDismiss={() => setOutcome(null)} /> : null}
      </Section>

      <AppButton
        testID="settings-about-button"
        variant="outline"
        label={t('settings_about_button_label')}
        icon="information-outline"
        onPress={() => router.push('/about')}
      />
    </ScrollView>
  );
}

/** Formats a sync outcome with this screen's own strings. */
export function syncMessageText(outcome: SyncOutcome, t: TFunction): string {
  switch (outcome.kind) {
    case 'unavailable':
      return t('settings_sync_unavailable_message');
    case 'upToDate':
      return t('settings_sync_up_to_date_message', { p1: outcome.bundleVersion });
    case 'updated':
      return t('settings_sync_updated_message', { p1: outcome.bundleVersion, p2: outcome.documentsDownloaded });
    case 'rejectedSignature':
      return t('settings_sync_rejected_signature_message');
    case 'failed':
      return t('settings_sync_failed_message', { p1: outcome.reason });
  }
}

function SyncMessage({ outcome, t, onDismiss }: { outcome: SyncOutcome; t: TFunction; onDismiss: () => void }) {
  return (
    <Card testID="settings-sync-message">
      <AppText accessibilityLiveRegion="polite">{syncMessageText(outcome, t)}</AppText>
      <AppButton testID="settings-sync-message-dismiss" variant="outline" icon="close" label={t('settings_sync_dismiss_label')} onPress={onDismiss} />
    </Card>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <View style={styles.section}>
      <AppText size={18} weight="700" accessibilityRole="header">{title}</AppText>
      {children}
    </View>
  );
}

function SwitchRow({ label, value, onValueChange, testID }: { label: string; value: boolean; onValueChange: (v: boolean) => void; testID: string }) {
  return (
    <View style={styles.switchRow}>
      <AppText style={styles.flex}>{label}</AppText>
      <Switch testID={testID} value={value} onValueChange={onValueChange} accessibilityLabel={label} />
    </View>
  );
}

function CountryPicker({ visible, selected, onClose, onSelect }: { visible: boolean; selected: string | null; onClose: () => void; onSelect: (code: string | null) => void }) {
  const { t, locale } = useT();
  const theme = useTheme();
  const [query, setQuery] = useState('');
  const rows = useMemo(() => {
    const needle = query.trim().toLocaleLowerCase(locale);
    return Object.entries(COUNTRY_NAMES)
      .map(([code, names]) => ({ code, name: names[locale] }))
      .filter((r) => needle === '' || r.name.toLocaleLowerCase(locale).includes(needle) || r.code.toLowerCase() === needle)
      .sort((a, b) => a.name.localeCompare(b.name, locale));
  }, [query, locale]);

  return (
    <Modal visible={visible} animationType="slide" onRequestClose={onClose}>
      <View testID="settings-country-picker" style={[styles.modal, { backgroundColor: theme.colors.background }]}>
        <View style={styles.modalHeader}>
          <AppText size={20} weight="700" accessibilityRole="header" style={styles.flex}>{t('settings_country_label')}</AppText>
          <Pressable testID="settings-country-close" onPress={onClose} accessibilityRole="button" accessibilityLabel={t('back')} style={styles.iconButton}>
            <MaterialCommunityIcons name="close" size={theme.fs(26)} color={theme.colors.text} />
          </Pressable>
        </View>
        <TextInput
          testID="settings-country-search"
          value={query}
          onChangeText={setQuery}
          placeholder={t('settings_country_search_placeholder')}
          placeholderTextColor={theme.colors.mutedText}
          accessibilityLabel={t('settings_country_search_placeholder')}
          style={[styles.search, { color: theme.colors.text, borderColor: theme.colors.border, borderWidth: theme.borderWidth, fontSize: theme.fs(16) }]}
        />
        <RadioRow testID="settings-country-none" label={t('settings_country_not_set')} selected={selected === null} onPress={() => onSelect(null)} />
        <FlatList
          data={rows}
          keyExtractor={(r) => r.code}
          keyboardShouldPersistTaps="handled"
          renderItem={({ item }) => (
            <RadioRow testID={`settings-country-${item.code}`} label={item.name} selected={selected === item.code} onPress={() => onSelect(item.code)} />
          )}
        />
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  content: { padding: spacing.m, gap: spacing.l },
  section: { gap: spacing.s },
  switchRow: { minHeight: 48, flexDirection: 'row', alignItems: 'center', gap: 12 },
  flex: { flex: 1 },
  modal: { flex: 1, padding: spacing.m, gap: spacing.s, paddingTop: 48 },
  modalHeader: { flexDirection: 'row', alignItems: 'center' },
  iconButton: { minWidth: 48, minHeight: 48, alignItems: 'center', justifyContent: 'center' },
  search: { minHeight: 48, borderRadius: 12, paddingHorizontal: 12 },
});
