import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';
import { z } from 'zod';

import { defaultApiBaseUrl, normalizeApiBaseUrl } from '@/config/env';

export const themePreferenceSchema = z.enum(['dark', 'light', 'system']);
export type ThemePreference = z.infer<typeof themePreferenceSchema>;

export const settingsSchema = z.object({
  apiBaseUrl: z.string().transform(normalizeApiBaseUrl),
  theme: themePreferenceSchema,
  timezone: z.string().min(1).max(64),
  currencyCode: z
    .string()
    .length(3)
    .transform((value) => value.toUpperCase()),
});
export type AppSettings = z.infer<typeof settingsSchema>;

const SETTINGS_KEY = 'yuuka.settings.v1';
const secureStoreOptions: SecureStore.SecureStoreOptions = {
  keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY,
};

export const defaultSettings: AppSettings = {
  apiBaseUrl: defaultApiBaseUrl,
  theme: 'dark',
  timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
  currencyCode: 'USD',
};

export async function loadSettings(): Promise<AppSettings> {
  const raw = await readValue();
  if (!raw) {
    return defaultSettings;
  }
  try {
    return settingsSchema.parse(JSON.parse(raw));
  } catch {
    await clearValue();
    return defaultSettings;
  }
}

export async function saveSettings(settings: AppSettings): Promise<void> {
  const value = JSON.stringify(settingsSchema.parse(settings));
  if (Platform.OS === 'web') {
    globalThis.localStorage?.setItem(SETTINGS_KEY, value);
    return;
  }
  await SecureStore.setItemAsync(SETTINGS_KEY, value, secureStoreOptions);
}

async function readValue() {
  if (Platform.OS === 'web') {
    return globalThis.localStorage?.getItem(SETTINGS_KEY) ?? null;
  }
  return SecureStore.getItemAsync(SETTINGS_KEY);
}

async function clearValue() {
  if (Platform.OS === 'web') {
    globalThis.localStorage?.removeItem(SETTINGS_KEY);
    return;
  }
  await SecureStore.deleteItemAsync(SETTINGS_KEY);
}
