import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';

import { AuthSession, authSessionSchema, isRefreshTokenExpired } from './session';

const SESSION_KEY = 'yuuka.auth.session';
const secureStoreOptions: SecureStore.SecureStoreOptions = {
  keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY,
};

export async function getStoredSession(): Promise<AuthSession | null> {
  const value = await readValue();
  if (!value) {
    return null;
  }

  try {
    const session = authSessionSchema.parse(JSON.parse(value));
    if (isRefreshTokenExpired(session)) {
      await clearSession();
      return null;
    }
    return session;
  } catch {
    await clearSession();
    return null;
  }
}

export async function storeSession(session: AuthSession) {
  await writeValue(JSON.stringify(session));
}

export async function clearSession() {
  if (Platform.OS === 'web') {
    globalThis.sessionStorage?.removeItem(SESSION_KEY);
    return;
  }

  await SecureStore.deleteItemAsync(SESSION_KEY);
}

async function readValue() {
  if (Platform.OS === 'web') {
    return globalThis.sessionStorage?.getItem(SESSION_KEY) ?? null;
  }

  return SecureStore.getItemAsync(SESSION_KEY);
}

async function writeValue(value: string) {
  if (Platform.OS === 'web') {
    globalThis.sessionStorage?.setItem(SESSION_KEY, value);
    return;
  }

  await SecureStore.setItemAsync(SESSION_KEY, value, secureStoreOptions);
}
