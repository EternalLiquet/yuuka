import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';

import { AuthStorageError } from './auth-storage-error';
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

  let session: AuthSession | null = null;
  try {
    session = authSessionSchema.parse(JSON.parse(value));
  } catch {
    session = null;
  }
  if (!session || isRefreshTokenExpired(session)) {
    // Definitively unusable: corrupt or past its refresh expiry.
    await clearSession();
    return null;
  }
  return session;
}

export async function storeSession(session: AuthSession) {
  await writeValue(JSON.stringify(session));
}

export async function clearSession() {
  try {
    if (Platform.OS === 'web') {
      globalThis.sessionStorage?.removeItem(SESSION_KEY);
      return;
    }

    await SecureStore.deleteItemAsync(SESSION_KEY);
  } catch {
    throw new AuthStorageError('clear');
  }
}

async function readValue() {
  try {
    if (Platform.OS === 'web') {
      return globalThis.sessionStorage?.getItem(SESSION_KEY) ?? null;
    }

    return await SecureStore.getItemAsync(SESSION_KEY);
  } catch {
    throw new AuthStorageError('read');
  }
}

async function writeValue(value: string) {
  try {
    if (Platform.OS === 'web') {
      globalThis.sessionStorage?.setItem(SESSION_KEY, value);
      return;
    }

    await SecureStore.setItemAsync(SESSION_KEY, value, secureStoreOptions);
  } catch {
    throw new AuthStorageError('write');
  }
}
