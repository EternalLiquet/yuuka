# Architecture Decisions

## Paycheck-first aggregate

Yuuka treats each paycheck as the budgeting aggregate. Entries belong to a paycheck, and Active/History visibility is derived from lifecycle and completion state. No monthly-budget aggregate is introduced.

## Exact money

All persisted and API money uses signed 64-bit integer minor units. Mobile input parsing uses string and `BigInt` arithmetic before converting only safe integers. Decimal percentages are presentation metrics, never stored money.

## Template snapshots

Applying a template copies entries in one database transaction. Paychecks retain only a source template ID for provenance; later edits in either direction cannot mutate the other aggregate.

## Immutable history

Every status change appends an event with separate effective and recorded timestamps. Status and audit tables have PostgreSQL update/delete rejection triggers so application defects cannot rewrite history.

## Concurrency and ownership

Mutable aggregates use optimistic versions and stale requests receive `409 Conflict`. Every lookup is scoped to the authenticated owner, and cross-owner IDs resolve as not found.

## Authentication boundary

The mobile app uses short-lived signed access JWTs and one-time rotating opaque refresh tokens. Only refresh-token hashes are stored. Replaying a rotated token revokes its family. Production is single-owner, registration-disabled, password-plus-TOTP, and private-network-first.
