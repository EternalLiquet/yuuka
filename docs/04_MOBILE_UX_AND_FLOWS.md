# Mobile UX and User Flows

## Navigation

Persistent bottom tabs:

1. Active
2. Ledgers
3. History

The app-menu drawer provides Active, Ledgers, History, Paybacks, Templates, Recurring Bills, and
Settings. Ledgers are top-level because they are an entry point into expense-first capture rather
than a child of a single paycheck.

A prominent create action is available from Active.

## Authentication flow

- Sign in
- Session expired state
- Sign out
- Public registration not required

After authentication, open Active.

## Active tab

Show paychecks that are not fully allocated, contain at least one entry that is not Posted, or were
deliberately reopened and not explicitly closed again.

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

The secondary actions include Duplicate Paycheck for Active, Closed, Archived, historical, and
reopened paychecks. Duplication opens a reviewed draft instead of immediately cloning persisted
data.

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
- Sinking Fund,
- Autopay bills,
- Manual Pay bills.

Sort:

- custom order,
- amount,
- status,
- due date,
- last edited.

Clearly indicate active filters or non-default sorting.

## Entry search

The global entry search screen supports name search, exact amount search, and an automatic mode
that treats valid money text as an exact amount, reports validation for numeric-looking invalid
money, and treats other text as a name. Search can be scoped to all paychecks, Active only, or
History only. Results open the owning paycheck with the matching entry highlighted.

## Reordering

Support drag-and-drop in custom-order mode and accessible Move Up / Move Down controls.

## Create paycheck flow

The mobile creation screen supports Start from Scratch and Use a Template.

### From scratch

Collect:

- name,
- exact amount,
- income date,
- optional source,
- optional notes.

The draft may import recurring Bill suggestions before save. If it has no entries, save opens
paycheck detail with the full amount unallocated; otherwise the ordered draft entries are created
transactionally with the paycheck.

### From template

1. Select template.
2. Enter exact paycheck amount manually.
3. Enter or edit paycheck date, source, notes, and paycheck name.
4. Preview copied entries in a local application draft.
5. Edit, remove, add, or reorder draft entries.
6. Create paycheck.

Draft entries are copied from the selected template in saved order and can be adjusted before
creation without mutating the source template. Bills preserve Autopay or Manual Pay. Due offsets are
converted to absolute due dates from the entered income date when the paycheck is created. The draft
allocation total is recalculated using integer minor units; under-allocation is valid, exact
allocation is valid, and over-allocation blocks creation with a clear error. The submitted draft
array determines the saved paycheck-entry order. Created paycheck entries are independent snapshots;
later template edits do not affect the paycheck, and later paycheck edits do not affect the
template.

### From duplicate paycheck

1. Open any readable paycheck.
2. Tap Duplicate Paycheck.
3. Review or edit paycheck name, amount, income date, source, and notes.
4. Continue to a local entry draft.
5. Edit, remove, add, or reorder draft entries.
6. Create paycheck and open the new paycheck detail.

The duplicate draft loads the authoritative paycheck detail before initialization. It copies live
entries in saved order, excludes generated `LEFTOVER`, clears Payback assignments with an
informational count, and does not copy statuses, history, bucket purchases, spent values, IDs, or
versions. Bills preserve Autopay or Manual Pay and shift due dates by the source due-date offset
from the source paycheck income date to the new income date. Sinking Fund target dates remain exact.
Failed creation keeps the local draft available for retry, and repeated taps are guarded so one
successful request creates one paycheck.

## Entry editor

Shared:

- name,
- amount,
- notes.

Bill:

- due date,
- account,
- payee,
- Autopay or Manual Pay.

Spending Bucket:

- budget amount,
- optional initial transactions.

Sinking Fund:

- contribution amount,
- optional persistent Sinking Fund assignment,
- optional target,
- optional target date.

When a persistent Sinking Fund is selected, the editor sends the assignment, clears entry-level
target fields, and clears any Payback selection. Selecting a Payback clears the persistent Sinking
Fund selection. Posted contributions and derived balances are read back from the API.

## Expense Ledgers

The Ledgers tab supports Open, Finalized, and Settled states. List rows show the ledger name,
derived total, item count, latest expense date when available, loading, stale, empty, retry, and
pull-to-refresh states. Each state has an isolated paged cache. Load older ledgers appends later
pages with ID deduplication, exposes the loaded and total counts, and keeps every ledger reachable.

The detail screen shows the derived total and items. Open ledgers provide compact repeated item
entry with Save and add another plus Save and close, preserve input after failed saves, and allow
editing or deleting items. Finalized ledgers are read-only and provide Reopen, Settle as Bill, and
Settle as Payback actions. Settled ledgers are read-only and show the created target summary link.

Bill settlement review requires choosing an active paycheck and shows each paycheck's available
unallocated amount. Payback settlement creates the Payback with the derived total and latest expense
date default. Both settlement actions guard repeated taps and refresh the affected target caches.
Settled Bill links open the containing paycheck ID stored by the settlement; Payback links continue
to open the Payback target ID.

## History

Support:

- search,
- date filtering,
- newest/oldest sorting,
- full detail,
- reopen.

Full paycheck audit browsing remains later-scope mobile UI. Status history is available from entry
detail controls.

## Templates

The Templates menu destination exposes active and archived template lists with loading, empty, stale-data,
pull-to-refresh, error, and retry states.

Template cards show:

- name,
- entry count,
- default total,
- last edited.

Active template detail supports:

- edit template name and description,
- add, edit, delete with confirmation, and reorder entries,
- Bill, Spending Bucket, and Sinking Fund entry types,
- Bill Autopay or Manual Pay using "I need to pay this manually",
- duplicate template,
- archive template.

Archived template detail is read-only for entries, labels the template as archived, hides entry
mutation controls, and supports restore and duplicate when permitted by the backend. Archive is a
recoverable lifecycle action, not permanent deletion. Template-entry order persists through saved
reorder requests and stale writes surface conflict errors.

## Recurring Bills

The Recurring Bills menu destination opens a vertically scrollable occurrence timeline with an
always-visible Today divider, month/date grouping, pull-to-refresh, and a Jump to today action.
Definition management supports search, active/inactive filtering, create, edit, deactivate,
reactivate, and delete. Monthly dates shown in the timeline come from the backend's clamped calendar
policy.

Scratch, template, duplicate, and existing-paycheck entry drafts expose Import recurring bills.
Suggested results use the owner setting around the paycheck income date; All shows the relevant
occurrence date near that income date. Nothing is preselected. Selecting uses the typical amount,
and editing that selection offers This paycheck only or Update typical amount. Draft imports remain
editable local snapshots. Existing-paycheck imports submit the full selection as one transactional
request and retain the user's selection if validation or allocation fails.

## Settings

At minimum:

- API base URL,
- connection status,
- theme preference,
- timezone,
- currency display,
- recurring Bill suggestion days (1 through 31, default 7),
- sign out,
- backend release version from `/health/version`.

The Settings footer displays the packaged backend version beside a small static Yuuka because that
is the deployed homelab release source of truth. Normal semantic versions receive one `v` prefix
for display, already prefixed versions are preserved, and `0.0.0-dev` is shown without a forced
prefix. Readiness remains driven by `/health/ready`; version loading and failures do not replace the
connection status. Tapping the footer mascot plays one brief, non-networked heart interaction;
additional taps are ignored until it returns to idle.

## Loading states

Prominent initial loads without usable cached data use the reusable Yuuka mascot loader for at
least one second. The request and minimum timer run concurrently, so a slow request is not followed
by an additional delay. Cached navigation and background refetches keep their content and controls
available and show a compact, non-blocking running mascot for a short readable minimum. Native
pull-to-refresh continues to own the gesture and refresh state while the same compact mascot
communicates progress; mutations and button loading states retain their existing compact behavior.

Selected first-party empty states use contextual static mascot poses while retaining their title,
explanation, and actions. Error states do not use mascot art. The source sheet lives at
`mobile/assets/yuuka/yuuka-sprite-sheet.png`; extracted true-alpha frames are grouped under
`mobile/assets/yuuka/running/`, `idle/`, `wave/`, `clipboard/`, and `heart/`. Prefer suitable poses
from the source sheet. Any future generated supporting art must closely preserve the established
character and chibi style, use true transparency and consistent alignment, and be added only when
the source sheet is insufficient.

Mascot frames are decorative and are not announced individually. Loading and refresh text remains
visible and accessible. Reduced-motion settings keep the loading duration and messages but render
static frames; the Settings interaction briefly shows its final heart pose without cycling frames.
Mascots remain restrained to cold loading, refresh feedback, selected empty states, and the Settings
footer.

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
linked entry reaches Posted status. Selecting a Payback clears any persistent Sinking Fund
assignment so one entry cannot affect both balances.

## Sinking Funds menu destination

The Sinking Funds menu destination focuses on money reserved for future purposes:

- summary card: total active balance, active count, archived count,
- active Sinking Funds first,
- archived Sinking Funds in a separate section,
- each card shows current balance, optional target, optional target date, transaction count, state,
  and accessible progress when a target amount exists.

Create/edit fields:

- name,
- target amount,
- target date,
- opening balance on create,
- notes.

Detail supports edit, archive, restore, withdrawal creation, transaction history, and withdrawal
reversal. The mobile app displays backend-derived balances and progress instead of calculating
authoritative Sinking Fund totals locally.

### Fully allocated but incomplete

A paycheck with zero unallocated money and one Processing entry remains in Active.

### Closing and reopening

An ordinary paycheck automatically closes into History when it is fully allocated and every live
entry is Posted. A completed paycheck may be reopened from History; deliberately reopened paychecks
stay Active until explicitly closed again, with close and reopen actions audited.
