# AGENTS.md

This file contains repository-wide instructions for AI coding agents working on Project Yuuka.

These rules apply to all files unless a more specific nested `AGENTS.md` overrides them.

## Project overview

Yuuka is a private, self-hosted, paycheck-first budgeting application.

The primary product question is:

> What does this paycheck still need to accomplish?

Current stack:

- Java 21
- Spring Boot 3
- Spring Security
- Spring Data JPA
- PostgreSQL 16
- Flyway
- Gradle Kotlin DSL
- Expo React Native
- TypeScript strict mode
- Expo Router
- TanStack Query
- React Hook Form
- Zod
- Docker Compose
- private access through Tailscale

Repository areas:

- `backend/` — Spring Boot API and domain logic
- `mobile/` — Expo React Native Android client
- `docs/` — authoritative product, architecture, API, security, testing, deployment, and recovery documentation
- `ops/` — backup, restore, and owner-management tooling
- `.maestro/` — Android end-to-end flows
- `docs/openapi.json` — committed API contract

## Source of truth

Before editing:

1. Inspect the relevant current code.
2. Inspect applicable tests.
3. Inspect `docs/openapi.json` when API behavior is involved.
4. Inspect relevant files under `docs/`.
5. Reuse existing patterns and components.

The repository is the source of truth.

Do not rely on stale prompts, assumptions, old plans, or remembered architecture when the current implementation can be inspected.

Clearly distinguish:

- confirmed current behavior
- requested changes
- assumptions
- deferred follow-up work

## Scope discipline

Implement the smallest complete change that satisfies the task.

Do not:

- perform broad unrelated refactors
- introduce parallel systems
- redesign established architecture without a demonstrated need
- add speculative abstractions
- implement roadmap items that were not requested
- change domain behavior merely to simplify implementation
- weaken tests or validation to make CI pass

Preserve backward compatibility and production data unless the task explicitly requires otherwise.

## Mandatory Git workflow for coding tasks

All coding work must be performed on a dedicated branch created from the latest `master`.

### Before editing

1. Confirm the working tree is clean.
2. Fetch the latest remote state.
3. Update local `master` using a safe fast-forward.
4. Create and switch to a conventionally named branch.

If the working tree contains uncommitted user work, do not overwrite, discard, stash, or commit it without explicit permission. Stop and report the conflict.

### Branch naming

Use lowercase snake-case descriptions with one of these prefixes:

```text
feature/do_thing
bug/fix_thing
chore/update_thing
docs/update_thing
test/add_coverage
refactor/improve_thing
ci/optimize_workflow
security/harden_thing
```

Examples:

```text
feature/spending_bucket_performance
bug/fix_template_reorder
ci/parallelize_android_e2e
security/harden_refresh_tokens
```

Choose the prefix that best matches the primary purpose of the task.

Do not work directly on `master`.

### After implementation

For every completed coding task:

1. Review the complete diff.
2. Confirm only intended files changed.
3. Run relevant tests and quality gates.
4. Check for secrets and generated junk.
5. Commit with a clear conventional message.
6. Push the working branch.
7. Create a pull request targeting `master`.
8. Do not merge the pull request unless explicitly instructed.

The pull request must summarize:

- behavior implemented
- important design decisions
- backend/API/mobile changes
- migrations, if any
- tests added or updated
- commands run and results
- anything not run
- known risks or follow-up work

Read-only reviews, planning tasks, and investigations that make no repository changes do not require a branch, commit, push, or pull request.

## Core domain rules

### Paychecks

The user-facing term is **Paycheck**, but a paycheck may represent any incoming-money event:

- employment pay
- bonus
- refund
- reimbursement
- side income
- arbitrary lump sum
- free paycheck

Do not hard-code a paycheck rotation or specific paycheck names.

A paycheck is the primary budgeting aggregate. Do not introduce a monthly-budget aggregate unless explicitly requested and approved.

### Active and History

A paycheck requires attention when:

- it is not fully allocated, or
- at least one live entry is not Posted

A paycheck can be fully allocated and still remain Active.

History contains completed, closed, or archived paychecks according to the current lifecycle and visibility policy.

Do not duplicate visibility rules in controllers or mobile code. Reuse the established domain policy.

### Entry types

Supported entry types are:

- `BILL`
- `SPENDING_BUCKET`
- `SINKING_FUND`

Do not treat these as interchangeable.

### LEFTOVER

`LEFTOVER` is intentionally a normal `BILL`.

Do not create a special LEFTOVER entry type.

The one-tap LEFTOVER operation allocates the exact current unallocated amount and must preserve existing stale-version and duplicate-submission protections.

### Bills

Bills may be:

- `AUTOPAY`
- `MANUAL`

Autopay is the default.

The mobile form expresses the exception with:

```text
I need to pay this manually
```

Payment method is informational and filterable.

It must not:

- change entry status
- initiate payment
- automatically mark an entry Processing
- automatically mark an entry Posted
- change allocation math

Non-Bill entries must not retain a Bill payment method.

### Spending Buckets

A Spending Bucket reserves its full entry amount immediately.

Bucket purchases are separate ledger records.

Adding, editing, or deleting a bucket purchase:

- changes spent, remaining, and over-budget values
- does not change paycheck allocation
- must remain owner-scoped
- must preserve optimistic locking
- must use integer minor units

Spent, remaining, and over-budget values are derived.

Do not persist or directly edit derived bucket totals unless a reviewed performance requirement explicitly justifies it.

### Sinking Funds

A Sinking Fund entry represents the current paycheck contribution.

Do not silently turn Sinking Funds into persistent account balances without an explicit feature decision and data model.

### Statuses

Use these exact user-facing status names:

1. `Not Paid`
2. `Processing`
3. `Posted`

Users may move forward or backward between statuses.

Status changes must:

- preserve allocation
- append immutable status history
- record the user-selected effective timestamp
- separately record the actual system timestamp
- preserve optional notes
- retain auditability

Never rewrite or delete immutable status history.

### Allocation and completion

Allocation and payment completion are separate concepts.

```text
allocated = sum(live entry amounts)
unallocated = paycheck amount - allocated
posted amount = sum(Posted live entry amounts)
completion percent = posted amount / allocated amount
```

When allocated is zero, completion percent follows the established zero-allocation behavior.

Changing status must not change allocation.

Accidental over-allocation must remain blocked unless an explicit, audited override feature is requested.

### Ordering

The default order is the user’s persisted custom order.

Temporary sorting and filtering must never mutate or destroy custom order.

Reorder operations should validate the complete expected live ID set when required by the current aggregate.

### Templates

Templates are reusable ordered entry sets.

Applying a template must:

- copy entries transactionally
- preserve entry order
- preserve relevant Bill payment method
- create independent paycheck-entry snapshots
- retain only source-template provenance where currently supported

Later edits to a template must not mutate existing paychecks.

Later edits to a paycheck must not mutate its source template.

### Entry search

Global entry search:

- is owner-scoped
- excludes soft-deleted entries
- supports case-insensitive partial text matching
- supports exact amount matching
- may cover Active, History, or both
- must use deterministic pagination and ordering
- must not fetch all history into mobile and filter client-side

### Paybacks

Paybacks are separate aggregates representing money borrowed from the owner’s protected funds.

A paycheck entry may link to at most one Payback.

Payback assignment alone does not change the Payback balance.

Repayment behavior:

- Not Paid or Processing does not apply repayment
- moving a linked entry to Posted applies exactly one repayment
- moving backward from Posted reverses the active repayment
- returning to Posted creates a new active repayment while retaining reversed history
- deleting a Posted linked entry reverses repayment transactionally
- deleting a Payback reverses active repayments and clears live assignments transactionally
- repayment history is preserved
- remaining balance must never become negative

Do not introduce:

- interest
- fees
- repayment schedules
- split repayment across Paybacks
- multiple Paybacks per entry
- currency conversion
- bank synchronization

unless explicitly requested.

## Money rules

All persisted and API money uses signed 64-bit integer minor units.

Examples:

```text
$1,939.23 = 193923
$0.03 = 3
```

Never use floating-point arithmetic for money.

Backend:

- use integer arithmetic
- validate ranges
- guard aggregate arithmetic against overflow where relevant

Mobile:

- parse user input from strings
- use the existing `BigInt`-safe parsing approach
- convert only values proven safe for JavaScript integer representation

User-facing text must display formatted currency.

Do not show internal terminology such as:

- minor units
- `amountMinor`
- raw integer cents

in visible errors or labels.

## Ownership and authorization

Every private query and mutation must be scoped to the authenticated owner.

Prefer repository methods that include owner ID directly.

Do not:

- load by resource ID alone and check ownership later when an owner-scoped query is possible
- leak cross-owner existence
- leak cross-owner counts through pagination, search, summaries, or aggregates
- trust owner IDs supplied by the client

Cross-owner identifiers should resolve according to the existing not-found/forbidden policy without disclosing data.

Add owner-isolation tests for new private resources and queries.

## Concurrency and transactions

Mutable persisted resources use optimistic locking.

Stale writes must return `409 Conflict` using the established error envelope.

Do not bypass or silently retry stale writes in ways that overwrite user changes.

Critical multi-record operations must be transactional, including operations such as:

- template application
- status transition plus repayment application/reversal
- Payback deletion cleanup
- entry deletion with dependent effects
- reorder operations where partial updates would corrupt order

Use pessimistic locking only when the existing aggregate requires it to protect a cross-record invariant.

## Persistence and migrations

Use PostgreSQL behavior as the source of truth. Do not introduce H2-specific assumptions.

Use real foreign keys and database constraints in addition to service validation.

Soft-deleted records must not contribute to normal reads, allocation, search, summaries, or derived totals.

### Flyway rules

When schema changes are required:

- add a new migration
- never edit, rename, reorder, or replace an already-applied migration
- make migrations safe for existing production data
- backfill required values deterministically
- add constraints only after data is valid
- test migrations against PostgreSQL
- document compatibility and rollback implications

Do not add a migration when the existing schema already supports the feature.

Do not create persisted aggregate tables when a derived query is sufficient unless repository inspection demonstrates a real performance requirement.

## Audit and immutable history

Material operations should follow existing audit conventions.

Audit and status history are append-only.

Do not update or delete immutable history records.

When implementing new material mutations, inspect whether the equivalent existing operations create audit events and preserve that behavior.

User-selected effective timestamps and system-recorded timestamps are separate concepts and must remain separate.

## API and OpenAPI

Private business APIs use the `/api/v1` base path.

Use:

- JSON
- ISO-8601 dates and timestamps
- consistent error envelopes
- pagination for growing collections
- `409 Conflict` for stale optimistic-lock writes

When API behavior changes:

1. Update backend DTOs and endpoints.
2. Update the committed `docs/openapi.json`.
3. Update mobile runtime schemas.
4. Update mobile API usage.
5. Update contract tests.

Do not allow backend, OpenAPI, and mobile contracts to drift.

Health endpoints remain outside `/api/v1`.

Public health responses must expose only intentionally safe operational metadata.

## Backend conventions

Follow the existing layering:

- controllers handle HTTP concerns
- services enforce application and transaction behavior
- domain classes and policies enforce domain rules
- repositories handle persistence
- DTOs define API contracts

Avoid placing business logic in controllers.

Use constructor injection.

Reuse existing:

- exception types
- error codes
- ownership patterns
- calculators
- visibility policies
- audit helpers
- pagination conventions

Do not return JPA entities directly from controllers.

## Mobile conventions

Preserve strict TypeScript.

Do not use `any` to bypass contract or state problems.

Reuse existing:

- Expo Router navigation patterns
- TanStack Query keys and invalidation patterns
- React Hook Form
- Zod/runtime validation
- API error mapping
- themed components
- loading, empty, stale, and retry states
- money parsing and formatting
- accessibility conventions

Mobile behavior must:

- preserve forms after failed saves
- never show false success
- keep usable cached data visible during background refetch
- provide clear retry behavior
- avoid replacing cached content with full-screen loading
- use large touch targets
- not rely on color alone
- prevent duplicate submissions where a repeated tap could duplicate data

Do not make the mobile client calculate authoritative persisted-domain totals when the backend already owns that logic.

## Security rules

Never commit, print, log, upload, or expose:

- `.env` files
- passwords
- password hashes
- TOTP secrets
- `otpauth://` URLs
- JWT signing secrets
- access or refresh tokens
- database credentials
- production logs containing sensitive data
- Android keystores
- signing keys
- private certificates
- backup archives
- internal secrets

Every private endpoint must remain authenticated.

Preserve:

- BCrypt password hashing
- short-lived access tokens
- rotating opaque refresh tokens
- hashed refresh tokens at rest
- refresh-token revocation and replay protection
- production-disabled public registration
- restricted production CORS
- secure mobile token storage

Do not log full sensitive request bodies.

Do not weaken security merely because Yuuka is private or behind Tailscale.

## Deployment constraints

Production is private and self-hosted.

Preserve:

- backend binding to `127.0.0.1`
- PostgreSQL not publicly exposed
- private access through Tailscale Serve
- Tailscale Funnel disabled
- production secrets outside Git
- persistent PostgreSQL storage
- backup-before-risky-migration practice

Do not introduce a mandatory paid service.

Do not require Firebase, Supabase, Plaid, public cloud hosting, or public API exposure unless explicitly approved.

## Testing expectations

Follow behavior-driven and test-driven practices where practical:

1. Define expected behavior.
2. Add or update failing tests.
3. Implement the smallest correct change.
4. Refactor.
5. Run focused tests.
6. Run broader relevant gates.
7. Update documentation.

Tests must be meaningful.

Do not:

- disable tests to get green CI
- weaken assertions
- add arbitrary waits instead of fixing synchronization
- claim a test passed unless it actually ran
- hide flaky behavior with retries

### Backend checks

Relevant full backend gates:

```sh
cd backend
./gradlew check
./gradlew pitest
```

`check` includes formatting, unit tests, PostgreSQL integration tests, OpenAPI snapshot verification, and JaCoCo verification.

Run focused test classes first when debugging.

Run PIT when domain policies, calculators, or covered critical services are materially changed.

### Mobile checks

Relevant full mobile gates:

```sh
cd mobile
npm ci
npm run format:check
npm run lint
npm run typecheck
npm run test:coverage
npx expo export --platform android --output-dir dist/android
npx expo-doctor
```

Inspect `mobile/package.json` and current CI before assuming a script exists.

Run focused Jest tests first.

### Contract checks

When API behavior changes, run both:

- backend OpenAPI snapshot validation
- mobile contract/runtime-schema tests

### Docker and infrastructure

When Docker, deployment, or environment configuration changes:

```sh
docker compose config --quiet
```

Do not use commands that unnecessarily print resolved secrets.

### Android E2E and Maestro

Maestro is reserved for critical full-device journeys.

Most behavior belongs in backend integration tests and mobile component tests.

When changing or debugging Maestro:

- run only the affected flow
- do not rerun the full suite repeatedly
- limit automated fix-and-rerun attempts to two
- after two unsuccessful attempts, stop and report the exact failure, diagnostics, screenshots, and likely root cause
- do not add arbitrary sleeps unless evidence requires one
- prefer explicit waits and stable accessibility selectors
- do not weaken assertions for CI
- never run destructive flows against production data
- preserve diagnostic artifacts

Nightly E2E failures remain real quality issues even when they do not block ordinary PR validation.

## Documentation

Update documentation when behavior, architecture, API, testing, deployment, or operations change.

Relevant locations include:

- `README.md`
- `docs/01_PRODUCT_AND_DOMAIN_RULES.md`
- `docs/architecture-decisions.md`
- `docs/03_API_AND_SECURITY.md`
- `docs/04_MOBILE_UX_AND_FLOWS.md`
- `docs/testing.md`
- deployment and recovery documentation
- `docs/openapi.json`

Do not update unrelated documents merely to create churn.

Do not leave documentation claiming a feature is planned after it has been implemented.

## Final review before commit

Before committing:

1. Review `git status`.
2. Review the full diff.
3. Confirm no unrelated changes.
4. Confirm no secret or environment files are tracked.
5. Confirm generated files are intentional.
6. Confirm migrations are new and safe.
7. Confirm OpenAPI and mobile contracts agree.
8. Confirm required tests actually ran.
9. Confirm user-visible behavior matches requested terminology.
10. Confirm no existing domain invariant was weakened.

Useful sensitive-file check:

```sh
git ls-files | grep -Ei '(^|/)\.env($|\.)|keystore|\.jks$|\.p12$|\.pem$|backup'
```

Treat matches as items to inspect, not automatically delete.

## Commit and PR conventions

Use a clear conventional commit message, such as:

```text
feat: add spending bucket performance summaries
fix: preserve template entry ordering
test: expand Payback reversal coverage
ci: parallelize Android E2E flows
docs: update deployment verification
```

Push only the dedicated working branch.

Create a pull request into `master`.

Do not merge it unless explicitly instructed.

## Completion report

At the end of a coding task, report:

- branch name
- concise implementation summary
- files changed
- migrations added, if any
- API/OpenAPI changes
- tests added or updated
- commands run and exact results
- anything not run
- assumptions or remaining risks
- commit SHA
- pushed branch
- pull request URL

Be honest about incomplete checks or environment limitations.