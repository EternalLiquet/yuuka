# MCP read tools (issue #147)

## Status

Five read-only budgeting tools exist behind a disabled-by-default feature flag. **Nothing here enables or
deploys them.** The authentication architecture is approved (ADR, issue #146) and the production
wiring exists (see "Authentication and discovery"), but the feature stays disabled until the
separately gated provider and connectivity prerequisites (P1/P2) are done. With
`yuuka.mcp.enabled=true` and incomplete or invalid configuration, application startup fails in
every profile.

## Configuration

| Property | Default | Meaning |
| --- | --- | --- |
| `yuuka.mcp.enabled` (`YUUKA_MCP_ENABLED`) | `false` | Master switch. |
| `yuuka.mcp.resource-uri` | none | Canonical resource URI and token audience. Required when enabled; `https`, path exactly `/mcp`. |
| `yuuka.mcp.issuer` (`YUUKA_MCP_ISSUER`) | none | Expected `iss`. Required when enabled; `https`. |
| `yuuka.mcp.jwk-set-uri` (`YUUKA_MCP_JWK_SET_URI`) | none | Provider JWKS. Required when enabled; `https`. |
| `yuuka.mcp.authorization-servers` | `[issuer]` | Advertised in the metadata document. |
| `yuuka.mcp.clock-skew` (`YUUKA_MCP_CLOCK_SKEW`) | `PT60S` | Explicit `exp`/`nbf` tolerance, 0 to 5 minutes. |
| `yuuka.mcp.owner-mappings[]` | empty | Exact `(issuer, subject) -> ownerId`. Required when enabled; each mapping issuer must equal `issuer`. |
| `yuuka.mcp.allowed-origins` | empty | Allowed `Origin` values; any other present `Origin` gets 403. |

## Security model

* A second filter chain (`@Order(1)`, matcher `/mcp/**`; see "Authentication and discovery" for the
  `@Order(0)` metadata chain) is independent of the owner-JWT chain.
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

## Authentication and discovery

Production wiring for ADR section 4.3 (issue #147, step P3). All values below are configuration;
no credentials, clients or grants are created by Yuuka.

* **Decoder.** `JwksMcpTokenDecoder` is registered only when `yuuka.mcp.enabled=true` and both
  `issuer` and `jwk-set-uri` are set. It uses `NimbusJwtDecoder.withJwkSetUri` restricted to
  `RS256`, `ES256` and `PS256`; `HS256` (including the Yuuka owner secret) and `alg=none` are
  rejected. Validators: issuer equals `yuuka.mcp.issuer`; `aud` contains `resource-uri`; `exp`
  required; `exp`/`nbf` checked with `JwtTimestampValidator` using the explicit `clock-skew` and the
  application `Clock`. JWT `typ` may be absent, `JWT` or `at+jwt` (RFC 9068). The JWKS HTTP client
  has 3 s connect and 5 s read timeouts. The decoder is not a Spring `JwtDecoder` bean.
* **JWK cache.** Spring Security 6.5's default is kept: Nimbus' in-memory cache with a **5 minute
  TTL**, no refresh-ahead, plus an immediate re-fetch when a token names an unknown `kid`. The TTL is
  not configurable (the supported `.cache(Cache)` hook replaces it with a Spring cache that has no
  TTL, which would be worse) and no cache is hand-rolled. The JWKS is fetched lazily on the first
  token, so startup has no provider dependency. A provider outage rejects tokens (401) once the
  cache is empty or a new `kid` appears.
* **Rotation is not revocation.** Observed (see `JwksMcpTokenDecoderTests`): K2 is accepted
  immediately; if K1 is removed from the JWKS, K1 tokens are still accepted while the cached set
  lives (up to 5 minutes, or until any unknown-`kid` token forces a refresh) and are rejected
  afterwards. TTL expiry itself is wall-clock and is not asserted in tests (no sleeping).
* **Metadata (RFC 9728).** `GET /.well-known/oauth-protected-resource/mcp` is served by a separate
  `@Order(0)` chain registered only when enabled: exact path and method, `permitAll`, no resource
  server (a garbage `Authorization` header is ignored), stateless, CSRF left at the Spring default
  (safe methods are not checked). The Origin filter is intentionally not applied there: the
  document is static and public, and Origin validation protects `/mcp`. Body: `resource`,
  `authorization_servers`, `scopes_supported: ["yuuka:budget:read"]`,
  `bearer_methods_supported: ["header"]`, with `Cache-Control: no-store`. No owner data. The root
  `/.well-known/oauth-protected-resource` is not served. Disabled: no chain, no handler; the path
  falls to the REST chain (401). Yuuka serves no authorization-server metadata; clients fetch it
  from `authorization_servers[0]`.
* **Challenge.** 401 on `/mcp`: `WWW-Authenticate: Bearer resource_metadata="<scheme>://<authority>/.well-known/oauth-protected-resource/mcp", scope="yuuka:budget:read"`,
  with `error="invalid_token"` added when a token was presented but is invalid. The 403
  `insufficient_scope` challenge is unchanged.
* **Owner mappings from the environment.** Spring only discovers list elements from environment
  variables when the list name has no underscore, so use
  `YUUKA_MCP_OWNERMAPPINGS_0_ISSUER`, `YUUKA_MCP_OWNERMAPPINGS_0_SUBJECT` and
  `YUUKA_MCP_OWNERMAPPINGS_0_OWNERID` (and `_1_...` for more). `YUUKA_MCP_OWNER_MAPPINGS_0_*` does
  **not** bind. `docker-compose.yml` passes through index 0 only; extra mappings need a Compose
  override. Verified in `McpEnvironmentBindingTests`.
* **Startup validation (enabled only).** `https` `resource-uri` with path `/mcp`; `https` issuer,
  JWKS URI and authorization servers (plain `http` is accepted only for loopback hosts, solely so
  tests can use a loopback stub JWKS; never use it in production); non-empty owner mappings, each
  with subject, owner id and issuer equal to `yuuka.mcp.issuer`; skew within 0 to 5 minutes; an
  approved decoder bean.
* **Why CSRF is disabled on `/mcp`.** Only a bearer token in the `Authorization` header
  authenticates; the chain is stateless, query/form tokens are not read, CORS is off and
  disallowed Origins are rejected first. `McpBrowserCredentialTests` proves each point.

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
  Its params must be an object with a string `protocolVersion`; invalid params return HTTP 200
  with JSON-RPC `-32602` and the original request ID. Unsupported string versions still negotiate
  the latest supported legacy version.
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
`failure|null`, `bankFeed: false`, `data|null`. `consistency` is always `MULTI_READ`: each tool
performs several reads, so no cross-read snapshot or reconciliation is claimed. No global revision
or bank-sync timestamp exists. Source timestamps and versions are preserved per entity.

## Precision rules

Money and optimistic-lock versions are exact decimal strings (`^-?[0-9]{1,19}$`) so values above
2^53 survive JavaScript hosts; nullable values stay `null` (unknown, not zero). Page totals
(`totalItems`) are strings too. Counts are plain JSON integers. Percentages keep the REST
representation (JSON number). Search `amountMinor` is an input string parsed with checked 64-bit
parsing (overflow is `INVALID_ARGUMENTS`). REST contracts are unchanged. No tool returns notes or
owner email.

Paycheck summaries include `estimatedEntryCount`, the number of live entries whose amounts are
estimates included in the authoritative totals. The totals are not recalculated or adjusted.
Search candidates include `amountEstimated` alongside each exact amount, including when estimated
and confirmed entries have equal values. These markers are required by the MCP output schemas;
the existing REST search representation remains unchanged.

## Tools

| Tool | Input | Notes |
| --- | --- | --- |
| `yuuka_get_budget_context` | `{}` | Currency, time zone, owner-local date, backend version, compact dashboard, `bankFeed:false`, `writesEnabled:false`. |
| `yuuka_list_paychecks` | `view` ACTIVE/HISTORY, `page`>=0, `size` 1..100 (default 25, never clamped), `from`/`through` (HISTORY only) | Summaries only, no entries. |
| `yuuka_get_paycheck` | `paycheckId` | Live entries in custom order; missing and cross-owner ids give identical `NOT_FOUND`. |
| `yuuka_get_recurring_coverage` | `from`, `through` (inclusive, <=366 days) | Active definitions only; `linkStatus` LINKED/UNLINKED. |
| `yuuka_search_entries` | `query` (1..200, trimmed) and/or `amountMinor`, `scope` ALL/ACTIVE/HISTORY, `page`, `size` | Candidates only, `matchKind: "CANDIDATE"`. |

All inputs have `additionalProperties: false`, outputs have strict schemas, and annotations are
`readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false`
(truthful metadata, never used for access control). Tools are alphabetical by name. New tools are
added by registering another `McpTool` bean.

## Known limitations

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
