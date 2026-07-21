# Product and Domain Rules

## Primary user

The first release is for one technically capable user managing personal finances. The architecture may support multiple accounts, but public registration and consumer SaaS features are not required.

## Core user outcomes

The user must be able to:

1. Authenticate.
2. See active paychecks that still require attention.
3. Create a paycheck from scratch.
4. Create a paycheck from a reusable template.
5. Duplicate an existing paycheck into a reviewed editable draft.
6. Enter the exact paycheck amount manually each time.
7. Add bills, spending buckets, and sinking funds.
8. Track every entry as Not Paid, Processing, or Posted.
9. Edit the effective date of a status change.
10. Preserve the real recorded timestamp separately.
11. Reorder entries.
12. Filter and sort entries.
13. View historical paychecks and progress.
14. View an append-only audit history.
15. Capture expense-first ledgers and settle finalized totals into a Bill or Payback.

## Paychecks and income events

The user-facing term is **Paycheck**, but the model must support any incoming money event:

- normal employment pay,
- free paycheck,
- bonus,
- refund,
- reimbursement,
- side income,
- arbitrary lump sum.

Do not hard-code a four-paycheck rotation. Common names such as Rent 1/2, Rent 2/2, Utilities 1/2, and Utilities 2/2 are user-created templates.

Every paycheck has:

- name,
- exact amount,
- income date,
- optional source,
- optional notes,
- state,
- created timestamp,
- updated timestamp,
- optional source template,
- derived allocation and completion values.

## Active paychecks

A paycheck belongs in Active if either condition is true:

- it is not fully allocated, or
- at least one non-deleted entry is not Posted, or
- it was deliberately reopened and has not been explicitly closed again.

A paycheck can be 100% allocated and still remain active when at least one live entry is not Posted.
When a normal, never-reopened paycheck becomes fully allocated and every live entry is Posted, the
system automatically closes it into History. Deliberately reopened paychecks stay Active until the
user explicitly closes them again.

## History

History contains closed, archived, or legacy completed paychecks that predate automatic lifecycle
synchronization. Historical paychecks remain readable, may be reopened, and remain auditable.

## Templates

A template is a reusable ordered set of default entries.

Templates support:

- create,
- edit,
- duplicate,
- archive,
- restore,
- reorder,
- create paycheck from template.

Applying a template copies its entries into a new paycheck. The resulting entries are independent snapshots. Editing a paycheck must not alter the template, and later template edits must not alter existing paychecks.

## Duplicate Paycheck

Any readable paycheck can be duplicated into a local editable draft, including Active, partially
completed, Closed, Archived, and deliberately reopened paychecks. Duplication never modifies the
source paycheck and does not create persistent source lineage.

The duplicate draft pre-fills paycheck name, amount, source, and notes from the source paycheck.
The income date uses the normal new-paycheck default and must be reviewed before the entry draft is
created. Duplicate names are allowed and Yuuka does not prepend `Copy of`.

The entry draft copies live source entries in saved custom order, excluding generated `LEFTOVER`
entries. It copies entry type, name, amount, notes, Bill payment method/account/payee/due-date
relationship, and unlinked Sinking Fund target fields. It does not copy entry IDs, versions,
statuses, status history, timestamps, audit history, Spending Bucket purchases or spent totals,
Payback assignments, Sinking Fund assignments, Payback repayment history, Sinking Fund
transactions, or soft-deleted entries. Every saved duplicate entry starts as Not Paid, and the new
paycheck starts Active.

Bill due dates are shifted by preserving the source due-date offset from the source paycheck income
date and applying it to the new income date. Unlinked Sinking Fund target dates stay exact.
Payback and persistent Sinking Fund assignments are cleared by omission and the mobile draft
reports how many were not copied.

## Recurring Bill definitions

A recurring Bill definition is an owner-scoped planning source, not a paycheck entry and not an
automatic payment. The first supported recurrence is monthly. A definition stores its name,
typical amount, Bill payment method, due day, optional account/payee/notes, and active state.

Monthly occurrences are derived dynamically. If the configured due day does not exist in a month,
the occurrence uses that month's final calendar day, including leap-year February. Occurrences are
not persisted as jobs or materialized future records.

Importing an occurrence creates an ordinary independent `BILL` snapshot in a paycheck. The snapshot
starts Not Paid, reserves its full amount, preserves the selected occurrence date and optional
definition provenance, and may be edited or deleted like any other Bill. Later definition changes,
deactivation, or deletion do not mutate imported entries. Duplicate imports are allowed. Imports
into an existing paycheck validate the full batch and allocation transactionally; a failure creates
no entries. Importing into a local scratch, template, or duplicate draft remains local until the
paycheck is created.

The owner controls the suggestion window in days, from 1 through 31, with a default of 7. Suggested
occurrences are derived relative to the paycheck income date. Selection is always explicit and the
user may override only this paycheck's amount or also update the definition's typical amount.

## Entry types

### Bill

A specific obligation, such as rent, utilities, insurance, subscriptions, loans, or credit-card payments.

Bills may be marked as **Autopay** or **Manual Pay**. This is informational and filterable; it
does not automate payment, change status transitions, or affect allocation math.

### Spending Bucket

A spending allowance intended to be consumed through one or more purchases, such as groceries, work food, gas, or Misc/Life.

A bucket tracks:

- budgeted amount,
- spent amount,
- remaining amount,
- individual spending adjustments,
- optional over-budget state.

The Home rolling Spending Bucket report is a current 30- or 90-day snapshot across qualifying Active,
Closed, and Archived paychecks. The default mobile view is 30 days. It includes owner-scoped
paychecks with income dates from `asOfDate - 29 days` through `asOfDate` for 30 days, or
`asOfDate - 89 days` through `asOfDate` for 90 days. It includes live Spending Bucket budgets even
when they have no purchases, and excludes future-dated paychecks, soft-deleted entries,
soft-deleted purchases, non-bucket entries, and purchases effective after `asOfDate`.

## Home financial dashboard

Home is a read-only financial orientation surface. It summarizes and links to existing records; it
does not own editable financial state, management forms, or entry-status mutations.

Needs Attention returns no more than five owner-scoped items. Eligible items are unallocated Active
paychecks, Not Paid Manual Pay Bills in Active paychecks, Processing entries that have remained in
that state for at least three owner-local calendar days, over-budget non-Posted Spending Buckets in
Active paychecks, and Finalized unsettled Expense Lists. Deleted records and records outside the
relevant Active/finalized lifecycle are excluded. Past-due Manual Pay Bills come first, followed by
unallocated paychecks, long-running Processing entries, over-budget non-Posted buckets, and
Finalized Expense Lists. Other Not Paid Manual Pay Bills remain eligible after those prioritized
groups. Amount/date ordering and UUID tie-breakers make the result deterministic.

The Active summary keeps allocation separate from completion: it reports visible Active paycheck
count, total unallocated money, live Not Paid and Processing counts, and at most two previews. The
previews prioritize unallocated paychecks, then higher non-Posted counts, then newer income dates.
Payback, Planned Savings, and Expense List positions reuse their authoritative derived totals and
state rules; dashboard totals are never persisted.

### Sinking Fund

A contribution toward a future purpose, such as tires, travel, car maintenance, or an emergency
reserve.

The mobile UI calls this domain concept **Planned Savings**. Internal domain, API, and persistence
terminology remains Sinking Fund.

A Sinking Fund may be a persistent owner-scoped fund with an optional target amount, target date,
notes, owner-defined order, current balance derived from transactions, and Active or Archived
state. A persistent fund can start with an optional opening balance. Opening balances,
contributions, withdrawals, and reversals are transaction history, not persisted derived totals.

A paycheck entry of type `SINKING_FUND` may link to one Active persistent Sinking Fund. The entry
still reserves only that paycheck's contribution in allocation. Linking does not change the fund
balance until the entry reaches Posted. Moving a linked entry out of Posted reverses the active
contribution; returning it to Posted creates a new active contribution while preserving reversed
history. Editing a Posted linked entry's amount or assignment reverses the old contribution and
applies the new one transactionally. When a Posted entry stays linked to the same persistent fund,
Yuuka validates the final replacement balance, `current - old contribution + new contribution`, so
valid net replacements are allowed even if a full standalone reversal would temporarily overdraw the
fund. Deleting a Posted linked entry reverses its active contribution.

Unlinked Sinking Fund entries retain entry-level target amount and target date for lightweight
per-paycheck planning. Linked entries use the persistent fund's target fields and return null
entry-level target fields in the API.

Withdrawals reduce a persistent fund's derived balance, require the latest fund version, and cannot
exceed the current balance. Reversing a withdrawal restores that balance contribution while
preserving the withdrawal row as reversed history. Archiving a fund blocks pending linked entries,
requires explicit confirmation when the fund still has a positive balance, and does not erase
history. Archived funds can be restored, and reversed history remains auditable.

## Entry statuses

Use these exact user-facing names:

1. **Not Paid** — the user has not initiated the payment or relevant spending action.
2. **Processing** — the user initiated it, but it has not appeared in the relevant account.
3. **Posted** — the user confirmed it appears in the bank or card account and affected the displayed balance.

The user may move forward or backward between statuses. Every transition creates a new immutable status record.

Each status transition records:

- previous status,
- new status,
- effective date/time chosen by the user,
- actual system-recorded timestamp,
- user,
- optional note.

## Allocation

All active entries reserve money immediately, regardless of status.

- `allocated = sum(active entry amounts)`
- `unallocated = paycheck amount - allocated`

A paycheck is 100% allocated when unallocated equals zero.

Allocation progress and payment completion are different concepts.

## Completion

- `posted amount = sum(amount of Posted entries)`
- `completion percent = posted amount / allocated amount`, or zero when allocated is zero.

Changing status must not change allocation.

## Over-allocation

Accidental over-allocation should be blocked. If an override is supported, it must require explicit confirmation, clearly show the excess, and create an audit event.

## Ordering

Default order is the user’s custom order. Temporary sorting must never destroy the saved custom order.

## Entry search

Global entry search supports case-insensitive partial text matching against entry and paycheck
names plus exact amount matching. Search results are owner-scoped, exclude soft-deleted entries,
and may be scoped to Active, History, or both.

## Expense Ledgers

The mobile UI calls these **Expense Lists**. Expense Ledger remains the internal domain, API, and
persistence name.

Expense Ledgers support expense-first workflows where the user records purchases before deciding
how the total should affect budgeting. An Expense Ledger is owner-scoped and moves through:

```text
OPEN -> FINALIZED -> SETTLED
```

- `OPEN` ledgers and items are editable.
- `FINALIZED` ledgers are read-only, eligible for settlement, and may be reopened until settled.
- `SETTLED` ledgers are permanently read-only historical records.

An Open ledger must have at least one live positive item before finalization. Ledger totals are
always derived from live items; Yuuka does not store or edit an authoritative cached total.

Expense items support a positive amount, a name or merchant, an expense date that cannot be in the
future, and optional notes. Categories, receipts, OCR, tax fields, payment accounts, tags, and
spreadsheet UI are outside the current feature.

Item creates and updates validate the final prospective ledger total while holding the ledger write
lock. Exactly the signed 64-bit maximum is valid; a larger total is rejected atomically with
`MONEY_AMOUNT_OVERFLOW` before an item, ledger version change, or audit event is persisted.

A Finalized ledger may settle exactly once as either:

- one ordinary Bill entry in an active paycheck that accepts the derived total, or
- one ordinary Payback with original and opening remaining amounts equal to the derived total.

Settlement recalculates the total server-side in the settlement transaction. The resulting Bill or
Payback stores nullable Expense Ledger provenance for navigation only. Later target edits do not
modify the ledger, later ledger reads do not synchronize the target, and target deletion does not
reopen or delete the ledger.

Settlement provenance stores the created Bill entry ID separately from its containing paycheck ID.
Payback settlement stores the Payback ID and no paycheck ID, so one target UUID never has two
navigation meanings.

## Dates and auditing

Every material record includes created and updated timestamps. Material edits must be audited, including:

- paycheck create/edit/close/reopen/archive,
- entry create/edit/delete/reorder,
- template create/edit/duplicate/archive,
- status changes,
- effective-date edits,
- bucket transaction changes,
- over-allocation overrides.

User-entered effective dates are editable. System-recorded timestamps are immutable.

## Money

Store money as integer minor units, such as cents.

Examples:

- $1,939.23 = `193923`
- $0.03 = `3`

Never use floating-point arithmetic for balances.

User-facing text must display formatted currency, not internal storage terminology.
For example, show `$0.98`, not `98 minor units`, and show `Amount`, not API field
names such as `amountMinor`.

## Paybacks

A Payback tracks money the owner borrowed from themself and intends to repay through paycheck
entries. It is separate from allocation: assigning a paycheck entry to a Payback does not change
the Payback balance until that entry reaches Posted status.

A single entry cannot be assigned to both a Payback and a persistent Sinking Fund. The user must
choose which balance the Posted entry will affect.

Payback terminology:

- original amount = the full amount initially owed,
- opening remaining amount = the unpaid balance when tracking began in Yuuka,
- repaid through Yuuka = active Posted paycheck-entry repayments recorded after creation,
- current remaining amount = opening remaining amount minus active repayments.

Payback states:

- **Active** — current remaining amount is greater than zero.
- **Paid Off** — current remaining amount is exactly zero.

When a Payback is created with a zero opening remaining amount, Yuuka records it as Paid Off.
Opening remaining amount cannot exceed original amount, and neither repayments nor edits may make
the current remaining amount negative. Money remains integer minor units internally and must be
formatted as currency in visible UI.

Repayment lifecycle:

- entries linked while Not Paid or Processing do not apply repayment,
- changing a linked entry to Posted applies exactly one repayment for that entry amount,
- changing a Posted entry back to Processing or Not Paid reverses the active repayment,
- returning the entry to Posted creates a new active repayment while preserving reversed history,
- deleting a Posted linked entry reverses the repayment in the same transaction,
- deleting a Payback reverses active repayments and unassigns linked live entries without changing
  entry status,
- Paybacks move automatically between Active and Paid Off as repayments apply or reverse.

Paybacks have a persistent owner-defined order. New Paybacks append to the end of the live order,
and selectors use that order instead of update time or database order.

For the MVP, one paycheck entry may link to at most one Payback, and the repayment amount is the
entry amount. Yuuka does not support interest, fees, schedules, recurring repayments, split
repayments, multiple Paybacks per entry, currency conversion, or bank synchronization.

## Non-goals for v1

Do not implement:

- bank aggregation,
- Plaid,
- automatic transaction import,
- automatic reconciliation,
- investment tracking,
- tax calculation,
- credit-score tracking,
- shared household budgets,
- social features,
- AI financial advice,
- subscription billing,
- paid cloud infrastructure.
