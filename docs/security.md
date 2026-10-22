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
- Corrupt or locally refresh-expired mobile auth sessions, and sessions the server rejects with HTTP 401 on refresh, are cleared automatically. Connectivity failures, 403, 429, 5xx, malformed responses, and secure-storage errors keep the saved sign-in and show a recovery screen; no protected data is shown until the server validates the session. Sign out clears local credentials and cache even when the server is unreachable, and late refresh or storage results cannot resurrect a signed-out session or overwrite a newer sign-in.
- Mobile sessions within 30 seconds of expiry are treated as expired.
- Sessions retain their issuing API base URL in secure storage. Refreshes, protected requests,
  and best-effort revocation use that bound URL, including cleanup of late auth results. Changing
  the configured server invalidates in-flight auth work; saved sessions from another server are
  cleared without sending their credentials to the replacement server. Legacy records without an
  issuer bind to the configured URL at startup; Settings deletes them before changing that URL.
- Protected responses are checked against their initiating auth epoch before refresh, replay, or
  delivery. A late response cannot invalidate a replacement login or revive a signed-out session.
- Sign-out storage failures keep all routes locked behind an auth-level cleanup warning and retry.
  Sign-out is only reported complete after deletion succeeds. If storage remains unavailable,
  credentials may still exist on disk; the UI instructs the user to retry before closing the app.
- Native mobile token storage uses Expo SecureStore with device-local keychain accessibility on iOS.
- Web sessions use `sessionStorage` instead of persistent `localStorage`.
- Production mobile builds require an HTTPS API base URL.
- API requests omit browser credentials, reject redirects, disable request caching, and time out.
- Mobile screenshots and screen recording are intentionally allowed; sensitive controls remain focused on authentication, encrypted token storage, HTTPS production URLs, request hardening, and not exposing secrets in logs or UI.
- OpenAPI requires authentication; health endpoints expose only safe status and backend version metadata.
- PostgreSQL rejects updates and deletes on status-event and audit-event tables.
- Docker Compose keeps PostgreSQL on an internal network and binds the API to loopback by default.

## Known Follow-Up

- The mobile dependency graph pins patched transitive releases for `image-size`, `@xmldom/xmldom`,
  and the Istanbul-owned `js-yaml` path until their upstream packages adopt those versions. Keep the
  overrides under review during Expo upgrades and validate them with the production audit and Android
  export gates.
- Before deployment, use the `prod` Spring profile, replace all example secrets, and configure production CORS origins explicitly.
- Owner onboarding steps live in `docs/owner-onboarding.md`.
- If the backend is deployed behind a reverse proxy, configure trusted forwarded-header handling at the proxy/platform layer before relying on client-address rate limits.
- Rate limiting is in-memory and intended for the single backend instance. Email-key limits still apply behind Tailscale Serve, but limits reset on process restart.
- TOTP enrollment and recovery secrets must be protected outside the database; follow `docs/owner-onboarding.md`.
