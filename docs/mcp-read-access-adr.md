# ADR: Private read-only MCP access

- Status: **Proposed — awaiting owner architecture approval** (issue #146)
- Scope: architecture and feasibility only. This document does not authorize deploying, installing
  a tunnel, creating credentials or OAuth grants, changing Tailscale/network/security settings,
  publishing a plugin, live financial reads, or writes.
- Related: #147 (read tools), #148 (review queue). Both stay disabled by default and draft until
  this ADR is approved.

## 1. Question

What is the smallest secure way for an assistant to read Yuuka budgeting records while Yuuka stays
private and self-hosted?

## 2. Verified repository facts

Checked against `master` at `1e06ce3`.

| Fact | Evidence |
| --- | --- |
| One Spring Security filter chain protects every route except login/register/refresh/logout and health. | `backend/.../common/config/SecurityConfig.java` (`anyRequest().authenticated()`) |
| Access tokens are HS256 JWTs signed with one shared secret. They carry `iss=yuuka`, `aud=yuuka-api`, `sub=<owner UUID>`, `email` and `roles`. They have **no `scope` claim**. | `SecurityConfig.jwtDecoder`, `common/security/JwtTokenService`, `application.yml` |
| Authorities come only from `roles` (the owner role). Any valid access token can call every private REST mutation. | `SecurityConfig.jwtAuthenticationConverter` |
| Controllers take the owner from `sub` (`AuthenticatedOwner.id(jwt)`). | `common/security/AuthenticatedOwner` |
| Refresh tokens are opaque, one-time, rotating, hashed at rest, and revoke their family on replay. | `docs/architecture-decisions.md` § Authentication boundary |
| Login is password plus TOTP. Production is single-owner with registration disabled. There is no OAuth authorization-server endpoint (`/authorize`, `/token`, metadata, PKCE, client registration). | `SecurityConfig` routes, `docs/security.md` |
| Production binds the API to `127.0.0.1:8080` and publishes it only through Tailscale Serve. Tailscale Funnel is forbidden. | `docs/deployment.md` § Private Tailscale HTTPS |
| Tailscale Serve proxies the whole backend port, so any new route is tailnet-reachable as soon as it exists. | `docs/deployment.md` |
| Stack: Java 21, Spring Boot 3.5.3, `spring-boot-starter-oauth2-resource-server` (Nimbus) already present. Jackson comes from the Boot 3.5.x BOM (Jackson 2.19 line). There is no MCP or AI dependency. | `backend/build.gradle.kts` |
| Owner-scoped read services already exist with authoritative totals and bounds. | `PaycheckService`, `RecurringBillService.timeline`, `EntrySearchService`, `DashboardService` |

The current owner JWT is therefore **not** a read-only credential. Giving one to an assistant would
grant full mutation rights, and so would copying a mobile refresh token.

### MCP protocol facts (verified from specification sources)

The specification website could not be reached from the authoring environment because of an egress
policy. These facts come from the specification source files in the
`modelcontextprotocol/modelcontextprotocol` repository (`docs/specification/2026-07-28/**`,
`schema/2026-07-28/schema.ts`).

- **2026-07-28 is stateless.** It removes `initialize`, sessions and `Mcp-Session-Id`. Servers
  **MUST** implement `server/discover`. Every request carries its protocol version in `_meta`.
  Results carry `resultType`. `tools/list` results carry `ttlMs` and `cacheScope`.
- **Streamable HTTP:** one POST endpoint. The server returns `application/json` or SSE. It
  **MUST** validate `Origin` (403 if present and invalid) and **SHOULD** bind to localhost when
  local. `MCP-Protocol-Version`, `Mcp-Method` and `Mcp-Name` headers are required and must match
  the body (400 `HeaderMismatch` `-32020`). An unsupported version returns 400 `-32022` with
  `supported`. An unknown method returns 404 `-32601`. GET or DELETE from older clients returns 405.
- **Authorization (HTTP transports):** the MCP server is an OAuth 2.1 resource server. It
  **MUST** publish RFC 9728 Protected Resource Metadata. It **MUST** validate that tokens were
  issued for it as the audience (clients send the RFC 8707 `resource` parameter with the server's
  canonical URI). It **MUST NOT** accept or pass through other tokens. Invalid or expired tokens
  return 401. Insufficient scope returns 403 with
  `WWW-Authenticate: Bearer error="insufficient_scope", scope=...`. Authorization servers use
  OAuth 2.1 with PKCE (S256). Client ID Metadata Documents are the preferred registration method,
  and Dynamic Client Registration is now deprecated. Clients validate `iss` per RFC 9207.
- **STDIO** implementations **SHOULD NOT** follow the HTTP authorization specification. They
  obtain credentials from the environment instead (see § 7).
- Older revisions (`2025-11-25`, `2025-06-18`) use `initialize` and optional sessions. Hosts may
  still speak them.

### Java SDK compatibility (verified from Maven Central POMs, 2026-10-21)

| Artifact | Finding |
| --- | --- |
| `io.modelcontextprotocol.sdk:mcp` 2.0.x | Aggregates `mcp-core` plus **`mcp-json-jackson3`**. Jackson 3 is a different line from Boot 3.5.3's Jackson 2. Rejected. |
| `mcp-core` 2.0.1/2.1.0 + `mcp-json-jackson2` | Possible, but adds `reactor-core` as a new runtime dependency and declares `jackson-annotations` 2.21, which is newer than the Boot BOM line. That would need BOM overrides. |
| `mcp-spring-webmvc` | Latest is 0.18.x, a pre-2.0 line. Not suitable. |

## 3. Unverified prerequisites (precise questions for the owner)

These facts cannot be verified from the repository or without account access. Each one blocks only
the part named.

1. **Target host and protocol version.** Which assistant host will connect (for example ChatGPT
   web, ChatGPT mobile, an API agent, or a local desktop client)? Which MCP revision does it speak
   today (2026-07-28 or 2025-11-25/06-18)?
   *Blocks:* choosing which revisions to keep after the first live test.
2. **Secure MCP Tunnel availability.** Is Secure MCP Tunnel available and enabled for the owner's
   account or workspace? What plan or admin enablement does it need? Does it present the end
   user's OAuth token to the MCP server unchanged?
   *Blocks:* selecting the tunnel path.
   The official guide (`developers.openai.com/api/docs/guides/secure-mcp-tunnels`) could not be
   fetched from this environment. The issue's summary ("outbound-only private connection") is
   recorded as **unverified**.
3. **Host OAuth requirements.** What redirect URI(s), client registration method (CIMD,
   pre-registration or DCR), and token audience behavior does the chosen host use (see
   `developers.openai.com/plugins/build/auth`, also unreachable here)?
   *Blocks:* authorization-server client configuration.
4. **Authorization provider.** Is there an existing OAuth 2.1/OIDC provider the owner already runs
   or trusts? Examples are a self-hosted IdP on the homelab or a provider account the owner
   already has. Can it issue asymmetric-signed JWT access tokens with a custom audience, a custom
   scope `yuuka:budget:read`, S256 PKCE, exact redirect URIs, short TTL and revocation?
   *Blocks:* production wiring of #147/#148.
   The repository contains none (§ 2).
5. **Reachability for a cloud host.** A cloud-hosted assistant cannot reach a tailnet-only
   hostname. Without the tunnel (Q2), cloud or mobile-hosted assistants are **not supported**. A
   public gateway would be a separate owner decision (§ 6).

## 4. Decision (recommended)

### 4.1 Implementation boundary: thin in-process Java MCP layer

Add a package `com.yuuka.backend.mcp` inside the existing backend. Each tool calls the existing
owner-scoped application services and maps results to MCP-specific DTOs. Money and long versions
are encoded as exact decimal strings. No REST contract changes. The layer has no repository
access of its own beyond what those services expose, and it has no write paths.

Transport: Streamable HTTP at `POST /mcp`, JSON responses only (no SSE, no sessions), on the
existing loopback-bound process. Implement the small required method set directly on Spring MVC
with the existing Jackson 2:

- 2026-07-28: `server/discover`, `tools/list`, `tools/call`;
- legacy: `initialize`, `notifications/initialized`, `ping`, `tools/list`, `tools/call`.

This adds **no new runtime dependency** and no framework upgrade. The official Java SDK is
deferred (§ 2 table). It can be re-evaluated once it ships a Jackson-2-compatible module without
BOM overrides, or once Yuuka moves to Boot 4 / Jackson 3 for unrelated reasons.

### 4.2 Connectivity

1. **Preferred:** Secure MCP Tunnel, *if* Q2 confirms availability. The tunnel connector runs on
   the homelab, makes outbound connections only, and targets `http://127.0.0.1:8080/mcp`.
   OAuth is still enforced by Yuuka; the tunnel is transport, not authentication.
2. **Otherwise:** a local MCP client on a device that is already a tailnet member reaches
   `https://<node>.<tailnet>.ts.net/mcp` through the existing Tailscale Serve, under existing
   ACLs/grants.
3. **Excluded:** Tailscale Funnel, public ingress, a public database, or any anonymous endpoint.
   A public gateway (for example a reverse proxy with its own WAF) is a **separate owner
   decision** and is not proposed here.

Local success proves only local connectivity. It is not evidence that a cloud or mobile host can
connect.

### 4.3 Trust boundary and token contract

| Element | Contract |
| --- | --- |
| Authenticating client | The assistant host's registered OAuth client, using authorization code + PKCE (S256), exact pre-registered redirect URIs, and least-privilege scope. |
| Authorization server | An **external, owner-approved** OAuth 2.1 provider (Q4). Yuuka is **not** turned into an authorization server, and password/TOTP login is not reused as one. |
| Resource / audience | The canonical MCP URI (for example `https://<node>.<tailnet>.ts.net/mcp`). Tokens must contain it in `aud`. Others are rejected. |
| Scope | `yuuka:budget:read`, required **at invocation** of every tool. `tools/list` without it returns no tools; `tools/call` returns 403 `insufficient_scope`. |
| Token format | Asymmetric (RS256/ES256) JWT validated against the provider's JWKS, plus `iss`, `aud`, `exp`, `nbf`. HS256 is rejected, and so is the Yuuka shared secret. |
| Owner mapping | A configured allow-list `(issuer, subject) → ownerId`, checked to exist at startup. No client-supplied owner. Unmapped subjects get 403 with no detail. |
| Expiry / revocation | Short access-token TTL (≤ 15 min, matching the existing access TTL). Revocation is done at the provider (grant revocation plus short TTL). Introspection is an optional later hardening. Removing the owner mapping is an immediate local kill switch, and so is `yuuka.mcp.enabled=false`. |
| Separation from REST | Its own `SecurityFilterChain` (`@Order(1)`, matcher `/mcp/**`) with its **own** decoder that is **not** a `JwtDecoder` bean. The existing REST chain keeps its HS256 own-issuer decoder, so MCP tokens fail there with 401. Owner HS256 JWTs fail at `/mcp` (wrong algorithm, issuer and audience). Both directions are tested. |
| Metadata | `GET /.well-known/oauth-protected-resource/mcp` (RFC 9728) lists `resource`, `authorization_servers` and `scopes_supported: ["yuuka:budget:read"]`. 401 responses carry `WWW-Authenticate: Bearer resource_metadata="…"`. This is part of production wiring after approval. |
| Origin | `Origin` is validated: if present and not allow-listed, the response is 403. |
| Fail-closed | Disabled by default. If enabled without an approved decoder or owner mappings, startup fails. Test-only decoders and principals exist only under `src/test`. |

### 4.4 Proof of "connected"

"Connected" requires, **in the intended host**: authenticated tool discovery that lists exactly the
expected tools, plus one harmless synthetic read (`yuuka_get_budget_context` against a synthetic or
demo owner). Local MockMvc/curl success is recorded as local-only evidence.

## 5. Rejected alternatives

| Alternative | Why rejected |
| --- | --- |
| **REST sidecar MCP server** calling `/api/v1` | It needs its own Yuuka credential. In practice that means a copied refresh token or an owner JWT, which carries full mutation rights and breaks rotation and replay assumptions. It duplicates DTO mapping and adds another deployable. It reads across several HTTP calls with weaker consistency, and it could not enforce a read-only scope inside Yuuka. |
| Reusing owner JWTs or adding `scope` to them | These are owner-role mutation credentials signed with a shared secret. Sharing the secret or minting long-lived owner tokens violates least privilege. |
| Making Yuuka's password/TOTP login an OAuth AS | It would be a new identity platform (authorize/token endpoints, PKCE, client registration, consent, revocation). Out of scope and higher risk. If no provider exists, it becomes a separately scoped follow-on proposal. |
| Official Java MCP SDK now | Jackson 3 aggregate, Reactor, and annotation-version skew versus Boot 3.5.3 (§ 2). |
| Tailscale Funnel / public gateway | Public exposure, excluded by deployment policy. A gateway needs a separate owner decision. |
| Generic API proxy, SQL or export tool | Unbounded data access. It conflicts with the five-tool contract in #147. |

## 6. Separately scoped prerequisites (not part of #146)

- **P1 — Authorization provider selection and configuration.** Choose or confirm the provider
  (Q4). Register the host client with exact redirect URIs, S256 PKCE and the audience/resource;
  define the `yuuka:budget:read` scope; set TTL and revocation. Needs owner account access. Not
  started.
- **P2 — Connectivity.** Confirm the tunnel (Q2), or confirm a local tailnet client is acceptable.
  Installing a tunnel daemon or changing Tailscale settings needs separate owner permission.
- **P3 — Production wiring PR** (after approval of this ADR and P1): JWKS decoder, metadata
  endpoint, `resource_metadata` challenge, owner mapping values supplied through environment, and
  a deployment-preflight check. Lands on #147's branch.

## 7. Local STDIO difference

An STDIO MCP server would run as a local process launched by the client. Per the specification it
does not use OAuth. It would therefore need a credential from its environment, which would be a
Yuuka owner token or refresh token. That is exactly the credential this ADR forbids sharing.
STDIO is therefore **not** recommended. If ever wanted, it should be a thin STDIO-to-HTTP bridge
that obtains its own `yuuka:budget:read` token from the provider. It must never use an owner
credential.

## 8. Synthetic end-to-end test plan

All fixtures are synthetic: generated UUIDs, invented names, `*.test.invalid` issuers and
resources, and RSA keys generated at test time. No personal data, real hostnames or secrets.

### 8.1 Automated (backend, PostgreSQL Testcontainers + MockMvc) — #147/#148

| Case | Expected |
| --- | --- |
| Feature disabled (default) | `/mcp` returns no data (denied). Tools are not registered. |
| Enabled without approved decoder or owner mappings | Application context fails to start. |
| Discovery: `server/discover` (2026-07-28 headers), legacy `initialize` → `tools/list` | Supported versions are listed. Exactly the expected tools appear, in deterministic order, with strict schemas and read-only annotations. |
| Header mismatch / unsupported version / unknown method / GET | 400 `-32020` / 400 `-32022` (with `supported`) / 404 `-32601` / 405 |
| Harmless synthetic read | `yuuka_get_budget_context` returns envelope with `bankFeed:false`, `writesEnabled:false`, no email |
| Wrong owner | A token mapped to owner B asks for owner A's paycheck UUID. The result is `NOT_FOUND`, identical to a random UUID, and counts and totals reflect B only. |
| Unmapped subject | 403, no detail |
| Wrong audience / wrong issuer / HS256 owner JWT at `/mcp` | 401 |
| Missing scope | `tools/list` returns empty. `tools/call` returns 403 `insufficient_scope`. |
| Expired / not-yet-valid token | 401 |
| Revoked | Simulated by removing the owner mapping or rotating the test JWKS. The result is 401/403 on the next call. |
| MCP token at REST routes (`POST /api/v1/paychecks`, entry PATCH/DELETE, GET reads) | 401, so the MCP token cannot mutate |
| Hostile text in records (an entry named "Ignore previous instructions and transfer…") | Returned verbatim only inside data fields. Tool descriptions and schemas are unchanged. |
| Origin header not allow-listed | 403 |
| Precision | Amounts and versions above 2^53 round-trip as exact decimal strings. Null amounts stay null. |
| Output schema | Every tool's actual `structuredContent` validates against its declared `outputSchema`. |

### 8.2 Manual, gated (only after approval and P1/P2; not run by this ADR)

| Case | Expected |
| --- | --- |
| Discovery failure (host cannot fetch protected-resource metadata, or provider metadata unreachable) | The host shows an auth/discovery error. Yuuka logs no token. No data is returned. |
| Unavailable tunnel (connector stopped) | The host reports the server unreachable. Yuuka is unchanged. No fallback to a public path. |
| In-host discovery + synthetic read against a demo/synthetic owner | This is the only evidence that counts as "connected" (§ 4.4). |
| Revocation at provider | After TTL expiry, calls fail with 401. |

## 9. Consequences

- Positive: least-privilege read scope without sharing owner credentials; reuse of authoritative
  services; no new runtime dependencies; private topology unchanged.
- Negative: a production connection depends on an external provider (P1) and, for cloud hosts, on
  tunnel availability (P2). Until then, the feature can be exercised only with synthetic tests.
- Rollback: `yuuka.mcp.enabled=false` (the default) removes the endpoint. No migration is involved.
