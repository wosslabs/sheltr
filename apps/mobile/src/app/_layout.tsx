import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { AppProvider } from '../platform/AppProvider';
import { useServices } from '../platform/AppContext';
import { ThemeProvider, useTheme } from '../ui/theme';
import { useT } from '../ui/useT';

function Screens() {
  const { t } = useT();
  const { colors } = useTheme();
  return (
    <>
      <StatusBar style="auto" />
      <Stack
        screenOptions={{
          headerStyle: { backgroundColor: colors.background },
          headerTintColor: colors.text,
          contentStyle: { backgroundColor: colors.background },
          headerBackButtonDisplayMode: 'minimal',
        }}
      >
        <Stack.Screen name="index" options={{ title: t('app_name') }} />
        <Stack.Screen name="search" options={{ title: t('search_title') }} />
        <Stack.Screen name="answer" options={{ title: t('app_name') }} />
        <Stack.Screen name="settings" options={{ title: t('settings_title') }} />
        <Stack.Screen name="about" options={{ title: t('about_title') }} />
      </Stack>
    </>
  );
}

function Themed() {
  const { settings } = useServices();
  return (
    <ThemeProvider settings={settings}>
      <Screens />
    </ThemeProvider>
  );
}

export default function RootLayout() {
  return (
    <AppProvider>
      <Themed />
    </AppProvider>
  );
}
