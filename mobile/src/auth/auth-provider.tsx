import {
  createContext,
  PropsWithChildren,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { useQueryClient } from '@tanstack/react-query';

import { apiRequest, ApiRequestOptions } from '@/api/api-client';
import { useSettings } from '@/settings/settings-provider';

import { login, logout, refreshSession } from './auth-api';
import { clearSession, getStoredSession, storeSession } from './auth-storage';
import { SignInFormValues } from './schemas';
import { AuthSession, isAccessTokenExpired, isRefreshTokenExpired } from './session';

type AuthContextValue = {
  authenticatedRequest: (path: string, options?: ApiRequestOptions) => Promise<Response>;
  isLoading: boolean;
  session: AuthSession | null;
  sessionExpired: boolean;
  signIn: (values: SignInFormValues) => Promise<void>;
  signOut: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: PropsWithChildren) {
  const { settings } = useSettings();
  const queryClient = useQueryClient();
  const [isLoading, setIsLoading] = useState(true);
  const [session, setSessionState] = useState<AuthSession | null>(null);
  const [sessionExpired, setSessionExpired] = useState(false);
  const sessionRef = useRef<AuthSession | null>(null);
  const refreshInFlight = useRef<Promise<AuthSession> | null>(null);

  const setSession = useCallback((next: AuthSession | null) => {
    sessionRef.current = next;
    setSessionState(next);
  }, []);

  const expireSession = useCallback(async () => {
    await clearSession();
    setSession(null);
    setSessionExpired(true);
    queryClient.clear();
  }, [queryClient, setSession]);

  const rotate = useCallback(
    async (current: AuthSession): Promise<AuthSession> => {
      if (isRefreshTokenExpired(current)) {
        await expireSession();
        throw new Error('Session expired');
      }
      if (!refreshInFlight.current) {
        refreshInFlight.current = refreshSession(settings.apiBaseUrl, current.refreshToken)
          .then(async (next) => {
            await storeSession(next);
            setSession(next);
            return next;
          })
          .catch(async (error) => {
            await expireSession();
            throw error;
          })
          .finally(() => {
            refreshInFlight.current = null;
          });
      }
      return refreshInFlight.current;
    },
    [expireSession, setSession, settings.apiBaseUrl],
  );

  useEffect(() => {
    let active = true;
    getStoredSession()
      .then(async (stored) => {
        if (!active || !stored) return;
        if (isAccessTokenExpired(stored)) {
          await rotate(stored);
        } else {
          setSession(stored);
        }
      })
      .catch(() => undefined)
      .finally(() => {
        if (active) setIsLoading(false);
      });
    return () => {
      active = false;
    };
  }, [rotate, setSession]);

  const authenticatedRequest = useCallback(
    async (path: string, options: ApiRequestOptions = {}) => {
      let current = sessionRef.current;
      if (!current) {
        throw new Error('Authentication is required');
      }
      if (isAccessTokenExpired(current)) {
        current = await rotate(current);
      }
      const execute = (activeSession: AuthSession) =>
        apiRequest(`${settings.apiBaseUrl}${path}`, {
          ...options,
          headers: {
            ...options.headers,
            Authorization: `${activeSession.tokenType} ${activeSession.accessToken}`,
          },
        });
      let response = await execute(current);
      if (response.status === 401) {
        current = await rotate(current);
        response = await execute(current);
      }
      return response;
    },
    [rotate, settings.apiBaseUrl],
  );

  const value = useMemo<AuthContextValue>(
    () => ({
      authenticatedRequest,
      isLoading,
      session,
      sessionExpired,
      signIn: async (values) => {
        const next = await login(settings.apiBaseUrl, values);
        await storeSession(next);
        setSessionExpired(false);
        setSession(next);
      },
      signOut: async () => {
        const current = sessionRef.current;
        if (current) {
          await logout(settings.apiBaseUrl, current.refreshToken).catch(() => undefined);
        }
        await clearSession();
        queryClient.clear();
        setSession(null);
        setSessionExpired(false);
      },
    }),
    [
      authenticatedRequest,
      isLoading,
      queryClient,
      session,
      sessionExpired,
      setSession,
      settings.apiBaseUrl,
    ],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const value = useContext(AuthContext);
  if (!value) {
    throw new Error('useAuth must be used inside AuthProvider');
  }
  return value;
}
