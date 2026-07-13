# Project Yuuka Implementation Plan

This plan implements the authoritative specifications in `docs/00_PROJECT_VISION.md` through
`docs/07_CODEX_EXECUTION_PROMPT.md`. Yuuka is paycheck-first. Money is represented only as integer
minor units, status and audit history are append-only, and every persisted record is owner-scoped.

## Architecture

- Backend: Java 21, Spring Boot 3, Spring Security, JPA, Flyway, PostgreSQL, and Gradle Kotlin DSL.
- Mobile: Expo React Native, strict TypeScript, Expo Router, TanStack Query, React Hook Form, Zod,
  SecureStore, and React Native Testing Library.
- Runtime: Android client over a private Tailscale network to a homelab-hosted API and PostgreSQL.
- API: `/api/v1`, JSON, generated OpenAPI, consistent error envelopes, UUID identifiers, optimistic
  lock versions on mutable resources, and authenticated owner resolution from the access token.
- Authentication: BCrypt owner password, TOTP, short-lived access JWT, opaque rotating refresh
  tokens hashed at rest, replay-family revocation, and public registration disabled by default.
- Aggregate boundaries: Paycheck owns paycheck entries and status events; Template owns template
  entries; bucket transactions belong to spending-bucket entries. Critical writes are transactional.
- Audit: material changes append immutable JSONB audit events. Status events and audit events are
  never updated or deleted through application APIs.

## Vertical Slices

### 1. Authentication and session lifecycle

Behaviors:

- Valid owner credentials and TOTP return an access token and refresh token.
- Refresh rotates the opaque token and revokes the consumed token atomically.
- Reuse of a rotated token revokes its token family.
- Logout revokes the supplied refresh token and is idempotent.
- `/api/v1/me` returns the authenticated owner profile.

Tests first: token hashing, expiry, rotation, replay handling, revocation, API authorization, and
PostgreSQL persistence.

### 2. Paycheck creation and calculations

Behaviors:

- Create a paycheck from scratch with exact cents and an initial audit event.
- Derived allocated, unallocated, status totals, and percentages are calculated, never edited.
- A paycheck is Active when it is not fully allocated or any live entry is not Posted.
- Stale writes return `409 Conflict`.

Tests first: exact-cent examples, zero allocation, active-list rules, ownership, and concurrency.

### 3. Entries, ordering, and immutable status history

Behaviors:

- Add, edit, soft-delete, filter, sort, and reorder Bill, Spending Bucket, and Sinking Fund entries.
- Over-allocation is blocked before commit.
- Status changes may move in either direction and append a transition with separate effective and
  recorded timestamps.
- Temporary sorting never changes saved custom order.

Tests first: allocation invariants, rollback, all status transitions, timestamp separation,
append-only history, reorder validation, and owner isolation.

### 4. Templates and snapshot application

Behaviors:

- Create, edit, duplicate, archive, restore, and reorder templates and their entries.
- Applying a template copies ordered entries into a new paycheck in one transaction.
- Copied entries and source template entries remain independent after creation.

Tests first: snapshot independence, exact-cent difference, atomic failure, ordering, and ownership.

### 5. Spending buckets

Behaviors:

- Add, edit, and soft-delete positive purchase transactions.
- Spent and remaining values are derived from live transactions.
- Each transaction mutation appends an audit event atomically.

Tests first: purchase totals, over-budget display, entry-type validation, audit creation, rollback,
optimistic locking, and ownership.

### 6. Lifecycle, History, and audit

Behaviors:

- Only a fully allocated and fully Posted paycheck can be closed.
- Closing moves it to History; reopening returns it to Active; archiving remains readable.
- Paycheck and entry audit feeds are paginated and immutable.

Tests first: close eligibility, close/reopen audit events, history ordering/filtering, and isolation.

### 7. Mobile experience

Behaviors:

- Sign in, refresh expired sessions, sign out, and show an explicit session-expired state.
- Active cards prioritize unallocated money and work remaining over charts.
- Create paychecks from scratch, edit entries, use a compact status sheet, preserve failed forms,
  transact against buckets, reorder accessibly, browse History, and manage Paybacks.
- Settings expose API URL, connection, dark/light/system theme, timezone, currency, version, and
  sign out. Dark is the first-run default.
- Cached reads remain visible on network errors with stale and retry affordances.
- Mobile template management, create-from-template UI, and full audit browsing remain later-scope
  screens even though backend contracts and service workflows exist.

Tests first: money/validation/sort/filter utilities, cards and rows, forms, status sheet, bucket
form, Payback flows, retry states, and accessibility labels.

## Data and Migrations

1. Extend users and add hashed rotating refresh tokens.
2. Add paychecks, paycheck entries, append-only status events, and audit events.
3. Add templates and template entries.
4. Add bucket transactions.
5. Add database functions/triggers that reject updates/deletes to append-only tables.
6. Add development-only idempotent demo seeding through an explicit profile.

Every migration is validated by a fresh PostgreSQL Testcontainer and by upgrading the existing V1
schema. Foreign keys, owner consistency, enum checks, nonnegative amounts where appropriate,
positions, and version columns are enforced in PostgreSQL as well as services.

## Contract and Operations

- Generate OpenAPI from backend annotations and validate a committed contract in CI.
- Keep mobile API schemas in Zod and add contract-drift checks against OpenAPI.
- Compose exposes PostgreSQL only to the internal Docker network by default; backend binding is
  configurable for local development and Tailscale-only deployment.
- Document first-owner setup, Android configuration, homelab deployment, Tailscale HTTPS access,
  nightly `pg_dump`, encrypted off-machine retention, restore, and restore verification.
- Add a Maestro Android flow for the twelve required critical actions, runnable when an emulator and
  test stack are available.

## Quality Gates

- Backend: Spotless, unit tests, PostgreSQL integration tests, JaCoCo thresholds, migration
  validation, OpenAPI generation/validation, and targeted PIT mutation tests.
- Mobile: Prettier, ESLint, strict TypeScript, Jest unit/component tests with thresholds, Expo export,
  API contract drift validation, and Maestro syntax/critical flow checks.
- Infrastructure: `docker compose config`, image build, container health, and API smoke test.
- Security: dependency audit, CodeQL, dependency review, secret-safe production startup checks,
  authorization/owner-isolation tests, and no database host publishing in production topology.

Completion requires exact command results and coverage values. Environment-dependent Android E2E
or container checks must be reported as unavailable rather than represented as passing.
