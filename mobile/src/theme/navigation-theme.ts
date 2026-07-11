import { DarkTheme, DefaultTheme } from 'expo-router';

import { colors } from './colors';

export const navigationThemes = {
  dark: {
    ...DarkTheme,
    colors: {
      ...DarkTheme.colors,
      background: colors.dark.background,
      border: colors.dark.border,
      card: colors.dark.surface,
      notification: colors.dark.danger,
      primary: colors.dark.accent,
      text: colors.dark.text,
    },
  },
  light: {
    ...DefaultTheme,
    colors: {
      ...DefaultTheme.colors,
      background: colors.light.background,
      border: colors.light.border,
      card: colors.light.surface,
      notification: colors.light.danger,
      primary: colors.light.accent,
      text: colors.light.text,
    },
  },
};
