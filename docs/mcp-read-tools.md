# MCP read tools (issue #147)

## Status

Six read-only budgeting tools exist behind a disabled-by-default feature flag. **They cannot run in
production.** Authentication for MCP access (issue #146) is not approved, so main code ships no
token decoder: with `yuuka.mcp.enabled=true` and no approved `McpTokenDecoder` bean, application
startup fails ("MCP read access requires the approved authentication configuration (#146)") in
every profile. Production issuer/JWKS wiring, OAuth client configuration and the
protected-resource metadata endpoint (including the `resource_metadata` challenge parameter) are
deliberately absent and belong to the post-approval wiring step.

## Configuration

| Property | Default | Meaning |
| --- | --- | --- |
| `yuuka.mcp.enabled` (`YUUKA_MCP_ENABLED`) | `false` | Master switch. |
| `yuuka.mcp.resource-uri` | none | Audience that tokens must carry. Required when enabled. |
| `yuuka.mcp.owner-mappings[]` | empty | Exact `(issuer, subject) -> ownerId`. Required when enabled. |
| `yuuka.mcp.allowed-origins` | empty | Allowed `Origin` values; any other present `Origin` gets 403. |

## Security model

* A second filter chain (`@Order(1)`, matcher `/mcp/**`) is independent of the owner-JWT chain.
  Disabled, or enabled without a `McpTokenDecoder` bean: `denyAll` (401 with `WWW-Authenticate:
  Bearer`). The controller and tools are not registered.
* `McpTokenDecoder` is intentionally not a Spring `JwtDecoder` bean. The chain wraps it in a
  non-bean adapter that also re-checks `aud` contains `resource-uri`. Authorities come from the
  `scope`/`scp` claim (`SCOPE_` prefix).
* Owner comes only from the configured mapping of the token's `iss` + `sub`, and the account must
  exist and be enabled. Unmapped or unknown subjects get HTTP 403, JSON-RPC code `-32000`
  `Forbidden`, no detail. The owner is never a tool argument; unknown arguments are rejected.
* `yuuka:budget:read` is enforced at invocation. `tools/list` returns an empty list without it;
  `tools/call` returns HTTP 403 with `WWW-Authenticate: Bearer error="insufficient_scope",
  scope="yuuka:budget:read"` and JSON-RPC `-32000` `Forbidden`, echoing the request id.
* Owner HS256 tokens are rejected at `/mcp`; MCP tokens are rejected (401) by every REST route.
* The `/mcp` endpoint is `@Hidden` from OpenAPI; `docs/openapi.json` is unchanged.

## Transport

Streamable HTTP, JSON responses only, no SSE, no sessions (`Mcp-Session-Id` is never minted).
`POST /mcp` only; GET/DELETE/PUT/PATCH return 405. Notifications return 202 with no body.
`McpProtocol.SUPPORTED_PROTOCOL_VERSIONS = [2026-07-28, 2025-11-25, 2025-06-18]`.

* **2026-07-28**: methods `server/discover`, `tools/list`, `tools/call`. Requires
  `MCP-Protocol-Version` equal to `params._meta["io.modelcontextprotocol/protocolVersion"]` and
  `Mcp-Method` equal to the method; `Mcp-Name` (base64 sentinel decoded) is required only for
  `tools/call` and must equal `params.name`. Missing/mismatched: 400 `-32020`. Results carry
  `resultType: "complete"`; `server/discover` and `tools/list` add `ttlMs: 0`, `cacheScope:
  "private"`; all carry `_meta` serverInfo.
* **2025-11-25 / 2025-06-18**: `initialize` needs no header (version negotiated in the body).
  Every later request and notification (`ping`, `tools/list`, `tools/call`,
  `notifications/initialized`) must send a supported `MCP-Protocol-Version`; absent gives 400
  `-32022` with `data.supported` (2025-03-26 is not supported). `Mcp-Method`/`Mcp-Name` are not
  validated.
* Unsupported version: 400 `-32022` with `data: {supported, requested}`. Unknown method: 404
  `-32601`. Unknown tool: `-32602`. Tool-level failures are `isError: true` with a populated
  `failure` and `data: null`.

## Envelope (all tools)

`schemaVersion "1"`, `tool`, `readStartedAt`, `readCompletedAt`, `asOfDate`, `timeZone`,
`currency`, `consistency`, `coverage{scope, limitations[]}`, `filters`, `page|null`, `truncated`,
`failure|null`, `bankFeed: false`, `data|null`. `consistency` is `MULTI_READ` for every tool except
`yuuka_get_review_queue`: those tools perform several reads, so no cross-read snapshot or
reconciliation is claimed. The review queue reports `SNAPSHOT` because its whole response is computed
inside one read-only REPEATABLE READ transaction (see "Review queue"). No global revision
or bank-sync timestamp exists. Source timestamps and versions are preserved per entity.

## Precision rules

Money and optimistic-lock versions are exact decimal strings (`^-?[0-9]{1,19}$`) so values above
2^53 survive JavaScript hosts; nullable values stay `null` (unknown, not zero). Page totals
(`totalItems`) are strings too. Counts are plain JSON integers. Percentages keep the REST
representation (JSON number). Search `amountMinor` is an input string parsed with checked 64-bit
parsing (overflow is `INVALID_ARGUMENTS`). REST contracts are unchanged. No tool returns notes or
owner email.

## Tools

| Tool | Input | Notes |
| --- | --- | --- |
| `yuuka_get_budget_context` | `{}` | Currency, time zone, owner-local date, backend version, compact dashboard, `bankFeed:false`, `writesEnabled:false`. |
| `yuuka_list_paychecks` | `view` ACTIVE/HISTORY, `page`>=0, `size` 1..100 (default 25, never clamped), `from`/`through` (HISTORY only) | Summaries only, no entries. |
| `yuuka_get_paycheck` | `paycheckId` | Live entries in custom order; missing and cross-owner ids give identical `NOT_FOUND`. |
| `yuuka_get_recurring_coverage` | `from`, `through` (inclusive, <=366 days) | Active definitions only; `linkStatus` LINKED/UNLINKED. |
| `yuuka_get_review_queue` | `from`, `through` (inclusive, <=93 days), `page`>=0, `size` 1..100 (default 25, never clamped) | Derived bookkeeping questions with evidence; `consistency: SNAPSHOT`. See "Review queue". |
| `yuuka_search_entries` | `query` (1..200, trimmed) and/or `amountMinor`, `scope` ALL/ACTIVE/HISTORY, `page`, `size` | Candidates only, `matchKind: "CANDIDATE"`. |

All inputs have `additionalProperties: false`, outputs have strict schemas, and annotations are
`readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false`
(truthful metadata, never used for access control). Tools are alphabetical by name. New tools are
added by registering another `McpTool` bean.

## Review queue

`yuuka_get_review_queue({from, through, page, size})` is a thin MCP mapping of
`BudgetReviewService.review(ownerId, from, through, page, size)` (package `review`, not conditional
on MCP). It turns existing records into a short list of bookkeeping **questions**. It never imports,
links, marks Posted, deletes, merges, or creates a recurring definition; it writes no review state,
audit event, or status event and needs no migration. It does not reconcile against a bank.

**Scopes.** Recurring checks cover occurrences dated `from..through` inclusive (at most 93 days;
`through >= from`). Paycheck checks cover **all** Active paychecks (same membership as
`PaycheckService.active`) regardless of the window, and every response says so
(`data.paycheckScope = ALL_ACTIVE_PAYCHECKS`, `scope` on every finding, and a coverage limitation).

| `checkCode` | Rule | Severity | Scope |
| --- | --- | --- | --- |
| `RECURRING_OCCURRENCE_UNLINKED` | Occurrence of an *active* definition (month-end clamping from `MonthlyOccurrencePolicy`) with no live linked entry in a live paycheck. Lists up to 10 `possibleMatches` (live BILL entries whose due date or paycheck income date is within 7 days; same name ignoring case, amount equal to the occurrence or typical amount, or same definition as provenance) and `candidateSearch{criteria, limit, returned, complete}`. | MEDIUM | window |
| `RECURRING_OCCURRENCE_MULTIPLE_LINKS` | More than one live linked entry. All are listed with id, paycheck, status, amount, version. May be intentional. | LOW | window |
| `VARIABLE_AMOUNT_NEEDS_REVIEW` | (a) VARIABLE occurrence whose amount was never entered (`amountBasis UNKNOWN`, amount `null`), reported even if a linked entry carries its own amount; (b) a live entry with `amountEstimated` in an Active paycheck, or linked to a window occurrence (`ESTIMATED`). | MEDIUM | window / all Active |
| `MANUAL_BILL_NOT_PAID_DUE` | Live `BILL`, `MANUAL`, `NOT_PAID`, **dated** `dueDate <= owner-local today`, in an Active paycheck. Past due is HIGH, due today is MEDIUM. | HIGH / MEDIUM | all Active |
| `PROCESSING_AGED` | Latest status event is a move to `PROCESSING` and its owner-local *effective* date is on or before today minus 3 days (`ProcessingAgePolicy`, shared with Home). Evidence carries `effectiveAt` (user-selected) and `recordedAt` (system) separately. | MEDIUM | all Active |
| `PAYCHECK_UNALLOCATED` | Active paycheck with `unallocated > 0` from `PaycheckCalculator`. Statuses never affect it: a fully allocated paycheck with unpaid entries is not flagged here. | LOW | all Active |
| `BUCKET_OVER_BUDGET` | Live `SPENDING_BUCKET` in an Active paycheck, `remaining < 0` from `BucketCalculator`, status not `POSTED`. | MEDIUM | all Active |

**Reuse and deliberate differences from Home attention.** Manual-bill, Processing-age and
over-budget logic follow `DashboardService`. Home also lists undated and future-dated manual Bills
(lowest rank); the review queue lists only dated manual Bills due today or earlier, because its
purpose is "due". The posted-bucket exclusion is copied from Home. Home caps results at 5; the
review queue never caps silently.

**Provenance.** Entries store no snapshot of their recurring definition, so evidence shows the
definition *as it is now*: `definitionState` ACTIVE/INACTIVE/DELETED, current version and name,
and `definitionUpdatedAfterEntryCreated` (approximate: definition `updated_at` after entry
`created_at`). Inactive and deleted definitions generate no occurrences, and no historical
occurrences are inferred.

**Ordering and paging.** Findings sort by severity (HIGH, MEDIUM, LOW), then primary date ascending
(occurrence date, due date, processing-since date, or income date; nulls last), then `checkCode`
declaration order, then `paycheckId`, `entryId`, `definitionId` compared as UUID strings. `page.totalItems`
and `data.summary` (counts per `checkCode`) describe the whole snapshot, not the page. A page past the
end is empty. `truncated` is always `false`; an incomplete possible-match search is reported on the
finding (`candidateSearch.complete=false`) and as a coverage limitation.

**Consistency.** The service method is `@Transactional(readOnly = true, isolation =
REPEATABLE_READ)` and computes the owner context, every query, the full sorted list, the total and
the page slice inside it; the tool reads nothing outside it, so the envelope reports `SNAPSHOT`.
Each page is its own transaction: separate pages may reflect different data, and a limitation says
so.

**Failures.** Invalid arguments are `INVALID_ARGUMENTS`. If any Active paycheck's amounts cannot be
summed without 64-bit overflow the whole call fails with `MONEY_AMOUNT_OVERFLOW` instead of
returning wrapped values.

## Known limitations

* No bank feed: findings are not verified against bank activity, and unsaved, unsynchronized or
  unentered activity may exist.

* The dashboard caps needs-attention at 5 items; `needsAttentionMayBeTruncated` and `truncated`
  are set when 5 are returned, and the limitation is listed in `coverage.limitations`.
* `MULTI_READ`: lists and pages are read separately and may reflect different moments.
* Unlinked recurring coverage means no paycheck entry is linked, not that a bill is unpaid. A null
  amount is unknown. There is no bank feed.
* Existing inconsistency (not fixed here): the Active list SQL (`findActivePage`) treats
  `amount_estimated` entries as needing attention, while `PaycheckVisibilityPolicy` (used by entry
  search `paycheckContext`) does not. `yuuka_list_paychecks` ACTIVE follows `PaycheckService.active`
  (authoritative); search may label an estimated-only paycheck `HISTORY`.
* `yuuka.mcp.allowed-origins` and the `resource-uri` startup requirement were added to the frozen
  configuration record (additive).

## Tests

`backend/src/test/java/com/yuuka/backend/mcp`: unit tests (values, envelope, arguments, startup
guard via `ApplicationContextRunner`), PostgreSQL integration tests (disabled default, auth,
protocol, tools, parity with REST/services, owner isolation, precision, schema validation with
`com.networknt:json-schema-validator:1.5.8` as a test-only Jackson 2 dependency). The synthetic RS256
decoder and tokens live only under `src/test` with reserved `.invalid` hosts.
