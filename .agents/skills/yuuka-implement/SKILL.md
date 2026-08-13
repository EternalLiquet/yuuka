---
name: yuuka-implement
description: Implement an approved Yuuka plan on a dedicated branch and iterate on verification or review findings. Use only after planning is approved and repository files may be changed, tested, committed, and prepared for a pull request.
---

# Implement an Approved Yuuka Plan

1. Read every applicable `AGENTS.md`; it remains authoritative over this skill.
2. Require the approved plan, requested behavior, acceptance criteria, test plan, and known risks. Stop if approval or a material requirement is missing.
3. Confirm the repository still matches the plan before editing. If reality materially conflicts with it, report the conflict instead of silently redesigning the change.
4. Follow the mandatory branch workflow in `AGENTS.md`, then implement the smallest complete change with meaningful tests. Never weaken a test, assertion, validation rule, or domain invariant to get green output.
5. Iterate in this order:
   - run the narrowest relevant check;
   - diagnose and correct failures;
   - rerun the failed focused check;
   - run the applicable component or `./scripts/verify-fast.sh` gate;
   - hand the goal, plan, criteria, base branch, and diff to an independent Verifier;
   - require the Verifier to run every applicable deterministic full gate before reporting PASS;
   - correct every verifier failure and request verification again;
   - after PASS, request an independent Reviewer;
   - correct review findings, then rerun verification before review.
6. Do not request review or mark the change ready until the Verifier has run `./scripts/verify-full.sh`, or the applicable full component gates, successfully. A required unavailable check remains FAIL with explicit environmental evidence.
7. When a required local gate is blocked only by unavailable infrastructure, a draft PR may be opened to obtain GitHub CI evidence. Keep it draft, feed CI evidence back to the Verifier, and do not mark it ready until verification passes and review is clean.
8. Review the final diff, scope, generated files, and sensitive-file check before committing, pushing, and opening or updating the PR. Never merge unless the user explicitly requests it.

Stop rather than loop indefinitely when the same material failure survives two reasonable correction attempts, the failure is environmental or external, requirements conflict, permissions or tools are missing, or continuing would require unrelated redesign or weaker tests. Report the command, evidence, attempts, and safe next action.

Implementation is not complete until the requested behavior and acceptance criteria are satisfied, applicable checks pass, independent verification passes, independent review is clean, and unresolved checks are disclosed.
