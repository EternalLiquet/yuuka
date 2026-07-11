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

The mobile OpenAPI test reads `docs/openapi.json`; the backend test regenerates that document from Springdoc and requires byte-for-byte structural equality.

## Android end to end

The current first-slice Maestro flow is `.maestro/01-scratch-lifecycle.yaml`. Run it against a disposable demo backend and installed preview APK:

```sh
maestro test -e YUUKA_EMAIL=e2e@yuuka.local -e YUUKA_PASSWORD=E2ePassword123 .maestro/01-scratch-lifecycle.yaml
```

Never run destructive E2E flows against production data. Flaky tests are failures and must be fixed, not disabled.
