# Test Strategy and BDD

Development must follow behavior-driven and test-driven development.

## Required workflow

For every feature:

1. Add or refine behavior scenarios.
2. Write failing tests.
3. Implement the smallest correct behavior.
4. Refactor.
5. Run the relevant suite.
6. Run the broader suite before completion.
7. Update documentation if behavior changes.

Tests are not an afterthought or final cleanup phase.

## Backend unit tests

Cover:

- allocation calculations,
- unallocated calculations,
- completion calculations,
- Active-list rules,
- template snapshot independence,
- status transitions,
- backward status transitions,
- bucket calculations,
- over-allocation behavior,
- effective versus recorded dates,
- audit-event creation,
- ownership validation.

## Backend integration tests

Use Testcontainers with real PostgreSQL.

Cover:

- Flyway migrations,
- repositories,
- authentication,
- refresh-token rotation,
- every critical REST workflow,
- transaction rollback,
- optimistic locking,
- owner isolation,
- append-only status history,
- append-only audit history,
- atomic template application.
- dashboard attention eligibility, exact owner-local three-day boundary, deterministic priority and
  cap, owner isolation, exclusions, Active calculations, and financial-position summaries.

Do not use H2 as a substitute for PostgreSQL integration behavior.

## Mobile tests

### Unit tests

Cover:

- money formatting,
- validation schemas,
- filter and sort behavior,
- status mapping,
- API error mapping,
- stale-data handling,
- form logic.

### Component tests

Use React Native Testing Library for:

- Active paycheck card,
- entry row states,
- status bottom sheet,
- editable effective date,
- create paycheck form,
- bucket transaction form,
- Payback list, editor, detail, and repayment assignment behavior,
- Planned Savings list, editor, detail, contribution assignment, withdrawal, and transaction behavior,
- template list, create/edit/detail, entry CRUD, archive/restore/duplicate, and create-from-template
  draft behavior,
- filter/sort controls,
- retry states,
- accessibility labels.
- Home section/navigation behavior, independent query loading/error/stale/retry states,
  pull-to-refresh partial failures, Active previews, 30/90-day bucket switching, recurring preview,
  financial rows, narrow layouts, and font-scaling-safe wrapping.
- exactly five ordered tabs with centered Home, authenticated Home landing, menu reachability,
  Planned Savings compatibility, detail push/Back semantics, and top-level stack replacement.

### End-to-end tests

Use Maestro or an equivalent Android-capable tool.

The current critical Android flows automate:

1. Scratch paycheck creation, Bill and Spending Bucket entry editing, status history, bucket
   purchases, entry reordering, automatic close, History, and reopen.
2. Payback creation, paycheck-entry assignment, Payback deletion cleanup, and reassignment.
3. Template creation, Manual Pay preservation, editable create-from-template draft behavior,
   ordering, and source-template independence.
4. Recurring Bill definition creation and editable import into a scratch paycheck draft.

Planned Savings has mobile component and backend integration coverage but is not currently one of
the four committed Maestro flows.

Full audit-browsing E2E coverage should be added when full audit browsing leaves later-scope mobile
status.

## Contract testing

Generate an OpenAPI contract and either generate mobile client types or validate client/server contract drift in CI.

## Coverage requirements

Coverage is a quality signal, not a substitute for meaningful assertions.

Backend minimums:

- 90% line coverage for domain and application-service packages
- 85% branch coverage for those packages
- 80% line coverage overall

Mobile minimums:

- 85% line coverage for domain, hooks, utilities, and state logic
- 80% branch coverage for those layers
- 75% line coverage overall

Critical business rules require 100% direct test coverage even if overall thresholds are already met.

CI must enforce thresholds.

Generated code, trivial DTO accessors, and framework boilerplate may be excluded only with documentation.

## Mutation testing

Use PIT or equivalent where practical for:

- allocation logic,
- Active-list logic,
- status transitions,
- bucket calculations.

Document mutation score.

## Flake policy

Flaky tests are failures. Do not disable tests merely to make CI green.

## BDD scenarios

### Exact cents

Given a template totals 193920 cents

When a paycheck is created for 193923 cents

Then unallocated is exactly 3 cents.

### Empty paycheck attention

Given a paycheck has zero amount

And it has no entries

When the Active list is loaded

Then the paycheck is still Active because it has no checklist items yet.

When the user attempts to close it

Then the close is rejected until at least one entry exists and all entries are Posted.

### Required money fields

Given the user creates a paycheck or entry

When the amount field is missing

Then the API rejects the request instead of silently treating the amount as zero.

### Template independence

Given a paycheck was created from a template

When a copied entry changes

Then the template does not change.

When the template later changes

Then the existing paycheck does not change.

### Status history

Given Verizon is Not Paid

When it is changed to Processing effective two days ago

Then the current status is Processing

And the effective date is the chosen date

And the recorded timestamp is the actual save time.

When it later changes to Posted

Then both transitions remain.

When it is moved back to Processing

Then a third transition is appended.

### Bucket math

Given Work Food is 5000 cents

When transactions of 1235 and 910 cents are added

Then remaining is 2855 cents.

When a 5100 cent purchase is recorded against a 5000 cent bucket

Then the bucket is shown as `$51.00 spent | $1.00 over`.

When bucket spending is under budget

Then the row distinguishes the red spent amount from the green amount left.

### Active payment progress

Given an Active paycheck has Posted, Processing, and Not Paid amounts

When the Active paycheck card renders the payment progress bar

Then the green segment represents Posted money, the yellow segment represents Processing money, and the unfilled remainder represents Not Paid money.

And the status count row still counts entries, not dollars.

### Owner isolation

Given User A owns a paycheck

And User B requests its ID

Then no data is leaked.

### Failed save

Given the API is unavailable

When the user submits a status change

Then the UI does not falsely show success

And the entered effective date and note remain available for retry.
