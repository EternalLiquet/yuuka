# Codex Execution Prompt

Build Project Yuuka according to every markdown file in this repository.

## First action

Read all files in `docs/` in numeric order. Treat them as authoritative. Do not begin product implementation until they have been read.

## Required architecture

- `backend/`: Spring Boot 3, Java 21, Gradle Kotlin DSL
- `mobile/`: Expo React Native, TypeScript strict mode
- PostgreSQL
- Flyway
- Docker Compose
- Spring Security with JWT and refresh-token rotation
- self-hosted homelab deployment
- Tailscale-compatible private access
- no Supabase
- no Firebase
- no mandatory paid service

## Required development method

Use behavior-driven and test-driven development.

For each vertical slice:

1. Write or refine behavior scenarios.
2. Write failing tests.
3. Implement the smallest correct behavior.
4. Refactor.
5. Run the relevant test suite.
6. Run broader regression tests.
7. Keep documentation synchronized.

Do not postpone tests until the end.

## Suggested implementation order

1. Inspect repository and produce `IMPLEMENTATION_PLAN.md`.
2. Scaffold backend and mobile projects.
3. Add Docker Compose and PostgreSQL.
4. Add Flyway migrations.
5. Implement authentication.
6. Implement paycheck vertical slice.
7. Implement entries and calculations.
8. Implement status transitions and immutable history.
9. Implement templates and snapshot copying.
10. Implement spending buckets.
11. Implement History and audit UI.
12. Implement sorting, filtering, and reordering.
13. Add security hardening.
14. Add automated end-to-end flows.
15. Complete deployment, backup, and restore documentation.
16. Run all quality gates and report exact results.

## Do not

- Do not build a monthly-budget-first product.
- Do not build a chat interface.
- Do not add bank integration.
- Do not use floating-point money.
- Do not use H2 as a substitute for PostgreSQL integration tests.
- Do not fake persistence with local-only state.
- Do not mutate existing paychecks when templates change.
- Do not rewrite status history.
- Do not mark failed writes as successful.
- Do not expose the API publicly by default.
- Do not require a paid cloud service.
- Do not disable failing tests to complete the task.

## Required deliverables

- working mobile app,
- working backend,
- Docker Compose,
- Flyway migrations,
- `.env.example`,
- seed/demo data,
- OpenAPI contract,
- CI workflows,
- unit tests,
- integration tests using PostgreSQL,
- mobile component tests,
- automated Android end-to-end tests where practical,
- enforced coverage thresholds,
- README setup instructions,
- homelab and Tailscale deployment guide,
- backup and restore guide.

## Completion rule

Do not merely scaffold the project. Implement the specified product.

Before declaring completion, run all tests, linting, type checks, formatting checks, migrations, contract validation, and configured end-to-end tests. Report exact coverage numbers and plainly identify anything unfinished.
