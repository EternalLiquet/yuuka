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

## Authentication boundary

The mobile app uses short-lived signed access JWTs and one-time rotating opaque refresh tokens. Only refresh-token hashes are stored. Replaying a rotated token revokes its family. Production is single-owner, registration-disabled, password-plus-TOTP, and private-network-first.
