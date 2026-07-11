import { z } from 'zod';

export const authSessionSchema = z.object({
  accessToken: z.string().min(1).max(8192),
  tokenType: z.literal('Bearer'),
  expiresAt: z.string().datetime(),
  refreshToken: z.string().min(32).max(256),
  refreshExpiresAt: z.string().datetime(),
});

export type AuthSession = z.infer<typeof authSessionSchema>;

const EXPIRY_SKEW_MS = 30_000;

export function isAccessTokenExpired(session: AuthSession, now = Date.now()) {
  return Date.parse(session.expiresAt) <= now + EXPIRY_SKEW_MS;
}

export function isRefreshTokenExpired(session: AuthSession, now = Date.now()) {
  return Date.parse(session.refreshExpiresAt) <= now + EXPIRY_SKEW_MS;
}
