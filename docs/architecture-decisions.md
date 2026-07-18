# Architecture Decisions

## Paycheck-first aggregate

Yuuka treats each paycheck as the budgeting aggregate. Entries belong to a paycheck, and Active/History visibility is derived from lifecycle and completion state. No monthly-budget aggregate is introduced.

## Exact money

All persisted and API money uses signed 64-bit integer minor units. Mobile input parsing uses string and `BigInt` arithmetic before converting only safe integers. Decimal percentages are presentation metrics, never stored money.

User-facing text must always render money as formatted currency, such as `$0.98`.
Internal storage terminology such as "minor units" and API field names such as
`amountMinor` must not appear in visible app errors or validation messages.

## Template snapshots

Applying a template copies entries in one database transaction. Paychecks retain only a source template ID for provenance; later edits in either direction cannot mutate the other aggregate.

## Recurring Bills are dynamic definition sources

Recurring Bills are owner-scoped definitions rather than scheduled paycheck entries. The backend
derives monthly occurrence dates on demand and clamps missing due days to the month's final day.
Imports create normal independent Bill snapshots; nullable definition-and-occurrence provenance is
informational and never makes later definition edits cascade into a paycheck. Existing-paycheck
batch imports lock the paycheck, validate optimistic version and total allocation, then persist all
selected snapshots in one transaction.

## Immutable history

Every status change appends an event with separate effective and recorded timestamps. Status and audit tables have PostgreSQL update/delete rejection triggers so application defects cannot rewrite history.

## Concurrency and ownership

Mutable aggregates use optimistic versions and stale requests receive `409 Conflict`. Every lookup is scoped to the authenticated owner, and cross-owner IDs resolve as not found.

## Payback repayments

Paybacks are their own aggregate, but repayment application is integrated into the paycheck-entry
status transaction. A paycheck entry has at most one Payback assignment. Posted status creates one
active repayment row for the entry amount; moving backward reverses that row instead of deleting
history. Returning to Posted creates a new active row, while the partial unique index on active
repayments prevents duplicate application for the same entry.

Current Payback balances are derived from opening remaining amount minus active repayments. A zero
opening remaining amount is accepted and creates a Paid Off Payback, which lets already-settled
borrowed money be recorded without fake repayments.

Deleting a Payback is a cleanup transaction, not a history erase. The service locks the Payback,
validates the supplied version, reverses any active repayment rows with the deletion timestamp,
clears live paycheck-entry assignments, touches affected paychecks, and soft-deletes the Payback.
Previously reversed repayments remain as audit history. Normal Payback reads and selectors exclude
deleted Paybacks.

Paybacks store an integer `position` scoped to the owner. New Paybacks append to the live order, and
reorder requests must include every live Payback ID exactly once. Active and Paid Off sections may
be shown separately, but each section preserves the persisted order.

## Persistent Sinking Funds

Persistent Sinking Funds are owner-scoped aggregates separate from paycheck allocation. A paycheck
entry may link to at most one Active Sinking Fund, but the entry still reserves only its own
paycheck contribution. Posted status creates one active contribution transaction for the entry
amount; moving backward, deleting the entry, or changing a Posted linked entry reverses the active
contribution instead of deleting history. Returning to Posted creates a new active contribution,
while a partial unique index prevents duplicate active contribution rows for the same entry.

Current Sinking Fund balances are derived from unreversed opening-balance, contribution, and
withdrawal transactions. The Sinking Fund table stores metadata, state, order, and target fields,
not current balance totals. Withdrawals and withdrawal reversals are explicit transaction rows and
version-guarded workflows so mobile and controllers do not duplicate the authoritative balance
rules.

Sinking Funds store an integer `position` scoped to the owner. New funds append to the active
order, and reorder requests must include every active Sinking Fund ID exactly once. Archived funds
remain readable with transaction history and can be restored.

## Runtime version reporting

Yuuka release tags are the version source of truth. CI converts a successful `master` release tag
into a Gradle build property, Spring Boot writes that value to build metadata, and the packaged
backend reports it through `/health/version`, `/health/live`, and `/health`. Local tagged builds
derive the exact checked-out tag in Gradle, stripping a leading `v`, so homelab rebuilds from
release tags report the release version. Untagged local builds fall back to `0.0.0-dev`.

`/health/version` returns only `{ "version": "..." }`, is publicly readable with the rest of the
health suite, and does not query PostgreSQL. Liveness only reports process status and safe build
version metadata. Readiness remains separate at `/health/ready` and may check required dependencies
such as PostgreSQL. Docker health checks continue to use readiness, while deployment verification
should check both readiness and liveness/version.

## Android E2E cadence

Backend, mobile unit/component, contract, Docker, and export checks gate pull requests and `master`
push releases. Android emulator/Maestro E2E runs in a separate nightly workflow with manual dispatch
because it is slower and depends on shared-runner emulator stability. A failed nightly E2E run must
still be treated as a product quality issue, but it does not block release tagging for unrelated
backend or documentation changes.

## Authentication boundary

The mobile app uses short-lived signed access JWTs and one-time rotating opaque refresh tokens. Only refresh-token hashes are stored. Replaying a rotated token revokes its family. Production is single-owner, registration-disabled, password-plus-TOTP, and private-network-first.

## Deferred search typo tolerance

Global entry search currently supports exact amount matching plus case-insensitive partial text
matching. If typo-tolerant entry search becomes important, prefer PostgreSQL `pg_trgm` over
application-side fuzzy matching or paid search libraries. A future migration can enable the
extension and add trigram indexes for live entry and paycheck names, then rank exact and substring
matches ahead of trigram similarity matches. Only apply fuzzy matching to reasonably long queries
such as four or more characters to avoid noisy short-query results. The expected tradeoff is extra
index storage and slightly slower writes, with little impact on normal reads because PostgreSQL
chooses query plans per query.
