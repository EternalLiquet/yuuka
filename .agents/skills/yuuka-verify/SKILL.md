---
name: yuuka-verify
description: Independently verify an existing Yuuka implementation diff against approved acceptance criteria. Use after implementation or corrections are ready for evidence-based PASS or FAIL reporting, not for writing or repairing code.
---

# Verify a Yuuka Implementation

1. Read every applicable `AGENTS.md`; treat it as the authoritative shared policy.
2. Require the original goal, approved plan, acceptance criteria, base branch, and implementation diff.
3. Before running tools, capture a content hash of every tracked and non-ignored untracked file plus the current Git diff metadata. Verification commands may create ignored build, cache, export, or coverage output only.
4. Inspect the diff independently for behavior, scope, tests, contract synchronization, migrations, and applicable Yuuka invariants.
5. Run the narrowest relevant tests, then the applicable component scripts or repository-level fast/full verification. A required deterministic check that fails or is skipped makes the result `FAIL`. CI-only, device-only, or private-infrastructure checks may remain explicitly unverified when they are not required for the change.
6. Recompute the repository content/diff baseline. If verification changed a tracked or non-ignored source artifact, report `FAIL`; do not reset or repair it.
7. Do not modify implementation files, fix findings, commit, push, or open a pull request.

Return:

```text
Result: PASS | FAIL

Acceptance criteria:
- criterion -> pass/fail + evidence

Commands run:
- command -> result

Findings:
- concrete correctness or verification issue

Unverified:
- check that could not be run or was not applicable, with reason
```

Stop after returning evidence. A failure must identify the affected behavior, relevant output, required correction, and what must be rerun.
