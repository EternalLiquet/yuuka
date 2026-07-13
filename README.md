# Yuuka

[![CI](https://github.com/EternalLiquet/yuuka/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/EternalLiquet/yuuka/actions/workflows/ci.yml)
[![Android E2E](https://github.com/EternalLiquet/yuuka/actions/workflows/android-e2e.yml/badge.svg?branch=master)](https://github.com/EternalLiquet/yuuka/actions/workflows/android-e2e.yml)
[![Version](https://img.shields.io/github/v/tag/EternalLiquet/yuuka?label=version&sort=semver)](https://github.com/EternalLiquet/yuuka/tags)
[![Release](https://img.shields.io/github/v/release/EternalLiquet/yuuka?display_name=tag&sort=semver)](https://github.com/EternalLiquet/yuuka/releases)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-f89820?logo=openjdk&logoColor=white)](backend)
[![Spring Boot 3](https://img.shields.io/badge/Spring%20Boot-3-6DB33F?logo=springboot&logoColor=white)](backend)
[![Expo](https://img.shields.io/badge/Expo-React%20Native-000020?logo=expo&logoColor=white)](mobile)
[![TypeScript](https://img.shields.io/badge/TypeScript-strict-3178C6?logo=typescript&logoColor=white)](mobile)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql&logoColor=white)](docker-compose.yml)
[![Docker](https://img.shields.io/badge/Docker-compose-2496ED?logo=docker&logoColor=white)](docker-compose.yml)
[![Private self-hosted](https://img.shields.io/badge/private-self--hosted-7C3AED)](docs/deployment.md)

<p align="center">
  <img src="docs/assets/yuuka.gif" alt="Yuuka at a desk" width="360">
</p>

Yuuka is a paycheck-first budgeting app for bills, spending buckets, and sinking funds. It is a self-hosted, single-owner system with an Expo React Native client and a Spring Boot/PostgreSQL API.

## What is implemented

- Active paycheck and paycheck-detail workflows with exact-cent allocation metrics
- Scratch paycheck creation, basic bill/spending-bucket/sinking-fund entries, filtering, sorting, and persistent custom order
- Not Paid, Processing, and Posted transitions with immutable status history
- Close, reopen, archive, owner isolation, optimistic locking, and backend audit recording
- Paybacks with mobile list/detail/create/edit/delete/reorder flows, repayment history, and paycheck-entry repayment assignment
- Spending-bucket purchase ledgers with derived spent, remaining, and over-budget display
- Password plus optional/deployment-required TOTP, JWT access tokens, and rotating refresh tokens
- Dark-first mobile UI with light/system preferences

Template management and full audit-history UI remain later-scope mobile features. Backend
foundations and contracts exist where needed, but visible mobile template screens are placeholders
until the next specification slice.

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

CI runs those gates, Compose validation, Docker image build validation, Expo Doctor, and Android
export for pull requests and pushes to `master`. The critical Android Maestro flow runs nightly and
can be started manually when a mobile change needs emulator coverage. Successful `master` pushes
publish the next `vMAJOR.MINOR.PATCH` tag and GitHub Release. The running backend reports its
packaged version at `/health/live`.

See [testing](docs/testing.md) for reports and E2E commands.

## Deploy and recover

1. Complete [owner onboarding](docs/owner-onboarding.md).
2. Follow the private [homelab and Tailscale deployment](docs/deployment.md).
3. Configure and test [backup and restore](docs/backup-restore.md).

No paid service, Firebase, Supabase, bank integration, or public API exposure is required.
