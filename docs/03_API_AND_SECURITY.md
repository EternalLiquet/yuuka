# API and Security

## API conventions

- Base path: `/api/v1`
- JSON
- ISO-8601 dates and timestamps
- consistent error envelope
- pagination for growing collections
- `409 Conflict` for optimistic-lock failures
- OpenAPI document generated and committed or generated in CI

Example error:

```json
{
  "code": "VALIDATION_ERROR",
  "message": "The request could not be completed.",
  "fieldErrors": {
    "amountMinor": "Amount must be greater than or equal to $0.00."
  },
  "details": {},
  "traceId": "..."
}
```

Business-rule errors that involve money should keep the API's integer money fields in
`details` and use user-readable messages. Mobile formats those details as currency before
display. Example:

```json
{
  "code": "PAYCHECK_OVER_ALLOCATED",
  "message": "This would over-allocate the paycheck.",
  "fieldErrors": {},
  "details": {
    "amountMinor": 98,
    "currencyCode": "USD"
  },
  "traceId": "..."
}
```

## Required endpoints

### Authentication

- `POST /auth/login`
- `POST /auth/refresh`
- `POST /auth/logout`
- `GET /me`
- `PATCH /me/settings` updates owner-backed settings such as the recurring Bill suggestion window.

### Paychecks

- `GET /paychecks/active`
- `GET /paychecks/history`
- `POST /paychecks`
- `POST /paychecks/from-draft`
- `POST /paychecks/from-template`
- `GET /paychecks/{id}`
- `PATCH /paychecks/{id}`
- `POST /paychecks/{id}/close`
- `POST /paychecks/{id}/reopen`
- `DELETE /paychecks/{id}` for archive/soft delete

`POST /paychecks/from-draft` creates a normal Active paycheck from a complete edited draft request.
It accepts paycheck fields plus an ordered `entries` array, validates the full allocation
transactionally, creates entries in supplied order, initializes each entry as Not Paid with an
initial status event, writes normal creation audit, and rolls back the whole create on failure. The
request is source-independent: it does not accept source paycheck IDs, entry IDs, versions,
statuses, bucket purchases, Payback assignments, or copied history.

### Entries

- `POST /paychecks/{paycheckId}/entries`
- `paybackId` may be supplied on entry create/update to assign that entry to an Active Payback.
- `sinkingFundId` may be supplied on `SINKING_FUND` entry create/update to assign that entry to an
  Active persistent Sinking Fund.
- Bill entry create/update requests and responses include optional `paymentMethod` values of `AUTOPAY` or `MANUAL`; non-Bill entries must not carry a payment method.
- `POST /paychecks/{paycheckId}/leftover-entry` creates a normal `BILL` named `LEFTOVER` for the exact current unallocated amount when the supplied paycheck version is current.
- `PATCH /entries/{id}`
- `DELETE /entries/{id}`
- `POST /entries/{id}/status`
- `GET /entries/{id}/status-history`
- `POST /paychecks/{paycheckId}/entries/reorder`

Status-change request:

```json
{
  "toStatus": "PROCESSING",
  "effectiveAt": "2026-07-10T12:00:00-04:00",
  "note": "Paid from checking"
}
```

### Bucket transactions

- `GET /entries/{entryId}/bucket-transactions`
- `POST /entries/{entryId}/bucket-transactions` with positive `amountMinor`, `effectiveDate`, optional `description`, and optional `notes`.
- `PATCH /bucket-transactions/{id}` with positive `amountMinor`, `effectiveDate`, optional `description`, optional `notes`, and `version`.
- `DELETE /bucket-transactions/{id}`
- `GET /spending-buckets/performance/rolling?days=30|90` returns a current 30- or 90-day snapshot across qualifying Active, Closed, and Archived paychecks; `days` defaults to `30`, unsupported values are rejected, and `summary` is absent only when there are zero qualifying live Spending Bucket entries.
- `GET /spending-buckets/performance/rolling-90-days` remains available as a compatibility endpoint delegating to the 90-day calculation.

### Paybacks

- `GET /paybacks` returns active and paid-off Paybacks plus a summary total.
- `POST /paybacks`
- `GET /paybacks/{id}`
- `PATCH /paybacks/{id}`
- `POST /paybacks/reorder` persists the owner-defined Payback order used by the Paybacks screen and selectors.
- `DELETE /paybacks/{id}` soft-deletes the Payback, reverses any active repayments, and clears live entry assignments in one transaction.
- `GET /paybacks/{id}/repayments` returns active and reversed repayment history.

Payback business-rule errors use structured money details for mobile formatting. For example,
overpayment uses `PAYBACK_REPAYMENT_OVERPAID` with `details.amountMinor` and `details.currencyCode`
instead of embedding raw storage values in the message.

### Sinking Funds

- `GET /sinking-funds?includeArchived=false` returns owner-scoped Sinking Funds plus a summary of
  active balances.
- `POST /sinking-funds` creates a persistent Sinking Fund with optional target fields, notes, and
  optional opening balance transaction.
- `GET /sinking-funds/{id}`
- `PATCH /sinking-funds/{id}`
- `POST /sinking-funds/reorder` persists the owner-defined order for Active Sinking Funds.
- `POST /sinking-funds/{id}/archive` archives a fund after version validation, blocks pending live
  linked entries, and requires positive-balance confirmation when applicable.
- `POST /sinking-funds/{id}/restore` restores an archived fund.
- `GET /sinking-funds/{id}/transactions` returns paged transaction history ordered by effective
  date, creation time, and ID descending.
- `POST /sinking-funds/{id}/withdrawals` creates a withdrawal transaction after version and balance
  validation.
- `POST /sinking-fund-transactions/{id}/reverse` reverses a withdrawal without deleting history.

Persistent Sinking Fund balances and progress are derived from transaction history. Controllers and
mobile clients must not persist or duplicate the authoritative balance calculation.

### Templates

- `GET /templates`
- `POST /templates`
- `GET /templates/{id}`
- `PATCH /templates/{id}`
- `POST /templates/{id}/duplicate`
- `POST /templates/{id}/archive`
- `POST /templates/{id}/restore`
- CRUD for template entries
- reorder template entries

### Recurring Bills

- `GET /recurring-bills?status=ACTIVE|INACTIVE|ALL&search=...`
- `POST /recurring-bills`
- `GET /recurring-bills/timeline?from=YYYY-MM-DD&through=YYYY-MM-DD`
- `GET /recurring-bills/{definitionId}`
- `PUT /recurring-bills/{definitionId}`
- `POST /recurring-bills/{definitionId}/activate`
- `POST /recurring-bills/{definitionId}/deactivate`
- `DELETE /recurring-bills/{definitionId}?version=...`
- `POST /paychecks/{paycheckId}/recurring-bill-imports`

Timeline ranges are inclusive and bounded to 366 days. They return dynamically derived active
monthly occurrences together with import counts/status for the requested period. Existing-paycheck
imports require the current paycheck version, validate every selected occurrence and the aggregate
allocation, and either create the complete ordered Bill snapshot batch or roll back. Draft creation
requests may carry nullable recurring-definition and occurrence provenance for Bill entries.

### Search

- `GET /search/entries` searches owner-scoped live entries by case-insensitive partial text in entry or paycheck names, exact `amountMinor`, and optional `scope` of `ALL`, `ACTIVE`, or `HISTORY`.

### Audit

- `GET /paychecks/{id}/audit`
- `GET /entries/{id}/audit`

### Health

- `GET /health` returns liveness and the packaged backend version.
- `GET /health/live` returns the same liveness contract explicitly for deployment checks.
- `GET /health/ready` checks dependency readiness, including PostgreSQL.

Liveness response:

```json
{
  "status": "UP",
  "version": "1.0.2"
}
```

Readiness response:

```json
{
  "status": "UP"
}
```

Health endpoints are unauthenticated so Docker, local deployment scripts, and tailnet-only
monitoring can check the process without API credentials. They must not expose environment
variables, secrets, database URLs, hostnames, stack traces, or authentication configuration.

## Security requirements

- Every private endpoint authenticated.
- Every query and mutation validates ownership.
- Cross-user access returns forbidden or not found without leaking data.
- Passwords hashed with BCrypt.
- Access tokens short-lived.
- Refresh tokens rotated, hashed at rest, and revocable.
- Login rate limiting where practical.
- No secrets committed.
- No permissive production CORS without documented justification.
- Database is not publicly exposed.
- Do not log passwords, tokens, or full sensitive request bodies.
- Mobile stores tokens only in secure storage.

## Cost and deployment requirements

The finished system must require no mandatory monthly paid service.

Do not require:

- Supabase,
- Firebase,
- paid hosting,
- paid authentication,
- paid database,
- paid domain,
- paid build system.

Provide Docker Compose with:

- postgres,
- backend,
- persistent volume,
- health checks,
- restart policy,
- environment configuration.

Provide documentation for:

- homelab deployment,
- Tailscale access,
- configurable mobile API URL,
- nightly `pg_dump`,
- encrypted off-machine backup,
- restore procedure,
- restore verification.
