# Mobile production dependency-audit status

Status: **open; actual remediation in progress**. This document records evidence only. It adds no
audit exemption or risk acceptance, and `mobile/scripts/audit-production.mjs` is unchanged.

The reachability evidence and table below describe the 2026-10-21 baseline after the compatible
`shell-quote` 1.12.0 and `brace-expansion` 1.1.21 fixes. The 2026-10-22 scoped remediation described
below removes `sprintf-js` rather than suppressing its advisory. The production audit still fails
on the remaining `braces` and `node-forge` roots; issue [#155](https://github.com/EternalLiquet/yuuka/issues/155)
remains open.

## Why build and test tooling counts as "production" here

`audit:production` runs `npm audit --omit=dev`. `expo-router` (a production dependency) declares an
optional peer dependency on `@testing-library/react-native` (`>= 13.2.0`), and `@expo/cli` declares
an optional peer on `expo-router`. Because those packages are installed, npm keeps `jest` and its
subtree in the `--omit=dev` tree. Metro (the bundler) and `@expo/cli` are regular dependencies of
`expo`. None of this means the code runs in the app (see the bundle check below).

## Evidence methods

1. **Shipped code.** `npx expo export --platform android --source-maps` and the source list of the
   Hermes bundle map. The bundle has 3,774 source modules. None come from `braces`, `micromatch`,
   `node-forge`, `sprintf-js`, `argparse` or `js-yaml`.
2. **Code loaded at build and test time.** A Node `require` resolution hook (`NODE_OPTIONS=--require`)
   logged every load of these packages during:
   - `npx jest --coverage` (two suites);
   - `npx expo export --platform android`.

   The `expo start` dev server and EAS builds were not traced.
3. **Static reading** of the requiring code, with paths from `npm explain <package>`.

## Findings

| | `braces` 3.0.3 | `node-forge` 1.4.0 | `sprintf-js` 1.0.3 |
|---|---|---|---|
| Advisory | [GHSA-vfj7-8cjw-p6xm](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm) (high), affects `<=3.0.3` | [GHSA-86w9-cpqp-85rv](https://github.com/advisories/GHSA-86w9-cpqp-85rv) (high), affects `<=1.4.0` | [GHSA-hp3w-g68c-fv3c](https://github.com/advisories/GHSA-hp3w-g68c-fv3c) (moderate, counted because high packages depend on it), affects `<=1.1.3` |
| Affected behavior | Stack-exhaustion denial of service when expanding deeply nested brace *patterns* | RSA PKCS#1 v1.5 signature verification accepts extra nested DigestAlgorithm elements (signature-validation weakness) | Denial of service through unbounded precision specifiers in *format strings* |
| Latest published | 3.0.3 (no fix) | 1.4.0 (no fix) | 1.1.3 (no fix) |
| Dependency paths | `@expo/metro` → `metro-file-map` → `micromatch@4.0.8` (`braces ^3.0.3`); `jest` → `@jest/core` / `jest-haste-map` → `micromatch` | `expo` → `@expo/cli` → `node-forge`, and `@expo/cli` → `@expo/code-signing-certificates@0.0.6` → `node-forge` | `jest` → `@jest/transform` → `babel-plugin-istanbul` → `@istanbuljs/load-nyc-config` → `js-yaml@3.15.2` (existing repo override) → `argparse@1.0.10` (`~1.0.2`) → `sprintf-js` |
| Ships in the app bundle? | **No** | **No** | **No** |
| Loaded at build/test time? | **Yes.** Metro's and Jest's file watchers (`watchers/common.js`) load `micromatch` → `braces` | **No** (not loaded during `expo export` or Jest) | **No.** The project's copy is never loaded. `js-yaml` 3 requires `argparse` only from its CLI binary `bin/js-yaml.js`, never from the library that Istanbul uses. |
| Who controls the vulnerable input | Glob patterns come from Metro and Jest configuration in the repository, not from the matched file paths or file contents | `@expo/cli` uses it only for Expo Updates code signing (`utils/codesigning`) and local iOS signing (`run/ios/codeSigning`). Yuuka has no `expo-updates` dependency and no `codeSigning` configuration in `app.json`/`eas.json`, and targets Android. | Not applicable: the vulnerable code is never loaded |
| Classification | **Real vulnerability, no fix.** The vulnerable code runs during local builds and tests, but only on trusted, repository-controlled patterns. Exploiting it needs the ability to change repo configuration, and the impact is a local build/test process crash. | **Real vulnerability, no fix, not reached** in Yuuka's current workflows. It becomes relevant if Expo Updates code signing or iOS local signing is adopted. | **Not loaded in the traced workflows.** The baseline version is listed by the still-active GHSA, but its linked CVE was rejected by the CNA on 2026-10-12. This source disagreement does not authorize suppressing the audit; the installed path is removed instead (see below). |
| Compatible removal or replacement | None found. Metro is the required bundler for Expo SDK 57. `micromatch@4.0.8` (latest) still requires `braces ^3.0.3`. `jest-expo@57` depends on Jest 29 packages, so a Jest major change is not compatible. | None found. It is a direct dependency of `@expo/cli` (required by `expo`). `@expo/code-signing-certificates@0.0.7` (latest) still requires `node-forge ^1.4.0`. | Removed on 2026-10-22 by the tested parent-scoped `js-yaml` 4.3.2 override described below. The earlier claim that this loader uses removed APIs was incorrect: version 1.1.0 calls only `load()`. This still crosses its declared major range, so compatibility is verified rather than inferred from the API name. |

Not counted by the audit script, but noted for completeness: `decode-uri-component` 0.2.2
([GHSA-vcc3-ghjq-m6fr](https://github.com/advisories/GHSA-vcc3-ghjq-m6fr), moderate) **does ship** in
the app bundle through `expo-router` → `query-string@7` (`^0.2.2`). The fix (0.5.0) is outside the
declared range, so there is no compatible update.

## Scoped remediation on 2026-10-22

The existing override under `@istanbuljs/load-nyc-config` now selects `js-yaml` 4.3.2 instead of
3.15.2. It is not a global YAML override, a Jest upgrade, or a change to which dependencies the
audit considers production. `js-yaml` 4 uses `argparse` 2, which does not depend on `sprintf-js`.
A clean install removes every `sprintf-js` lockfile entry and the actual installed module. The
remaining YAML consumers retain their compatible versions, with npm deduplicating shared copies.
The prior Expo, `shell-quote`, `brace-expansion`, `image-size`, `@xmldom/xmldom`, and `uuid` fixes
are retained.

The compatibility assessment is deliberately narrower than claiming that js-yaml 3 and 4 are
interchangeable for every caller:

- [The actual loader source](https://github.com/istanbuljs/load-nyc-config/blob/v1.1.0/index.js)
  calls only `load()`, which is retained in version 4. It does not use the removed `safeLoad()` or
  `safeDump()` APIs.
- [The v4 migration notes](https://github.com/nodeca/js-yaml/blob/4.3.2/docs/migrate_v3_to_v4.md)
  document changes to JavaScript-specific YAML tags and leading-zero number parsing. This
  repository has no NYC YAML configuration or dependency on those legacy semantics; Jest's
  coverage selection and thresholds remain unchanged in `mobile/package.json`. Future NYC YAML
  must use v4 semantics, including quoting numeric-looking strings when their spelling matters.
- `npm run test:dependency-compatibility` exercises the actual installed loader, including YAML
  and JSON discovery, anchors/merges, sequences/scalars, name/array normalization, both directions
  of YAML/JSON inheritance, relative `cwd`, precedence, and malformed/missing/circular inputs.
  It also verifies the lockfile and installed parser no longer provide the `sprintf-js` chain.
  These tests run in the full mobile verification gate before the unchanged production audit.

The canonical [CVE-2026-97058 record](https://github.com/CVEProject/cvelistV5/blob/main/cves/2026/97xxx/CVE-2026-97058.json)
was marked rejected on 2026-10-12, while the
[GitHub advisory record](https://github.com/github/advisory-database/blob/main/advisories/github-reviewed/2026/09/GHSA-hp3w-g68c-fv3c/GHSA-hp3w-g68c-fv3c.json)
still flags `sprintf-js`. This corrects the earlier unqualified classification, not the audit
policy. Removing the actual dependency chain resolves that finding without relying on the source
disagreement or adding an exemption.

The raw production-audit root set is still authoritative. Removing the `sprintf-js` chain is
partial remediation, not closure of #155 or proof that the production audit passes.

## Remaining upstream blockers

- `braces` 3.0.3 remains through `micromatch` 4.0.8 in stock Metro file-map 0.84.4/0.84.5 and five
  Jest 29 consumers. Upstream [Metro removal](https://github.com/react/metro/pull/2000) and its
  [dotfile/multipart-extension correction](https://github.com/react/metro/pull/2024), plus
  [Jest removal](https://github.com/jestjs/jest/pull/15922) and its
  [option-sensitive cache correction](https://github.com/jestjs/jest/pull/16381), are not a
  drop-in compatible published update for this tree. Adapting them would require a separately
  reviewed, maintained multi-package backport. Omitting installed optional Jest peers or
  rewriting their production classification does not remove this vulnerable code.
- `node-forge` 1.4.0 remains through Expo CLI 57.0.32 and its code-signing-certificates 0.0.6
  dependency. The [canonical DigestInfo validation proposal](https://github.com/digitalbazaar/forge/pull/1159)
  is not a published patched release. Its tests and a source backport are not sufficient to
  claim the unchanged installed version passes the advisory-based audit. The consumers need
  Forge's certificate, key, CSR, and signing APIs, so deleting or stubbing the dependency is not
  a compatible replacement.

The next narrow change is a verified compatible upstream release that removes or fixes these
remaining paths. A maintained backport or broader toolchain migration requires a separate
compatibility plan. No advisory ignores, renewed exceptions, severity changes, peer omissions,
or fabricated patched version numbers are part of this remediation.
