# Security Baseline

This document tracks foundation-level security controls. It does not define budgeting behavior or product requirements.

## Current Controls

- JWT access tokens are signed with HS256 and validated for issuer, audience, expiry, and signature.
- JWT secrets must be at least 32 bytes.
- Access tokens default to 15 minutes; configuration must be positive and no longer than one hour.
- Refresh tokens are opaque random values, stored only as SHA-256 hashes, rotated on every use, and grouped into revocable families.
- Reuse of a rotated refresh token revokes the entire token family.
- Backend sessions are stateless.
- API responses are marked `no-store`.
- Backend error responses are configured to avoid exposing stack traces, exception names, and framework messages.
- HTTP request headers and declared or chunked request bodies have conservative size limits.
- JSON parsing rejects unknown properties on request DTOs.
- Passwords are hashed with BCrypt cost 12.
- Login attempts are rate-limited per normalized email address and client address.
- Authentication limiter state is capped and clears expired keys before failing closed at capacity.
- Registration attempts are rate-limited per client address.
- Public registration is disabled by default and must be explicitly enabled.
- Production startup refuses default/weak database passwords, default JWT secrets, wildcard or non-HTTPS CORS origins, localhost CORS origins, or enabled public registration.
- Owner BCrypt cost and TOTP Base32 syntax are validated at startup.
- Single-owner mode can restrict authentication to `YUUKA_OWNER_EMAIL`.
- Owner account bootstrap can create the single account from `YUUKA_OWNER_PASSWORD_HASH`.
- Production startup requires owner email, owner password hash, and owner TOTP secret.
- Optional TOTP verification is supported through `YUUKA_OWNER_TOTP_SECRET`.
- CORS is allowlist-driven through `YUUKA_CORS_ALLOWED_ORIGINS`.
- API responses include baseline security headers.
- Mobile auth responses are schema-validated before storage.
- Mobile auth response payloads must be JSON and access tokens are capped to a bounded size before storage.
- Expired or corrupt mobile auth sessions are cleared automatically.
- Mobile sessions within 30 seconds of expiry are treated as expired.
- Native mobile token storage uses Expo SecureStore with device-local keychain accessibility on iOS.
- Web sessions use `sessionStorage` instead of persistent `localStorage`.
- Production mobile builds require an HTTPS API base URL.
- API requests omit browser credentials, reject redirects, disable request caching, and time out.
- Mobile screenshots and screen recording are intentionally allowed; sensitive controls remain focused on authentication, encrypted token storage, HTTPS production URLs, request hardening, and not exposing secrets in logs or UI.
- OpenAPI requires authentication; health endpoints expose only safe status and backend version metadata.
- PostgreSQL rejects updates and deletes on status-event and audit-event tables.
- Docker Compose keeps PostgreSQL on an internal network and binds the API to loopback by default.

## Known Follow-Up

- The current Expo dependency graph reports a moderate transitive `uuid` advisory through Expo CLI/config tooling. `npm audit fix` cannot clear it without a breaking dependency change, so this should be revisited when Expo publishes a compatible patch path.
- Before deployment, use the `prod` Spring profile, replace all example secrets, and configure production CORS origins explicitly.
- Owner onboarding steps live in `docs/owner-onboarding.md`.
- If the backend is deployed behind a reverse proxy, configure trusted forwarded-header handling at the proxy/platform layer before relying on client-address rate limits.
- Rate limiting is in-memory and intended for the single backend instance. Email-key limits still apply behind Tailscale Serve, but limits reset on process restart.
- TOTP enrollment and recovery secrets must be protected outside the database; follow `docs/owner-onboarding.md`.
