import { createContext, PropsWithChildren, useContext, useEffect, useMemo, useState } from 'react';

import {
  AppSettings,
  defaultSettings,
  loadSettings,
  saveSettings,
  settingsSchema,
} from './settings-storage';

type SettingsContextValue = {
  isLoading: boolean;
  settings: AppSettings;
  updateSettings: (next: Partial<AppSettings>) => Promise<void>;
};

const SettingsContext = createContext<SettingsContextValue | null>(null);

export function SettingsProvider({ children }: PropsWithChildren) {
  const [settings, setSettings] = useState(defaultSettings);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    loadSettings()
      .then(setSettings)
      .finally(() => setIsLoading(false));
  }, []);

  const value = useMemo<SettingsContextValue>(
    () => ({
      isLoading,
      settings,
      updateSettings: async (next) => {
        const merged = settingsSchema.parse({ ...settings, ...next });
        await saveSettings(merged);
        setSettings(merged);
      },
    }),
    [isLoading, settings],
  );

  return <SettingsContext.Provider value={value}>{children}</SettingsContext.Provider>;
}

export function useSettings() {
  const value = useContext(SettingsContext);
  if (!value) {
    throw new Error('useSettings must be used inside SettingsProvider');
  }
  return value;
}
