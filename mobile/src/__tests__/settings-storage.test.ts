import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';

import {
  defaultSettings,
  loadSettings,
  saveSettings,
  settingsSchema,
} from '@/settings/settings-storage';

jest.mock('expo-secure-store', () => ({
  WHEN_UNLOCKED_THIS_DEVICE_ONLY: 'WHEN_UNLOCKED_THIS_DEVICE_ONLY',
  deleteItemAsync: jest.fn(),
  getItemAsync: jest.fn(),
  setItemAsync: jest.fn(),
}));

describe('settings storage', () => {
  const getItem = jest.mocked(SecureStore.getItemAsync);
  const setItem = jest.mocked(SecureStore.setItemAsync);
  const deleteItem = jest.mocked(SecureStore.deleteItemAsync);

  const originalPlatform = Platform.OS;

  beforeEach(() => jest.clearAllMocks());

  afterEach(() => {
    Object.defineProperty(Platform, 'OS', { configurable: true, value: originalPlatform });
    delete (globalThis as { localStorage?: Storage }).localStorage;
  });

  it('defaults to dark mode and normalizes locale settings', () => {
    expect(defaultSettings.theme).toBe('dark');
    expect(
      settingsSchema.parse({
        apiBaseUrl: 'http://localhost:8080/api/v1/',
        currencyCode: 'usd',
        theme: 'system',
        timezone: 'America/Indianapolis',
      }),
    ).toMatchObject({ apiBaseUrl: 'http://localhost:8080/api/v1', currencyCode: 'USD' });
  });

  it('returns defaults when storage is empty or corrupt and clears corrupt data', async () => {
    getItem.mockResolvedValueOnce(null);
    await expect(loadSettings()).resolves.toBe(defaultSettings);

    getItem.mockResolvedValueOnce('{not-json');
    await expect(loadSettings()).resolves.toBe(defaultSettings);
    expect(deleteItem).toHaveBeenCalled();
  });

  it('loads and saves validated native settings', async () => {
    const stored = {
      apiBaseUrl: 'http://localhost:8080/api/v1',
      currencyCode: 'USD',
      theme: 'light' as const,
      timezone: 'UTC',
    };
    getItem.mockResolvedValueOnce(JSON.stringify(stored));

    await expect(loadSettings()).resolves.toEqual(stored);
    await saveSettings(stored);
    expect(setItem).toHaveBeenCalledWith(
      'yuuka.settings.v1',
      expect.any(String),
      expect.objectContaining({ keychainAccessible: 'WHEN_UNLOCKED_THIS_DEVICE_ONLY' }),
    );
    expect(JSON.parse(setItem.mock.calls[0][1])).toEqual(stored);
  });

  it('uses localStorage on web and clears corrupt web data', async () => {
    Object.defineProperty(Platform, 'OS', { configurable: true, value: 'web' });
    const values = new Map<string, string>();
    const localStorage = {
      getItem: jest.fn((key: string) => values.get(key) ?? null),
      removeItem: jest.fn((key: string) => values.delete(key)),
      setItem: jest.fn((key: string, value: string) => values.set(key, value)),
    } as unknown as Storage;
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      value: localStorage,
    });
    const stored = {
      apiBaseUrl: 'http://localhost:8080/api/v1',
      currencyCode: 'USD',
      theme: 'dark' as const,
      timezone: 'UTC',
    };

    await saveSettings(stored);
    await expect(loadSettings()).resolves.toEqual(stored);
    values.set('yuuka.settings.v1', '{bad-json');
    await expect(loadSettings()).resolves.toBe(defaultSettings);
    expect(localStorage.removeItem).toHaveBeenCalledWith('yuuka.settings.v1');
  });
});
