# Mobile UX and User Flows

## Navigation

Bottom tabs:

1. Active
2. History
3. Templates
4. Settings

A prominent create action is available from Active.

## Authentication flow

- Sign in
- Session expired state
- Sign out
- Public registration not required

After authentication, open Active.

## Active tab

Show paychecks that are not fully allocated or contain at least one entry that is not Posted.

Each paycheck card shows:

- name,
- income date,
- exact amount,
- allocated amount and percent,
- unallocated amount,
- completion percent,
- counts for Not Paid, Processing, Posted,
- last edited.

Actionable information takes priority over charts.

## Paycheck detail

Header shows:

- name,
- income date,
- exact amount,
- allocated,
- unallocated,
- allocation progress,
- completion progress,
- edit menu.

Entries default to custom order.

Each entry row shows:

- name,
- amount,
- type,
- status text and icon,
- due date when relevant,
- last edited,
- bucket spent and remaining when relevant.

## Status editing

Tapping status opens a bottom sheet or compact menu with:

- Not Paid
- Processing
- Posted

After choosing a status:

- effective date/time defaults to now,
- user may edit it,
- optional note,
- confirm action.

Do not require opening a large edit form just to change status.

## Filters and sorting

Filters:

- all statuses,
- Not Paid,
- Processing,
- Posted,
- Bill,
- Spending Bucket,
- Sinking Fund.

Sort:

- custom order,
- amount,
- status,
- due date,
- last edited.

Clearly indicate active filters or non-default sorting.

## Reordering

Support drag-and-drop in custom-order mode and accessible Move Up / Move Down controls.

## Create paycheck flow

First choice:

- Use a Template
- Start from Scratch

### From scratch

Collect:

- name,
- exact amount,
- income date,
- optional source,
- optional notes.

After save, open paycheck detail with zero entries and the full amount shown as unallocated.

### From template

1. Select template.
2. Enter exact paycheck amount manually.
3. Enter or edit paycheck date.
4. Preview copied entries.
5. Edit, remove, add, or reorder entries.
6. Create paycheck.

Never assume the exact deposit matches the template total.

## Entry editor

Shared:

- name,
- amount,
- notes.

Bill:

- due date,
- account,
- payee.

Spending Bucket:

- budget amount,
- optional initial transactions.

Sinking Fund:

- contribution amount,
- optional target,
- optional target date.

## History

Support:

- search,
- date filtering,
- newest/oldest sorting,
- full detail,
- audit history,
- reopen.

## Templates

Template cards show:

- name,
- entry count,
- default total,
- last edited.

Actions:

- Use
- Edit
- Duplicate
- Archive

## Settings

At minimum:

- API base URL,
- connection status,
- theme preference,
- timezone,
- currency display,
- sign out,
- app version.

## UX quality rules

- Phone-first and one-handed.
- Large touch targets.
- Numeric keyboard for money.
- Preserve form data on failure.
- Never show a false-success state.
- Use cached reads when available, clearly marked stale if offline.
- No full-screen spinner for small mutations.
- Status must not rely on color alone.
- Support screen readers and text scaling.

## Key flows

### Late bookkeeping

The user changes a status today but sets the effective date to several days earlier. Both the effective date and actual recorded timestamp remain visible.

### Correcting a mistake

The user may move Posted back to Processing. A new transition is appended; history is not rewritten.

## Paybacks tab

The Paybacks tab focuses on the total amount still owed to the owner:

- summary card: total left to pay back, total originally tracked, total repaid through Yuuka,
- active Paybacks first,
- paid-off Paybacks in a separate history section,
- each card shows remaining, original amount, tracked-from amount, repaid amount, start date,
  repayment count, state, and accessible progress.

Create/edit fields:

- name,
- original amount owed,
- amount currently left,
- borrowed or start date,
- source or reason,
- notes.

Helper text must explain the difference between original amount owed and amount currently left.
Paycheck entry editing exposes an "Apply to Payback" selector. Repayment applies only when the
linked entry reaches Posted status.

### Fully allocated but incomplete

A paycheck with zero unallocated money and one Processing entry remains in Active.

### Closing and reopening

A completed paycheck may be closed into History and later reopened, with both actions audited.
