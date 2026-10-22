# Mobile production-audit advisories without published fixes

Status: **open; risk acceptance pending owner decision**. This document records evidence only. It
adds no audit exemption, and `mobile/scripts/audit-production.mjs` is unchanged.

Analysed on 2026-10-21 against `mobile/package-lock.json` after the in-range fixes in this branch
(`shell-quote` 1.12.0, `brace-expansion` 1.1.21). `npm run audit:production` still fails on the three
root advisories below. For each one, the latest published release of the vulnerable package is
still inside the affected range, so no patched version exists.

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
| Classification | **Real vulnerability, no fix.** The vulnerable code runs during local builds and tests, but only on trusted, repository-controlled patterns. Exploiting it needs the ability to change repo configuration, and the impact is a local build/test process crash. | **Real vulnerability, no fix, not reached** in Yuuka's current workflows. It becomes relevant if Expo Updates code signing or iOS local signing is adopted. | **Not reachable in this tree** (strongest of the three). This is not a false positive of the advisory itself: the installed version is affected. No Yuuka workflow loads it, unless someone runs the `js-yaml` CLI by hand. |
| Compatible removal or replacement | None found. Metro is the required bundler for Expo SDK 57. `micromatch@4.0.8` (latest) still requires `braces ^3.0.3`. `jest-expo@57` depends on Jest 29 packages, so a Jest major change is not compatible. | None found. It is a direct dependency of `@expo/cli` (required by `expo`). `@expo/code-signing-certificates@0.0.7` (latest) still requires `node-forge ^1.4.0`. | None compatible. `js-yaml` 4 drops `argparse` 1, but `@istanbuljs/load-nyc-config` requires `js-yaml ^3` and uses APIs that version 4 removed. Overriding it would be an incompatible forced upgrade. The repository already pins `js-yaml` to 3.15.2 through an override. |

Not counted by the audit script, but noted for completeness: `decode-uri-component` 0.2.2
([GHSA-vcc3-ghjq-m6fr](https://github.com/advisories/GHSA-vcc3-ghjq-m6fr), moderate) **does ship** in
the app bundle through `expo-router` → `query-string@7` (`^0.2.2`). The fix (0.5.0) is outside the
declared range, so there is no compatible update.

## Decisions left to the owner

- Whether to wait for upstream fixes.
- Whether to record time-boxed, path-checked acceptances for any of the three advisories. This
  would follow the pattern of the existing (now expired) `image-size` and `nanoid` entries, with a
  written justification.
- Whether to plan a separate toolchain upgrade.

Until the owner decides, `audit:production` keeps failing.
