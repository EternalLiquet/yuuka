# Architecture and Data Model

## Repository layout

```text
yuuka/
├── backend/
├── mobile/
├── docs/
├── docker-compose.yml
├── .env.example
├── README.md
└── .github/workflows/
```

## Mobile stack

- Expo React Native
- TypeScript strict mode
- Expo Router
- TanStack Query
- React Hook Form
- Zod
- Expo SecureStore
- Jest
- React Native Testing Library
- Maestro or equivalent for Android end-to-end tests

## Backend stack

- Java 21
- Spring Boot 3
- Gradle Kotlin DSL
- Spring Web
- Spring Security
- Spring Data JPA
- Bean Validation
- Flyway
- PostgreSQL
- JUnit 5
- AssertJ
- Testcontainers
- MockMvc or REST Assured
- JaCoCo
- PIT mutation testing where practical

## Infrastructure

- Docker Compose
- PostgreSQL container
- Spring Boot API container
- Tailscale-compatible private access
- no mandatory paid cloud service
- no public port forwarding required

## Runtime topology

```text
Android phone
    |
Tailscale private network
    |
Spring Boot API on homelab
    |
PostgreSQL on homelab
```

## Authentication

Use Spring Security with:

- email and password,
- BCrypt password hashes,
- short-lived access JWT,
- rotating refresh tokens,
- refresh-token revocation,
- public registration disabled by default.

The first user may be provisioned through a bootstrap command, setup script, or environment-driven one-time initializer.

Store mobile tokens in Expo SecureStore, never plain AsyncStorage.

## Suggested domain tables

### users

- id UUID
- email
- password_hash
- display_name
- currency_code
- timezone
- enabled
- created_at
- updated_at

### refresh_tokens

- id UUID
- user_id
- token_hash
- expires_at
- revoked_at
- replaced_by_token_id
- created_at

### paychecks

- id UUID
- owner_id
- name
- source
- amount_minor BIGINT
- income_date DATE
- state: ACTIVE, CLOSED, ARCHIVED
- template_source_id nullable
- notes
- created_at
- updated_at
- closed_at nullable
- archived_at nullable
- optimistic-lock version

### paycheck_entries

- id UUID
- owner_id
- paycheck_id
- entry_type: BILL, SPENDING_BUCKET, SINKING_FUND
- name
- amount_minor BIGINT
- status: NOT_PAID, PROCESSING, POSTED
- position
- due_date nullable
- account_name nullable
- payee nullable
- notes nullable
- target_minor nullable
- target_date nullable
- created_at
- updated_at
- deleted_at nullable
- optimistic-lock version

### entry_status_events

Append-only:

- id UUID
- owner_id
- entry_id
- from_status nullable
- to_status
- effective_at
- recorded_at
- note nullable

### bucket_transactions

- id UUID
- owner_id
- entry_id
- amount_minor BIGINT
- description nullable
- notes nullable
- effective_date
- created_at
- updated_at
- deleted_at nullable
- optimistic-lock version

Amounts are positive purchase records. Corrections are made by editing or deleting the original
transaction; refund-style negative transactions are not part of the current contract.

### templates

- id UUID
- owner_id
- name
- description nullable
- archived
- created_at
- updated_at
- optimistic-lock version

### template_entries

- id UUID
- owner_id
- template_id
- entry_type
- name
- default_amount_minor BIGINT
- position
- default_due_offset_days nullable
- account_name nullable
- payee nullable
- notes nullable
- target_minor nullable
- target_date nullable
- created_at
- updated_at
- optimistic-lock version

### audit_events

Append-only:

- id UUID
- owner_id
- entity_type
- entity_id
- action
- effective_at nullable
- recorded_at
- before_data JSONB nullable
- after_data JSONB nullable
- metadata JSONB nullable

## Data integrity

- Use Flyway migrations.
- Use real foreign keys.
- Child owner IDs must match parent ownership.
- Soft-deleted entries do not contribute to totals.
- Use database constraints in addition to service validation.
- Use optimistic locking for mutable aggregate roots.
- Critical multi-record operations must be transactional.

## Critical atomic operations

- create paycheck from template,
- status transition plus status-event insert,
- entry reorder,
- bucket transaction plus audit event,
- close/reopen paycheck,
- refresh-token rotation.

## Derived values

Do not make derived totals directly editable.

For paycheck:

- allocated amount,
- unallocated amount,
- allocation percentage,
- posted amount,
- processing amount,
- not-paid amount,
- completion percentage,
- counts by status,
- last-edited timestamp.

For bucket:

- spent amount = sum(active bucket transactions),
- remaining amount = budget minus spent.

For Payback:

- repaid amount = sum(payback repayments where `reversed_at` is null),
- remaining amount = opening remaining amount minus repaid amount,
- progress percentage = repaid amount divided by opening remaining amount for presentation only.

Payback baseline amounts are mutable through optimistic-lock guarded updates, but opening remaining
amount cannot be reduced below already active repayments. Repayment application and reversal are
critical atomic operations tied to paycheck-entry status changes.
