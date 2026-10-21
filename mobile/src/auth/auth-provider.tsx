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
import { AppState } from 'react-native';
import { useQueryClient } from '@tanstack/react-query';

import { apiRequest, ApiRequestOptions } from '@/api/api-client';
import { useSettings } from '@/settings/settings-provider';

import { login, logout, refreshSession } from './auth-api';
import { clearSession, getStoredSession, storeSession } from './auth-storage';
import {
  AuthRecoverableError,
  classifyRefreshFailure,
  LocalRefreshExpiredError,
  RecoverableFailureKind,
} from './refresh-failure';
import { SignInFormValues } from './schemas';
import { AuthSession, isAccessTokenExpired, isRefreshTokenExpired } from './session';

export type AuthStatus = 'loading' | 'authenticated' | 'recovering' | 'signedOut';
export type AuthRecovery = { pending: boolean; reason: RecoverableFailureKind };

type AuthContextValue = {
  authenticatedRequest: (path: string, options?: ApiRequestOptions) => Promise<Response>;
  isLoading: boolean;
  recovery: AuthRecovery | null;
  retryRecovery: () => Promise<void>;
  /** Validated session only. It stays null while recovering. */
  session: AuthSession | null;
  sessionExpired: boolean;
  signIn: (values: SignInFormValues) => Promise<void>;
  signOut: () => Promise<void>;
  status: AuthStatus;
};

type AuthState = {
  recovery: AuthRecovery | null;
  session: AuthSession | null;
  sessionExpired: boolean;
  status: AuthStatus;
};

const INITIAL_STATE: AuthState = {
  recovery: null,
  session: null,
  sessionExpired: false,
  status: 'loading',
};
const SIGNED_OUT: AuthState = { ...INITIAL_STATE, status: 'signedOut' };

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: PropsWithChildren) {
  const { settings } = useSettings();
  const queryClient = useQueryClient();
  const [state, setState] = useState<AuthState>(INITIAL_STATE);
  const apiBaseUrlRef = useRef(settings.apiBaseUrl);
  useEffect(() => {
    apiBaseUrlRef.current = settings.apiBaseUrl;
  }, [settings.apiBaseUrl]);
  const statusRef = useRef<AuthStatus>('loading');
  // Validated session, handed to screens.
  const sessionRef = useRef<AuthSession | null>(null);
  // Saved but unvalidated session kept while recovering. Never handed to screens.
  const pendingSessionRef = useRef<AuthSession | null>(null);
  // Incremented on sign-in and sign-out; stale async continuations become no-ops.
  const epochRef = useRef(0);
  const refreshInFlight = useRef<Promise<AuthSession> | null>(null);
  const retryInFlight = useRef<Promise<void> | null>(null);
  // Secure-storage operations run strictly in order so late writes cannot
  // overwrite a newer sign-in or resurrect a signed-out session.
  const storageQueue = useRef<Promise<unknown>>(Promise.resolve());
  const started = useRef(false);

  const applyState = useCallback((next: AuthState) => {
    statusRef.current = next.status;
    setState(next);
  }, []);

  const enqueueStorage = useCallback(<T,>(operation: () => Promise<T>): Promise<T> => {
    const run = storageQueue.current.then(
      () => operation(),
      () => operation(),
    );
    storageQueue.current = run.catch(() => undefined);
    return run;
  }, []);

  const invalidate = useCallback(() => {
    epochRef.current += 1;
    refreshInFlight.current = null;
    retryInFlight.current = null;
  }, []);

  const bestEffortLogout = useCallback((refreshToken: string) => {
    void logout(apiBaseUrlRef.current, refreshToken).catch(() => undefined);
  }, []);

  const expireSession = useCallback(
    async (epoch: number) => {
      if (epochRef.current !== epoch) return;
      sessionRef.current = null;
      pendingSessionRef.current = null;
      applyState({ ...SIGNED_OUT, sessionExpired: true });
      queryClient.clear();
      await enqueueStorage(async () => {
        if (epochRef.current === epoch) await clearSession();
      }).catch(() => undefined);
    },
    [applyState, enqueueStorage, queryClient],
  );

  const enterRecovery = useCallback(
    (reason: RecoverableFailureKind) => {
      sessionRef.current = null;
      applyState({
        recovery: { pending: false, reason },
        session: null,
        sessionExpired: false,
        status: 'recovering',
      });
    },
    [applyState],
  );

  const runRefresh = useCallback(
    (requested: AuthSession): Promise<AuthSession> => {
      if (refreshInFlight.current) return refreshInFlight.current;
      const epoch = epochRef.current;
      // A rotated-but-unsaved session supersedes the stale refresh token.
      const candidate = pendingSessionRef.current ?? requested;
      const attempt = (async (): Promise<AuthSession> => {
        let rotated: AuthSession | null = null;
        try {
          if (isRefreshTokenExpired(candidate)) throw new LocalRefreshExpiredError();
          rotated = await refreshSession(apiBaseUrlRef.current, candidate.refreshToken);
          if (epochRef.current !== epoch) {
            // Signed out or replaced by a newer sign-in while refreshing.
            bestEffortLogout(rotated.refreshToken);
            throw new Error('Authentication is required');
          }
          const next = rotated;
          const written = await enqueueStorage(async () => {
            if (epochRef.current !== epoch) return false;
            await storeSession(next);
            return true;
          });
          if (!written || epochRef.current !== epoch) {
            bestEffortLogout(next.refreshToken);
            throw new Error('Authentication is required');
          }
          sessionRef.current = next;
          pendingSessionRef.current = null;
          applyState({
            recovery: null,
            session: next,
            sessionExpired: false,
            status: 'authenticated',
          });
          return next;
        } catch (error) {
          if (epochRef.current !== epoch) throw error;
          const kind = classifyRefreshFailure(error);
          if (kind === 'invalid') {
            await expireSession(epoch);
            throw error;
          }
          // The server may already have rotated the token: keep the newest one.
          if (rotated) pendingSessionRef.current = rotated;
          if (sessionRef.current) {
            // Already-open session: keep screens, forms, and cache untouched.
            throw new AuthRecoverableError(kind);
          }
          pendingSessionRef.current = rotated ?? candidate;
          enterRecovery(kind);
          throw new AuthRecoverableError(kind);
        }
      })();
      refreshInFlight.current = attempt;
      const clear = () => {
        if (refreshInFlight.current === attempt) refreshInFlight.current = null;
      };
      attempt.then(clear, clear);
      return attempt;
    },
    [applyState, bestEffortLogout, enqueueStorage, enterRecovery, expireSession],
  );

  const loadStored = useCallback(
    async (epoch: number) => {
      let stored: AuthSession | null;
      try {
        stored = await enqueueStorage(getStoredSession);
      } catch {
        if (epochRef.current === epoch) enterRecovery('storage');
        return;
      }
      if (epochRef.current !== epoch) return;
      if (!stored) {
        applyState(SIGNED_OUT);
      } else if (isAccessTokenExpired(stored)) {
        pendingSessionRef.current = stored;
        await runRefresh(stored).catch(() => undefined);
      } else {
        sessionRef.current = stored;
        applyState({
          recovery: null,
          session: stored,
          sessionExpired: false,
          status: 'authenticated',
        });
      }
    },
    [applyState, enqueueStorage, enterRecovery, runRefresh],
  );

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    void loadStored(epochRef.current);
  }, [loadStored]);

  const retryRecovery = useCallback((): Promise<void> => {
    if (statusRef.current !== 'recovering') return Promise.resolve();
    if (retryInFlight.current) return retryInFlight.current;
    const epoch = epochRef.current;
    setState((current) =>
      current.recovery ? { ...current, recovery: { ...current.recovery, pending: true } } : current,
    );
    const attempt = (async () => {
      const candidate = pendingSessionRef.current;
      if (candidate) {
        await runRefresh(candidate).catch(() => undefined);
      } else {
        await loadStored(epoch);
      }
    })();
    retryInFlight.current = attempt;
    const clear = () => {
      if (retryInFlight.current === attempt) retryInFlight.current = null;
    };
    attempt.then(clear, clear);
    return attempt;
  }, [loadStored, runRefresh]);

  useEffect(() => {
    const subscription = AppState.addEventListener('change', (next) => {
      if (next === 'active' && statusRef.current === 'recovering') void retryRecovery();
    });
    return () => subscription.remove();
  }, [retryRecovery]);

  const authenticatedRequest = useCallback(
    async (path: string, options: ApiRequestOptions = {}) => {
      let current = sessionRef.current;
      if (!current) {
        throw new Error('Authentication is required');
      }
      let refreshed = false;
      if (isAccessTokenExpired(current)) {
        current = await runRefresh(current);
        refreshed = true;
      }
      const execute = (activeSession: AuthSession) =>
        apiRequest(`${apiBaseUrlRef.current}${path}`, {
          ...options,
          headers: {
            ...options.headers,
            Authorization: `${activeSession.tokenType} ${activeSession.accessToken}`,
          },
        });
      let response = await execute(current);
      if (response.status === 401 && !refreshed) {
        current = await runRefresh(current);
        response = await execute(current);
      }
      return response;
    },
    [runRefresh],
  );

  const signIn = useCallback(
    async (values: SignInFormValues) => {
      const startEpoch = epochRef.current;
      const next = await login(apiBaseUrlRef.current, values);
      if (epochRef.current !== startEpoch) {
        // Signed out, or another sign-in won, while this one was in flight.
        bestEffortLogout(next.refreshToken);
        return;
      }
      invalidate();
      const epoch = epochRef.current;
      sessionRef.current = null;
      pendingSessionRef.current = null;
      try {
        await enqueueStorage(() => storeSession(next));
      } catch (error) {
        if (epochRef.current === epoch) applyState(SIGNED_OUT);
        throw error;
      }
      if (epochRef.current !== epoch) return;
      sessionRef.current = next;
      applyState({ recovery: null, session: next, sessionExpired: false, status: 'authenticated' });
    },
    [applyState, bestEffortLogout, enqueueStorage, invalidate],
  );

  const signOut = useCallback(async () => {
    invalidate();
    const candidate = pendingSessionRef.current ?? sessionRef.current;
    sessionRef.current = null;
    pendingSessionRef.current = null;
    applyState(SIGNED_OUT);
    queryClient.clear();
    try {
      await enqueueStorage(clearSession);
    } finally {
      // Best effort and never awaited, so signing out works offline.
      if (candidate) bestEffortLogout(candidate.refreshToken);
    }
  }, [applyState, bestEffortLogout, enqueueStorage, invalidate, queryClient]);

  const value = useMemo<AuthContextValue>(
    () => ({
      authenticatedRequest,
      isLoading: state.status === 'loading',
      recovery: state.recovery,
      retryRecovery,
      session: state.session,
      sessionExpired: state.sessionExpired,
      signIn,
      signOut,
      status: state.status,
    }),
    [authenticatedRequest, retryRecovery, signIn, signOut, state],
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
