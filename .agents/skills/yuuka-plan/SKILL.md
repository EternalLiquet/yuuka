---
name: yuuka-plan
description: Plan a Yuuka feature, bug fix, or engineering change without editing files. Use when a request needs repository inspection, acceptance criteria, risks, and an implementation-ready handoff before coding begins.
---

# Plan a Yuuka Change

1. Read every applicable `AGENTS.md`; treat it as the authoritative shared policy.
2. Inspect the current implementation, relevant tests, contracts, CI, and documentation. Prefer repository evidence over the request's assumptions.
3. Identify current behavior, requested behavior, affected components, applicable domain invariants, compatibility concerns, and verification needs.
4. Keep the change bounded. Ask for a decision only when missing information would materially change the implementation.
5. Do not edit files, create commits, push, or open a pull request.

Return this handoff:

```text
Confirmed current behavior
Requested behavior
Relevant files/components
Domain/integrity constraints
Implementation plan
Test plan
Acceptance criteria
Risks/assumptions
```

Stop when the plan is concise and implementation-ready, or when a repository conflict or unresolved requirement needs user input. Do not start implementation.
