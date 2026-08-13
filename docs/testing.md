# Testing and Quality Gates

## Repository verification interface

Use Java 21, Node.js 22.13 or a newer version supported by the pinned Expo stack, and a working
Docker daemon. Backend `check` runs PostgreSQL Testcontainers, so even fast verification requires
Docker. Install mobile dependencies once after checkout or lockfile changes:

```sh
cd mobile
npm ci
```

Run stable aggregate checks from the repository root:

```sh
./scripts/verify-fast.sh
./scripts/verify-full.sh
```

Fast verification runs:

- backend `check`, including formatting, PostgreSQL integration, coverage, and OpenAPI snapshot;
- mobile formatting, lint, TypeScript, and Jest coverage;
- Codex workflow metadata, shell orchestration, and CI-wiring validation;
- `docker compose config --quiet`.

Full verification includes every fast gate and adds:

- backend PIT mutation testing and `bootJar` packaging;
- Expo Doctor, Android export, audit-policy tests, and the live production dependency audit;
- production-preflight tests and the hardened backend image build.

For focused iteration or parallel execution, use the component interfaces:

```sh
./scripts/verify-backend.sh fast
./scripts/verify-mobile.sh fast
./scripts/verify-infrastructure.sh fast
```

Each also accepts `full`. GitHub CI installs the required runtimes and dependencies, then calls the
three full component scripts in parallel jobs. CI retains GitHub-specific setup, report uploads,
CodeQL, Dependency Review, release packaging/publishing, and Android emulator orchestration.

`npx expo-doctor`, the live npm advisory query, dependency or image downloads, Testcontainers, and
Docker image builds require their relevant network/runtime environment. Report an unavailable
required gate as unverified rather than passing. Android Maestro, physical-device USB checks,
private Tailscale reachability, production deployment verification, and restore drills are not part
of deterministic local full verification.

## Backend

```sh
cd backend
./gradlew check
./gradlew pitest
```

`check` runs formatting, unit tests, PostgreSQL Testcontainers integration tests, the exact OpenAPI snapshot comparison, and JaCoCo verification. Integration tests use PostgreSQL 16, not H2.

Expense List coverage lives in backend integration tests because lifecycle, derived totals,
settlement provenance, exact-max overflow rollback, and concurrent item serialization depend on
PostgreSQL constraints and transactions.

Dashboard integration coverage uses PostgreSQL for owner-scoped Active visibility, live/deleted
entry behavior, status-history timestamps, bucket and Expense List totals, stable ordering, and
financial-position summaries. The exact Processing boundary is asserted in owner-local calendar
dates rather than elapsed-hour approximations.

JaCoCo enforces 80% overall line coverage and 90% line / 85% branch coverage on critical domain policies and application services. Trivial DTO accessors, JPA persistence accessors, Spring configuration, and generated OpenAPI material may be omitted from targeted package rules; they remain exercised through integration tests and contribute to the overall report unless explicitly excluded in Gradle.

PIT covers allocation arithmetic, bucket arithmetic, Active/History visibility, and status-transition rules. Its mutation threshold is enforced at 85%.

Reports:

- `backend/build/reports/tests/test/index.html`
- `backend/build/reports/jacoco/test/html/index.html`
- `backend/build/reports/pitest/index.html`

## Mobile

```sh
cd mobile
npm ci
npm run format:check
npm run lint
npm run typecheck
npm run test:coverage
npx expo export --platform android --output-dir dist/android
```

Jest enforces 85% line/function/statement and 80% branch coverage for money, API contracts/parsing, session expiry, settings storage, validation, and list behavior. Expo Router page wiring is validated through React Native Testing Library and Maestro rather than counted as isolated unit logic. The resulting targeted threshold exceeds the required 85/80 core-logic and 75 overall minima for the code under the unit coverage gate.

Mobile Expense List checks cover runtime contracts, OpenAPI endpoint presence, Bill/Payback target
routing, and mocked multi-page list behavior through ledger 101. Full rapid-entry and settlement
journeys are candidates for Maestro once the flow is promoted into the critical E2E set.

Home component coverage exercises all five sections, existing-route navigation, independent
summary/bucket/recurring query states, cached stale content, retry, partial-failure pull-to-refresh,
full-window recurring coverage counts and three-row caps, zero/one/multiple import interactions,
quick assignment paycheck fit and ordering, amount review, transactional payload, authoritative
highlight navigation, duplicate-submit prevention, and failure-state preservation,
focus and app-active freshness, overlapping refresh coalescing, 30/90-day switching, compact
positions, accessibility, and wrapping on a 320-point viewport.

The mobile OpenAPI test reads `docs/openapi.json`; the backend test regenerates that document from
Springdoc and requires byte-for-byte structural equality. The version contract must keep
`/health/version` outside `/api/v1`, unauthenticated, JSON-only, and limited to a required nonblank
`version` string.

## Android end to end

The current critical Maestro flows are:

- `.maestro/01-scratch-lifecycle.yaml`
- `.maestro/02-payback-delete-reassign.yaml`
- `.maestro/03-template-application-draft.yaml`
- `.maestro/04-recurring-bill-import.yaml`

Run them against a disposable demo backend and installed preview APK:

```sh
maestro test -e YUUKA_EMAIL=e2e@yuuka.local -e YUUKA_PASSWORD=E2ePassword123 .maestro/01-scratch-lifecycle.yaml
maestro test -e YUUKA_EMAIL=e2e@yuuka.local -e YUUKA_PASSWORD=E2ePassword123 .maestro/02-payback-delete-reassign.yaml
maestro test -e YUUKA_EMAIL=e2e@yuuka.local -e YUUKA_PASSWORD=E2ePassword123 .maestro/03-template-application-draft.yaml
maestro test -e YUUKA_EMAIL=e2e@yuuka.local -e YUUKA_PASSWORD=E2ePassword123 .maestro/04-recurring-bill-import.yaml
```

Never run destructive E2E flows against production data. Flaky tests are failures and must be fixed, not disabled.

For adding or debugging Maestro flows, use `docs/android-e2e-maestro.md`. It documents the Android
workflow setup, Metro startup requirements, artifact paths, emulator pitfalls, and selector patterns
that keep the E2E suite deterministic.

## GitHub Actions

CI runs on pull requests targeting `master`, pushes to `master`, and manual dispatches.

Required validation jobs:

- Backend: `./scripts/verify-backend.sh full` with CI build metadata.
- Mobile: `npm ci`, then `./scripts/verify-mobile.sh full`.
- Infrastructure: `./scripts/verify-infrastructure.sh full`, including workflow validation,
  production-preflight tests, quiet Compose validation, and the hardened backend image build.

Pull-request and branch validation jobs use cancellable concurrency so newer commits replace stale
runs. The release job is separate, waits for all required validation jobs, runs only after a
successful push to `master`, and does not run for pull requests.

The mobile production audit runs `npm run test:audit-policy` and `npm run audit:production`. The
policy fails closed for new high or critical advisories, expired exceptions, dependency-tree
inspection failures, and any installed production path or version outside its exact approved set.
Temporary exceptions must identify exact GitHub advisory IDs, enumerate approved dependency paths
and versions where a patch is available, document why the finding is temporarily acceptable, and
include a near-term review deadline in `mobile/scripts/audit-production.mjs`.

Android E2E is intentionally not part of every pull-request or push run because emulator jobs are
slow and comparatively flaky on shared GitHub-hosted runners. The separate `Android E2E` workflow
runs every night at 07:00 UTC and can also be started manually. It provisions disposable PostgreSQL,
starts the demo backend, builds a bundled Android release APK for E2E, and runs the critical Maestro
flows.

Checks that remain local/manual:

- Production homelab deployment verification.
- Physical-device USB debugging.
- Real Tailscale Serve reachability from the owner's phone.
- Backup restore drills against a disposable production-like stack.

Those checks require private infrastructure or physical devices and are intentionally not required
for ordinary GitHub Actions CI.

## Release Versioning

Successful `master` builds publish semantic-version tags in the form `vMAJOR.MINOR.PATCH`. The
first automated release is `v1.0.0` if no valid version tag exists. Later releases inspect the pull
request associated with the pushed commit for release labels. `release:major` increments the major
number and resets minor and patch to zero, `release:minor` increments the minor number and resets
patch to zero, and `release:patch` increments only the patch number. If more than one release label
is present, the largest requested bump wins. Pull requests without a release label and direct pushes
to `master` default to patch bumps.

The release job fetches full history and tags, checks whether the current commit is already tagged,
and refuses to force-overwrite tags. Rerunning a workflow for an already tagged commit reuses that
tag instead of creating a second version. The job also creates or refreshes the matching GitHub
Release with the backend jar, committed OpenAPI snapshot, commit SHA, generation timestamp, and a
short commit-derived changelog.

The version source of truth is:

```text
Git tag -> CI release version -> Spring Boot build info -> packaged jar/Docker image -> /health/version
```

Gradle resolves versions from `-PyuukaVersion`, then `YUUKA_BUILD_VERSION`, then an exact
checked-out Git release tag, then `0.0.0-dev`. Local builds that are not created from a release tag
report `0.0.0-dev`.
