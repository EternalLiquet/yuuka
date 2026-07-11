import { useColorScheme } from 'react-native';

import { useSettings } from '@/settings/settings-provider';

import { ColorMode, colors } from './colors';

export function useAppTheme() {
  const scheme = useColorScheme();
  const { settings } = useSettings();
  const colorMode: ColorMode =
    settings.theme === 'system' ? (scheme === 'light' ? 'light' : 'dark') : settings.theme;

  return {
    colorMode,
    colors: colors[colorMode],
  };
}
