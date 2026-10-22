/* eslint-disable @typescript-eslint/no-require-imports */
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { ReactNode } from 'react';
import type { useAuth } from '@/auth/auth-provider';
import { clearSession, getStoredSession } from '@/auth/auth-storage';
import { AuthStorageError } from '@/auth/auth-storage-error';
import type { AuthSession } from '@/auth/session';

import RootLayout from '../../app/_layout';
let mockRootAuth: ReturnType<typeof useAuth>;

jest.mock('expo-router', () => {
  const React = require('react');
  const { View } = require('react-native');
  function Stack({ children }: { children?: ReactNode }) {
    mockRootAuth = require('@/auth/auth-provider').useAuth();
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
jest.mock('@/auth/auth-api', () => ({ logout: jest.fn().mockResolvedValue(undefined) }));

describe('app root providers', () => {
  it('keeps gesture-enabled screens inside GestureHandlerRootView', async () => {
    const view = await render(<RootLayout />);

    expect(view.getByTestId('gesture-handler-root')).toBeTruthy();
    await waitFor(() => expect(view.getByTestId('root-stack')).toBeTruthy());
  });

  it('unmounts every route during failed sign-out and retries before permitting a clean relaunch', async () => {
    let stored: AuthSession | null = {
      accessToken: 'access-token',
      tokenType: 'Bearer',
      refreshToken: 'r'.repeat(48),
      expiresAt: '2099-07-10T12:00:00.000Z',
      refreshExpiresAt: '2099-08-10T12:00:00.000Z',
    };
    jest.mocked(getStoredSession).mockImplementation(async () => stored);
    jest
      .mocked(clearSession)
      .mockRejectedValueOnce(new AuthStorageError('clear'))
      .mockImplementationOnce(async () => {
        stored = null;
      });
    const view = await render(<RootLayout />);
    await waitFor(() => expect(mockRootAuth.session).not.toBeNull());
    await act(async () => {
      await mockRootAuth.signOut().catch(() => undefined);
    });
    expect(view.queryByTestId('root-stack')).toBeNull();
    expect(view.getByText('Sign-out incomplete')).toBeTruthy();
    expect(stored).not.toBeNull();
    await fireEvent.press(view.getByRole('button', { name: 'Retry sign out' }));
    expect(stored).toBeNull();
    expect(view.getByTestId('root-stack')).toBeTruthy();
    expect(mockRootAuth.session).toBeNull();
    await view.unmount();
    await render(<RootLayout />);
    await waitFor(() => expect(mockRootAuth.status).toBe('signedOut'));
    expect(mockRootAuth.session).toBeNull();
  });
});
