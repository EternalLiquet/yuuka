# Yuuka

Yuuka is a paycheck-first budgeting app for bills, spending buckets, and sinking funds. It is a self-hosted, single-owner system with an Expo React Native client and a Spring Boot/PostgreSQL API.

## What is implemented

- Active paycheck and paycheck-detail workflows with exact-cent allocation metrics
- Scratch paycheck creation, basic bill/spending-bucket/sinking-fund entries, filtering, sorting, and persistent custom order
- Not Paid, Processing, and Posted transitions with immutable status history
- Close, reopen, archive, owner isolation, optimistic locking, and backend audit recording
- Password plus optional/deployment-required TOTP, JWT access tokens, and rotating refresh tokens
- Dark-first mobile UI with light/system preferences

Templates, bucket activity editing, full audit-history UI, and advanced settings remain later-scope mobile features. Backend foundations and contracts exist where needed, but visible mobile screens for those areas are placeholders until the next specification slice.

## Repository

- `backend/` - Java 21, Spring Boot 3, Spring Security, JPA, Flyway, PostgreSQL, Gradle Kotlin DSL
- `mobile/` - Expo React Native, TypeScript strict mode, Expo Router, TanStack Query, React Hook Form, Zod
- `docs/` - authoritative specification, API contract, architecture, security, deployment, and recovery
- `ops/` - owner enrollment and backup/restore helpers
- `.maestro/` - Android end-to-end flows

## Local start

Prerequisites: Docker Desktop/Engine, Java 21, and Node.js 22.13 or newer supported by the pinned Expo stack.

```sh
cp .env.example .env
docker compose up --build
```

The backend is available only on `http://127.0.0.1:8080` by default. In another terminal:

```sh
cd mobile
npm ci
npm start
```

Open the project in Expo Go or an Android emulator. The default development API URL is `http://localhost:8080/api/v1`; a physical phone must use a reachable LAN development URL or the recommended Tailscale HTTPS deployment.

## Demo data

Development-only acceptance data is seeded idempotently when `SPRING_PROFILES_ACTIVE=demo` and an owner account is configured. Do not enable `demo` for production.

## Quality gates

```sh
cd backend && ./gradlew check pitest
cd mobile && npm run format:check && npm run lint && npm run typecheck && npm run test:coverage
```

See [testing](docs/testing.md) for reports and E2E commands.

## Deploy and recover

1. Complete [owner onboarding](docs/owner-onboarding.md).
2. Follow the private [homelab and Tailscale deployment](docs/deployment.md).
3. Configure and test [backup and restore](docs/backup-restore.md).

No paid service, Firebase, Supabase, bank integration, or public API exposure is required.
