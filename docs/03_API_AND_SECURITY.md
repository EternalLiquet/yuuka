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

### Paychecks

- `GET /paychecks/active`
- `GET /paychecks/history`
- `POST /paychecks`
- `POST /paychecks/from-template`
- `GET /paychecks/{id}`
- `PATCH /paychecks/{id}`
- `POST /paychecks/{id}/close`
- `POST /paychecks/{id}/reopen`
- `DELETE /paychecks/{id}` for archive/soft delete

### Entries

- `POST /paychecks/{paycheckId}/entries`
- `paybackId` may be supplied on entry create/update to assign that entry to an Active Payback.
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
