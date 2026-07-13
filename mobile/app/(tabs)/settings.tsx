import { useQuery } from '@tanstack/react-query';
import { CheckCircle2, LogOut, Save, Server, WifiOff } from 'lucide-react-native';
import { useState } from 'react';
import type { PropsWithChildren } from 'react';
import { StyleSheet, View } from 'react-native';

import { apiRequest, parseApiResponse } from '@/api/api-client';
import { versionResponseSchema } from '@/api/contracts';
import { formatYuukaVersionFooter } from '@/api/version-format';
import { useAuth } from '@/auth/auth-provider';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { ScrollScreen } from '@/components/scroll-screen';
import { SegmentedControl } from '@/components/segmented-control';
import { TextField } from '@/components/text-field';
import { normalizeApiBaseUrl } from '@/config/env';
import { ThemePreference } from '@/settings/settings-storage';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

const themeOptions = [
  { label: 'Dark', value: 'dark' },
  { label: 'Light', value: 'light' },
  { label: 'System', value: 'system' },
] as const;

export default function SettingsScreen() {
  const { settings } = useSettings();

  return (
    <SettingsDraft key={`${settings.apiBaseUrl}|${settings.timezone}|${settings.currencyCode}`} />
  );
}

function SettingsDraft() {
  const { signOut } = useAuth();
  const { settings, updateSettings } = useSettings();
  const { colors } = useAppTheme();
  const [apiBaseUrl, setApiBaseUrl] = useState(settings.apiBaseUrl);
  const [timezone, setTimezone] = useState(settings.timezone);
  const [currencyCode, setCurrencyCode] = useState(settings.currencyCode);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const health = useQuery({
    queryKey: ['health', settings.apiBaseUrl],
    queryFn: async () => {
      const response = await apiRequest(`${apiOrigin(settings.apiBaseUrl)}/health/ready`, {
        timeoutMs: 5000,
      });
      if (!response.ok) throw new Error('Unavailable');
      return true;
    },
    refetchInterval: 30_000,
    retry: false,
  });
  const version = useQuery({
    queryKey: ['version', settings.apiBaseUrl],
    queryFn: async () => {
      const response = await apiRequest(`${apiOrigin(settings.apiBaseUrl)}/health/version`, {
        timeoutMs: 5000,
      });
      return parseApiResponse(response, versionResponseSchema);
    },
    retry: false,
    staleTime: 300_000,
  });

  async function save() {
    setSaving(true);
    setError('');
    try {
      const normalized = normalizeApiBaseUrl(apiBaseUrl);
      const changedServer = normalized !== settings.apiBaseUrl;
      await updateSettings({
        apiBaseUrl: normalized,
        timezone: timezone.trim(),
        currencyCode: currencyCode.trim().toUpperCase(),
      });
      if (changedServer) await signOut();
    } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : 'Settings were not saved.');
    } finally {
      setSaving(false);
    }
  }

  function checkNow() {
    void Promise.allSettled([health.refetch(), version.refetch()]);
  }

  return (
    <ScrollScreen contentContainerStyle={styles.content}>
      <View style={styles.titleBlock}>
        <AppText variant="title">Settings</AppText>
        <View style={styles.connection}>
          {health.isSuccess ? (
            <CheckCircle2 color={colors.posted} size={17} />
          ) : (
            <WifiOff color={colors.danger} size={17} />
          )}
          <AppText
            style={{ color: health.isSuccess ? colors.posted : colors.danger }}
            variant="caption"
          >
            {health.isPending
              ? 'Checking connection'
              : health.isSuccess
                ? 'API connected'
                : 'API unavailable'}
          </AppText>
        </View>
      </View>

      <Section title="Connection">
        <TextField
          autoCapitalize="none"
          autoCorrect={false}
          label="API base URL"
          onChangeText={setApiBaseUrl}
          value={apiBaseUrl}
        />
        <Button icon={Server} label="Check now" onPress={checkNow} variant="secondary" />
      </Section>

      <Section title="Appearance">
        <SegmentedControl
          label="Theme preference"
          onChange={(theme: ThemePreference) => updateSettings({ theme })}
          options={themeOptions}
          value={settings.theme}
        />
      </Section>

      <Section title="Locale">
        <TextField
          autoCapitalize="none"
          label="Timezone"
          onChangeText={setTimezone}
          value={timezone}
        />
        <TextField
          autoCapitalize="characters"
          label="Currency"
          maxLength={3}
          onChangeText={setCurrencyCode}
          value={currencyCode}
        />
      </Section>

      {error ? (
        <AppText style={{ color: colors.danger }} variant="error">
          {error}
        </AppText>
      ) : null}
      <Button icon={Save} label="Save settings" loading={saving} onPress={save} />
      <Button icon={LogOut} label="Sign out" onPress={signOut} variant="danger" />
      <AppText style={[styles.version, { color: colors.muted }]} variant="caption">
        {version.isPending
          ? 'Yuuka · Checking version'
          : formatYuukaVersionFooter(version.data?.version)}
      </AppText>
    </ScrollScreen>
  );
}

function Section({ children, title }: PropsWithChildren<{ title: string }>) {
  const { colors } = useAppTheme();
  return (
    <View style={[styles.section, { borderTopColor: colors.border }]}>
      <AppText variant="label">{title}</AppText>
      {children}
    </View>
  );
}

function apiOrigin(apiBaseUrl: string) {
  return apiBaseUrl.replace(/\/api\/v1\/?$/, '');
}

const styles = StyleSheet.create({
  connection: { alignItems: 'center', flexDirection: 'row', gap: 7 },
  content: { gap: 20, paddingBottom: 32 },
  section: { borderTopWidth: 1, gap: 14, paddingTop: 18 },
  titleBlock: { gap: 7 },
  version: { alignSelf: 'center', marginTop: 2 },
});
