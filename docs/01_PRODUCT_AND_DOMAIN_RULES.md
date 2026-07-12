# Product and Domain Rules

## Primary user

The first release is for one technically capable user managing personal finances. The architecture may support multiple accounts, but public registration and consumer SaaS features are not required.

## Core user outcomes

The user must be able to:

1. Authenticate.
2. See active paychecks that still require attention.
3. Create a paycheck from scratch.
4. Create a paycheck from a reusable template.
5. Enter the exact paycheck amount manually each time.
6. Add bills, spending buckets, and sinking funds.
7. Track every entry as Not Paid, Processing, or Posted.
8. Edit the effective date of a status change.
9. Preserve the real recorded timestamp separately.
10. Reorder entries.
11. Filter and sort entries.
12. View historical paychecks and progress.
13. View an append-only audit history.

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
- at least one non-deleted entry is not Posted.

A paycheck can be 100% allocated and still remain active.

## History

History contains closed, archived, or completed paychecks. Historical paychecks remain readable, may be reopened, and remain auditable.

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

## Entry types

### Bill

A specific obligation, such as rent, utilities, insurance, subscriptions, loans, or credit-card payments.

### Spending Bucket

A spending allowance intended to be consumed through one or more purchases, such as groceries, work food, gas, or Misc/Life.

A bucket tracks:

- budgeted amount,
- spent amount,
- remaining amount,
- individual spending adjustments,
- optional over-budget state.

### Sinking Fund

A contribution toward a future purpose, such as tires, travel, car maintenance, or an emergency reserve.

A sinking fund may have a target amount and target date, but only the current paycheck contribution counts as current allocation.

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
- Paybacks move automatically between Active and Paid Off as repayments apply or reverse.

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
