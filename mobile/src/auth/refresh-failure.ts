import { ZodError } from 'zod';

import { ApiError } from '@/api/api-error';

import { AuthStorageError } from './auth-storage-error';

export type RefreshFailureKind = 'invalid' | 'network' | 'server' | 'storage';
export type RecoverableFailureKind = Exclude<RefreshFailureKind, 'invalid'>;

/** The saved refresh token passed its local expiry, so no server call is made. */
export class LocalRefreshExpiredError extends Error {
  constructor() {
    super('Session expired');
    this.name = 'LocalRefreshExpiredError';
  }
}

/**
 * Raised to callers of authenticatedRequest when the session could not be
 * refreshed for a reason that does not prove the saved sign-in is bad.
 */
export class AuthRecoverableError extends Error {
  readonly reason: RecoverableFailureKind;

  constructor(reason: RecoverableFailureKind) {
    super(recoveryMessage(reason));
    this.name = 'AuthRecoverableError';
    this.reason = reason;
  }
}

export function recoveryMessage(reason: RecoverableFailureKind) {
  return reason === 'network'
    ? 'Cannot connect to Yuuka. Your saved sign-in has not been removed.'
    : "Yuuka couldn't confirm your sign-in right now. Your saved sign-in has not been removed.";
}

/**
 * Only a definitive rejection by the server (HTTP 401) or local refresh-token
 * expiry proves the saved credentials are unusable. Everything else, including
 * 403, 429, 5xx, malformed successful responses, secure-storage failures, and
 * transport failures, leaves the saved sign-in in place.
 */
export function classifyRefreshFailure(error: unknown): RefreshFailureKind {
  if (error instanceof LocalRefreshExpiredError) return 'invalid';
  if (error instanceof AuthStorageError) return 'storage';
  if (error instanceof ApiError) return error.status === 401 ? 'invalid' : 'server';
  if (error instanceof ZodError) return 'server';
  if (isTransportError(error)) return 'network';
  return 'server';
}

function isTransportError(error: unknown) {
  if (error instanceof TypeError) return true;
  if (typeof error !== 'object' || error === null) return false;
  const name = (error as { name?: unknown }).name;
  return name === 'AbortError' || name === 'TimeoutError' || name === 'NetworkError';
}
