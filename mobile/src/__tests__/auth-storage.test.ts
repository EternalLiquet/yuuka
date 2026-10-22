import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';

import { clearSession, getStoredSession, storeSession } from '@/auth/auth-storage';
import { AuthStorageError } from '@/auth/auth-storage-error';
import type { AuthSession } from '@/auth/session';

jest.mock('expo-secure-store', () => ({
  WHEN_UNLOCKED_THIS_DEVICE_ONLY: 'when-unlocked-this-device-only',
  deleteItemAsync: jest.fn(),
  getItemAsync: jest.fn(),
  setItemAsync: jest.fn(),
}));

const secureStore = jest.mocked(SecureStore);

function session(overrides: Partial<AuthSession> = {}): AuthSession {
  return {
    accessToken: 'access-token',
    expiresAt: '2099-07-10T12:00:00.000Z',
    refreshExpiresAt: '2099-08-10T12:00:00.000Z',
    refreshToken: 'r'.repeat(48),
    tokenType: 'Bearer',
    ...overrides,
  };
}

describe('auth storage', () => {
  beforeEach(() => {
    jest.resetAllMocks();
    secureStore.deleteItemAsync.mockResolvedValue(undefined);
    secureStore.setItemAsync.mockResolvedValue(undefined);
  });

  it('returns null when nothing is stored', async () => {
    secureStore.getItemAsync.mockResolvedValue(null);
    await expect(getStoredSession()).resolves.toBeNull();
    expect(secureStore.deleteItemAsync).not.toHaveBeenCalled();
  });

  it('returns a valid stored session', async () => {
    secureStore.getItemAsync.mockResolvedValue(JSON.stringify(session()));
    await expect(getStoredSession()).resolves.toEqual(session());
  });

  it('retains the issuing server when reading stored credentials', async () => {
    const stored = { ...session(), apiBaseUrl: 'https://issuer.test/api/v1' };
    secureStore.getItemAsync.mockResolvedValue(JSON.stringify(stored));
    await expect(getStoredSession()).resolves.toEqual(stored);
  });

  it('deletes corrupt data and refresh-expired sessions as definitively unusable', async () => {
    secureStore.getItemAsync.mockResolvedValueOnce('not json');
    await expect(getStoredSession()).resolves.toBeNull();
    secureStore.getItemAsync.mockResolvedValueOnce(
      JSON.stringify(session({ refreshExpiresAt: '2020-01-01T00:00:00.000Z' })),
    );
    await expect(getStoredSession()).resolves.toBeNull();
    expect(secureStore.deleteItemAsync).toHaveBeenCalledTimes(2);
  });

  it('surfaces a read failure without deleting the saved sign-in', async () => {
    secureStore.getItemAsync.mockRejectedValue(new Error('keystore locked'));
    await expect(getStoredSession()).rejects.toBeInstanceOf(AuthStorageError);
    expect(secureStore.deleteItemAsync).not.toHaveBeenCalled();
  });

  it('tags write and clear failures as storage errors without leaking details', async () => {
    secureStore.setItemAsync.mockRejectedValue(new Error('secret-token-value'));
    secureStore.deleteItemAsync.mockRejectedValue(new Error('secret-token-value'));
    const write = await storeSession(session()).catch((error: unknown) => error);
    const clear = await clearSession().catch((error: unknown) => error);
    expect(write).toBeInstanceOf(AuthStorageError);
    expect(clear).toBeInstanceOf(AuthStorageError);
    expect((write as Error).message).not.toMatch(/secret/);
  });

  it('stores with device-local accessibility', async () => {
    await storeSession(session());
    expect(secureStore.setItemAsync).toHaveBeenCalledWith(
      'yuuka.auth.session',
      JSON.stringify(session()),
      { keychainAccessible: 'when-unlocked-this-device-only' },
    );
  });

  describe('web session storage', () => {
    const originalOs = Platform.OS;
    beforeEach(() => {
      Object.defineProperty(Platform, 'OS', { configurable: true, value: 'web' });
    });
    afterEach(() => {
      Object.defineProperty(Platform, 'OS', { configurable: true, value: originalOs });
      delete (globalThis as { sessionStorage?: unknown }).sessionStorage;
    });

    it('reads, writes, and clears sessionStorage', async () => {
      const data = new Map<string, string>();
      Object.defineProperty(globalThis, 'sessionStorage', {
        configurable: true,
        value: {
          getItem: (key: string) => data.get(key) ?? null,
          removeItem: (key: string) => data.delete(key),
          setItem: (key: string, value: string) => data.set(key, value),
        },
      });
      await storeSession(session());
      await expect(getStoredSession()).resolves.toEqual(session());
      await clearSession();
      await expect(getStoredSession()).resolves.toBeNull();
      expect(secureStore.setItemAsync).not.toHaveBeenCalled();
    });
  });
});
