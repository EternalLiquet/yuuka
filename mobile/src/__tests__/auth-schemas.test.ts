import { signInSchema } from '@/auth/schemas';
import { authSessionSchema, isAccessTokenExpired, isRefreshTokenExpired } from '@/auth/session';

describe('signInSchema', () => {
  it('accepts valid credentials', () => {
    expect(
      signInSchema.safeParse({
        email: 'test@yuuka.local',
        password: 'password123',
        totpCode: '123456',
      }).success,
    ).toBe(true);
  });

  it('accepts login without TOTP when the server has not enabled it', () => {
    expect(
      signInSchema.safeParse({
        email: 'test@yuuka.local',
        password: 'password123',
        totpCode: '',
      }).success,
    ).toBe(true);
  });

  it('rejects short passwords', () => {
    expect(signInSchema.safeParse({ email: 'test@yuuka.local', password: 'short' }).success).toBe(
      false,
    );
  });

  it('rejects malformed authenticator codes', () => {
    expect(
      signInSchema.safeParse({
        email: 'test@yuuka.local',
        password: 'password123',
        totpCode: 'abc123',
      }).success,
    ).toBe(false);
  });

  it('detects expired auth sessions', () => {
    expect(
      isAccessTokenExpired(
        {
          accessToken: 'token',
          tokenType: 'Bearer',
          expiresAt: '2026-07-10T12:00:00.000Z',
          refreshToken: 'a'.repeat(32),
          refreshExpiresAt: '2026-08-10T12:00:00.000Z',
        },
        Date.parse('2026-07-10T12:00:00.001Z'),
      ),
    ).toBe(true);
  });

  it('treats almost-expired auth sessions as expired', () => {
    expect(
      isAccessTokenExpired(
        {
          accessToken: 'token',
          tokenType: 'Bearer',
          expiresAt: '2026-07-10T12:00:29.000Z',
          refreshToken: 'a'.repeat(32),
          refreshExpiresAt: '2026-08-10T12:00:00.000Z',
        },
        Date.parse('2026-07-10T12:00:00.000Z'),
      ),
    ).toBe(true);
  });

  it('validates refresh-token bounds and refresh expiry', () => {
    const session = {
      accessToken: 'token',
      tokenType: 'Bearer' as const,
      expiresAt: '2026-07-10T13:00:00.000Z',
      refreshToken: 'r'.repeat(48),
      refreshExpiresAt: '2026-07-10T12:00:29.000Z',
    };

    expect(authSessionSchema.safeParse(session).success).toBe(true);
    expect(isRefreshTokenExpired(session, Date.parse('2026-07-10T12:00:00.000Z'))).toBe(true);
    expect(authSessionSchema.safeParse({ ...session, accessToken: 'x'.repeat(8193) }).success).toBe(
      false,
    );
  });

  it('keeps fresh access and refresh tokens active', () => {
    const session = {
      accessToken: 'token',
      tokenType: 'Bearer' as const,
      expiresAt: '2026-07-10T13:00:00.000Z',
      refreshToken: 'r'.repeat(48),
      refreshExpiresAt: '2026-08-10T12:00:00.000Z',
    };
    const now = Date.parse('2026-07-10T12:00:00.000Z');

    expect(isAccessTokenExpired(session, now)).toBe(false);
    expect(isRefreshTokenExpired(session, now)).toBe(false);
  });
});
