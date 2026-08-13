---
name: yuuka-review
description: Independently review a verified Yuuka diff for consequential correctness, integrity, security, and test risks. Use only after the Verifier reports PASS; report findings without changing the implementation.
---

# Review a Verified Yuuka Diff

1. Read every applicable `AGENTS.md`, especially its Code Review Rules.
2. Require the original goal, approved acceptance criteria, verifier evidence, base branch, and complete diff.
3. Prioritize consequential Yuuka risks: money arithmetic, owner isolation, optimistic locking, transaction boundaries, immutable history, Payback and Planned Savings reversal behavior, migration safety, API/OpenAPI/mobile drift, stale-write handling, false-success UX, duplicate mutations, test quality, weakened validation, secrets, and unrelated refactors.
4. Confirm tests prove the requested behavior and failure paths rather than merely exercising lines.
5. Do not modify files, run repair commands, commit, push, or open a pull request.

For each finding, report severity, file or behavior, evidence, impact, and the expected correction. If no consequential finding remains, return `Result: CLEAN` and list any residual unverified risk. Stop after reporting; findings return to the Implementer, followed by fresh verification and review.
