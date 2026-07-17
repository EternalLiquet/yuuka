# Testing and Quality Gates

## Backend

```sh
cd backend
./gradlew check
./gradlew pitest
```

`check` runs formatting, unit tests, PostgreSQL Testcontainers integration tests, the exact OpenAPI snapshot comparison, and JaCoCo verification. Integration tests use PostgreSQL 16, not H2.

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

- Backend: `./gradlew check`, `./gradlew pitest`, and `./gradlew bootJar` with CI build metadata.
- Mobile: `npm ci`, formatting, lint, TypeScript, Jest coverage, Expo Doctor, Android export, and production dependency audit.
- Infrastructure: `docker compose config --quiet` and a hardened backend image build.

Pull-request and branch validation jobs use cancellable concurrency so newer commits replace stale
runs. The release job is separate, waits for all required validation jobs, runs only after a
successful push to `master`, and does not run for pull requests.

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
