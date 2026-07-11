import { Stack, ThemeProvider } from 'expo-router';
import { usePreventScreenCapture } from 'expo-screen-capture';
import { StatusBar } from 'expo-status-bar';
import 'react-native-reanimated';

import { AuthProvider } from '@/auth/auth-provider';
import { Screen } from '@/components/screen';
import { QueryProvider } from '@/providers/query-provider';
import { SettingsProvider, useSettings } from '@/settings/settings-provider';
import { navigationThemes } from '@/theme/navigation-theme';
import { useAppTheme } from '@/theme/use-app-theme';

export { ErrorBoundary } from 'expo-router';

export const unstable_settings = {
  initialRouteName: 'index',
};

export default function RootLayout() {
  usePreventScreenCapture();

  return (
    <SettingsProvider>
      <RootProviders />
    </SettingsProvider>
  );
}

function RootProviders() {
  const { isLoading } = useSettings();
  if (isLoading) {
    return <Screen />;
  }
  return (
    <QueryProvider>
      <AuthProvider>
        <RootNavigation />
      </AuthProvider>
    </QueryProvider>
  );
}

function RootNavigation() {
  const { colorMode, colors } = useAppTheme();
  return (
    <ThemeProvider value={navigationThemes[colorMode]}>
      <Stack
        screenOptions={{
          headerShown: false,
          contentStyle: { backgroundColor: colors.background },
        }}
      />
      <StatusBar style={colorMode === 'dark' ? 'light' : 'dark'} />
    </ThemeProvider>
  );
}
