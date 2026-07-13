/* eslint-disable @typescript-eslint/no-require-imports */
import { render, waitFor } from '@testing-library/react-native';
import type { ReactNode } from 'react';

import RootLayout from '../../app/_layout';

jest.mock('expo-router', () => {
  const React = require('react');
  const { View } = require('react-native');
  function Stack({ children }: { children?: ReactNode }) {
    return React.createElement(View, { testID: 'root-stack' }, children);
  }
  Stack.Screen = function Screen() {
    return null;
  };
  const baseTheme = {
    colors: {
      background: '#000000',
      border: '#111111',
      card: '#000000',
      notification: '#ff0000',
      primary: '#ffffff',
      text: '#ffffff',
    },
  };
  return {
    DarkTheme: baseTheme,
    DefaultTheme: baseTheme,
    Stack,
    ThemeProvider: function ThemeProvider({ children }: { children: ReactNode }) {
      return children;
    },
  };
});

jest.mock('@/settings/settings-storage', () => {
  const defaultSettings = {
    apiBaseUrl: 'http://localhost:8080/api/v1',
    currencyCode: 'USD',
    theme: 'dark',
    timezone: 'America/Indianapolis',
  };
  return {
    defaultSettings,
    loadSettings: jest.fn().mockResolvedValue(defaultSettings),
    saveSettings: jest.fn(),
    settingsSchema: { parse: (value: unknown) => value },
  };
});

jest.mock('@/auth/auth-storage', () => ({
  clearSession: jest.fn(),
  getStoredSession: jest.fn().mockResolvedValue(null),
  storeSession: jest.fn(),
}));

describe('app root providers', () => {
  it('keeps gesture-enabled screens inside GestureHandlerRootView', async () => {
    const view = await render(<RootLayout />);

    expect(view.getByTestId('gesture-handler-root')).toBeTruthy();
    await waitFor(() => expect(view.getByTestId('root-stack')).toBeTruthy());
  });
});
