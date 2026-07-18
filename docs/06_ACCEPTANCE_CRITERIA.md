# Acceptance Criteria

## Definition of done

V1 is complete only when:

- the backend starts through Docker Compose,
- PostgreSQL migrations apply cleanly,
- the mobile app builds,
- authentication works,
- all critical user flows work,
- unit tests pass,
- PostgreSQL integration tests pass,
- mobile component tests pass,
- configured end-to-end tests pass,
- coverage thresholds pass,
- linting and formatting pass,
- OpenAPI contract validation passes,
- no mandatory paid service is required,
- setup, deployment, backup, and restore documentation are accurate.

## Functional acceptance

### New paycheck from scratch

Creating a paycheck named Free Paycheck for $1,977.57 dated July 17, 2026 produces:

- allocated $0.00,
- unallocated $1,977.57,
- Active-list presence,
- created and updated timestamps,
- audit record.

Adding a $150.00 Groceries bucket produces unallocated $1,827.57.

### New paycheck from template

A template totaling $1,939.20 may create a paycheck for $1,939.23.

The result must preserve exact cents and show $0.03 unallocated.

Editing copied Electricity from $130.50 to $130.52 must not modify the template.

### Status audit

Changing Verizon from Not Paid to Processing with an earlier effective date must preserve:

- current status,
- old status,
- new status,
- chosen effective date,
- actual recorded timestamp,
- immutable transition history.

### Sorting

Sorting by amount must not overwrite custom order. Clearing the sort restores custom order.

### Bucket transactions

A $50.00 Work Food bucket with $12.35 and $9.10 spending has $28.55 remaining.
Editing those purchases updates spent and remaining totals. A $51.00 purchase against the same
$50.00 bucket shows $51.00 spent and $1.00 over budget.

### Expense Ledgers

An Open Expense Ledger with $25.00 and $30.00 live items derives a $55.00 total. Deleting one item
removes it from the derived total. The ledger cannot finalize while empty, becomes read-only after
finalization, can reopen before settlement, and cannot reopen after settlement.

Settling as Bill creates one normal Not Paid Bill in a selected active paycheck for the exact
server-derived amount and stores source-ledger provenance. Settling as Payback creates one normal
Payback whose original and opening remaining amounts equal the server-derived total. Repeated or
concurrent settlement attempts must not create more than one target or settlement row.

### History

A fully allocated and fully Posted ordinary paycheck closes into History automatically. Reopening
returns it to Active and creates an audit event; a deliberately reopened paycheck remains Active
until it is explicitly closed again.

### Concurrency

Two stale edits to the same version must not silently overwrite. The stale request receives `409 Conflict`.

### Security

One user cannot read or mutate another user’s records through direct API requests.

## Seed data

Include development seed/demo data similar to:

```text
UTILITIES 1/2 — $1,939.23

State Farm — $238.97 — Posted
Electricity — $130.52 — Processing
QuickBooks — $45.00 — Not Paid
BRZ — $462.73 — Posted
Cayenne — $341.58 — Posted
Fansly — $5.00 — Not Paid
Patreon — $5.35 — Not Paid
YouTube Membership — $4.99 — Not Paid
Microsoft — $14.05 — Not Paid
Ride with GPS — $8.65 — Not Paid
YouTube Premium — $13.99 — Not Paid
Clip Studio Paint — $5.23 — Not Paid
Verizon — $152.61 — Not Paid
Groceries — $150.00 — Spending Bucket
Work Food — $50.00 — Spending Bucket
Normal Car Gas — $50.00 — Spending Bucket
Misc/Life — $75.00 — Spending Bucket
```

This is demo/test data only, not hard-coded business logic.

## CI quality gates

CI must fail when any of the following fail:

- backend tests,
- mobile tests,
- coverage thresholds,
- lint,
- type check,
- formatting check,
- Flyway migration validation,
- PostgreSQL integration tests,
- API contract validation,
- configured critical end-to-end tests.

## Required completion report

Codex must report:

- what was built,
- architecture decisions,
- setup commands,
- deployment steps,
- exact coverage percentages,
- unit-test results,
- integration-test results,
- end-to-end results,
- known limitations,
- unfinished work.
