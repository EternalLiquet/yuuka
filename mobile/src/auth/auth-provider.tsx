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
import { clearSession, getStoredSession, storeSession, StoredAuthSession } from './auth-storage';
import {
  AuthRecoverableError,
  classifyRefreshFailure,
  LocalRefreshExpiredError,
  RecoverableFailureKind,
} from './refresh-failure';
import { SignInFormValues } from './schemas';
import { AuthSession, isAccessTokenExpired, isRefreshTokenExpired } from './session';

export type AuthStatus = 'loading' | 'authenticated' | 'recovering' | 'signedOut';
export type AuthRecovery = { pending: boolean; reason: RecoverableFailureKind | 'signOut' };

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
type BoundSession = AuthSession & { apiBaseUrl: string };

export function AuthProvider({ children }: PropsWithChildren) {
  const { settings } = useSettings();
  const queryClient = useQueryClient();
  const [state, setState] = useState<AuthState>(INITIAL_STATE);
  const apiBaseUrlRef = useRef(settings.apiBaseUrl);
  const statusRef = useRef<AuthStatus>('loading');
  // Validated session, handed to screens.
  const sessionRef = useRef<BoundSession | null>(null);
  // Saved but unvalidated session kept while recovering. Never handed to screens.
  const pendingSessionRef = useRef<BoundSession | null>(null);
  // Incremented on sign-in and sign-out; stale async continuations become no-ops.
  const epochRef = useRef(0);
  const refreshInFlight = useRef<Promise<BoundSession> | null>(null);
  const retryInFlight = useRef<Promise<void> | null>(null);
  const signOutInFlight = useRef<Promise<void> | null>(null);
  const cleanupRequired = useRef(false);
  const cleanupExpired = useRef(false);
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

  const bestEffortLogout = useCallback((session: BoundSession) => {
    void logout(session.apiBaseUrl, session.refreshToken).catch(() => undefined);
  }, []);

  const clearLocalSession = useCallback(
    (sessionExpired = false): Promise<void> => {
      if (signOutInFlight.current) return signOutInFlight.current;
      invalidate();
      const epoch = epochRef.current;
      const candidate = pendingSessionRef.current ?? sessionRef.current;
      sessionRef.current = null;
      pendingSessionRef.current = null;
      cleanupRequired.current = true;
      cleanupExpired.current = sessionExpired;
      const locked: AuthState = { ...SIGNED_OUT, sessionExpired, status: 'recovering' };
      applyState({ ...locked, recovery: { pending: true, reason: 'signOut' } });
      queryClient.clear();
      // Independent of storage and network availability; always use the issuer.
      if (candidate) bestEffortLogout(candidate);
      const attempt = enqueueStorage(clearSession).then(
        () => {
          if (epochRef.current !== epoch) return;
          cleanupRequired.current = false;
          applyState({ ...SIGNED_OUT, sessionExpired });
        },
        (error: unknown) => {
          if (epochRef.current === epoch) {
            applyState({ ...locked, recovery: { pending: false, reason: 'signOut' } });
          }
          throw error;
        },
      );
      signOutInFlight.current = attempt;
      const clear = () => {
        if (signOutInFlight.current === attempt) signOutInFlight.current = null;
      };
      attempt.then(clear, clear);
      return attempt;
    },
    [applyState, bestEffortLogout, enqueueStorage, invalidate, queryClient],
  );

  const signOut = useCallback(() => clearLocalSession(), [clearLocalSession]);

  const expireSession = useCallback(
    async (epoch: number) => {
      if (epochRef.current !== epoch) return;
      await clearLocalSession(true).catch(() => undefined);
    },
    [clearLocalSession],
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
    (requested: BoundSession): Promise<BoundSession> => {
      if (refreshInFlight.current) return refreshInFlight.current;
      const epoch = epochRef.current;
      // A rotated-but-unsaved session supersedes the stale refresh token.
      const candidate = pendingSessionRef.current ?? requested;
      const attempt = (async (): Promise<BoundSession> => {
        let rotated: BoundSession | null = null;
        try {
          if (isRefreshTokenExpired(candidate)) throw new LocalRefreshExpiredError();
          rotated = {
            ...(await refreshSession(candidate.apiBaseUrl, candidate.refreshToken)),
            apiBaseUrl: candidate.apiBaseUrl,
          };
          if (epochRef.current !== epoch) {
            // Signed out or replaced by a newer sign-in while refreshing.
            throw new Error('Authentication is required');
          }
          const next = rotated;
          const written = await enqueueStorage(async () => {
            if (epochRef.current !== epoch) return false;
            await storeSession(next);
            return true;
          });
          if (!written || epochRef.current !== epoch) {
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
          if (epochRef.current !== epoch) {
            if (rotated) bestEffortLogout(rotated);
            throw error;
          }
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
      const apiBaseUrl = apiBaseUrlRef.current;
      let stored: StoredAuthSession | null;
      try {
        stored = await enqueueStorage(getStoredSession);
      } catch {
        if (epochRef.current === epoch) enterRecovery('storage');
        return;
      }
      if (epochRef.current !== epoch) return;
      if (!stored) {
        applyState(SIGNED_OUT);
      } else {
        if (stored.apiBaseUrl && stored.apiBaseUrl !== apiBaseUrl) {
          await expireSession(epoch);
          return;
        }
        const bound = { ...stored, apiBaseUrl };
        if (isAccessTokenExpired(bound)) {
          pendingSessionRef.current = bound;
          await runRefresh(bound).catch(() => undefined);
          return;
        }
        sessionRef.current = bound;
        applyState({
          recovery: null,
          session: bound,
          sessionExpired: false,
          status: 'authenticated',
        });
      }
    },
    [applyState, enqueueStorage, enterRecovery, expireSession, runRefresh],
  );

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    void loadStored(epochRef.current);
  }, [loadStored]);

  const retryRecovery = useCallback((): Promise<void> => {
    if (statusRef.current !== 'recovering') return Promise.resolve();
    if (cleanupRequired.current) {
      return clearLocalSession(cleanupExpired.current).catch(() => undefined);
    }
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
  }, [clearLocalSession, loadStored, runRefresh]);

  useEffect(() => {
    const subscription = AppState.addEventListener('change', (next) => {
      if (next === 'active' && statusRef.current === 'recovering') void retryRecovery();
    });
    return () => subscription.remove();
  }, [retryRecovery]);

  const authenticatedRequest = useCallback(
    async (path: string, options: ApiRequestOptions = {}) => {
      const epoch = epochRef.current;
      const assertCurrent = () => {
        if (epochRef.current !== epoch) throw new Error('Authentication is required');
      };
      let current = sessionRef.current;
      if (!current) {
        throw new Error('Authentication is required');
      }
      let refreshed = false;
      if (isAccessTokenExpired(current)) {
        current = await runRefresh(current);
        assertCurrent();
        refreshed = true;
      }
      const execute = (activeSession: BoundSession) =>
        apiRequest(`${activeSession.apiBaseUrl}${path}`, {
          ...options,
          headers: {
            ...options.headers,
            Authorization: `${activeSession.tokenType} ${activeSession.accessToken}`,
          },
        });
      let response = await execute(current);
      assertCurrent();
      if (response.status === 401 && !refreshed) {
        current = await runRefresh(current);
        assertCurrent();
        response = await execute(current);
        assertCurrent();
      }
      return response;
    },
    [runRefresh],
  );

  const signIn = useCallback(
    async (values: SignInFormValues) => {
      if (cleanupRequired.current) throw new Error('Sign-out cleanup is required');
      const startEpoch = epochRef.current;
      const apiBaseUrl = apiBaseUrlRef.current;
      const next = { ...(await login(apiBaseUrl, values)), apiBaseUrl };
      if (epochRef.current !== startEpoch) {
        // Signed out, or another sign-in won, while this one was in flight.
        bestEffortLogout(next);
        return;
      }
      invalidate();
      const epoch = epochRef.current;
      sessionRef.current = null;
      pendingSessionRef.current = null;
      try {
        await enqueueStorage(() => storeSession(next));
      } catch (error) {
        bestEffortLogout(next);
        if (epochRef.current === epoch) applyState(SIGNED_OUT);
        throw error;
      }
      if (epochRef.current !== epoch) {
        bestEffortLogout(next);
        return;
      }
      sessionRef.current = next;
      applyState({ recovery: null, session: next, sessionExpired: false, status: 'authenticated' });
    },
    [applyState, bestEffortLogout, enqueueStorage, invalidate],
  );

  useEffect(() => {
    if (apiBaseUrlRef.current === settings.apiBaseUrl) return;
    apiBaseUrlRef.current = settings.apiBaseUrl;
    // A new server must never inherit an old session or an in-flight login.
    void signOut().catch(() => undefined);
  }, [settings.apiBaseUrl, signOut]);

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
