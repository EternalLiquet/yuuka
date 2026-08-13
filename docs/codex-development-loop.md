# Codex Development Loop

Yuuka uses repository-scoped Codex skills and project subagents to separate planning,
implementation, verification, and review. `AGENTS.md` remains the authoritative policy; role files
add only role-specific procedure.

## Roles

| Role | Codex entry point | May edit implementation files? | Stops when |
| --- | --- | --- | --- |
| Planner | `$yuuka-plan` or `yuuka_planner` | No | An implementation-ready plan is returned, or a material decision is blocked |
| Implementer | Main writer thread with `$yuuka-implement` | Yes | The change is ready for independent verification, or bounded failure rules require a report |
| Verifier | `$yuuka-verify` or `yuuka_verifier` | No; commands may create ignored verification output | Acceptance criteria and applicable gates produce evidence-backed PASS or FAIL |
| Reviewer | `$yuuka-review` or `yuuka_reviewer` | No | Consequential findings or an explicit clean result are returned |

The main thread remains the sole implementation writer. Planner and Reviewer custom agents default
to a read-only sandbox. Verifier uses workspace-write because Gradle, Jest, Expo, and Docker create
ignored build artifacts, but it must prove that tracked and non-ignored content did not change.

Custom-agent sandbox defaults are defense in depth, not the policy source. A live client permission
override may supersede them, and project `.codex` configuration loads only for trusted repositories.
If a Codex client does not expose project custom agents, use the matching skill in a fresh thread;
the role separation and handoff format remain the same.

## Normal flow

1. Invoke `$yuuka-plan` or ask Codex to use `yuuka_planner`.
2. Review and approve the plan. Approval stays in the task/thread; do not commit a temporary plan.
3. Use `$yuuka-implement` in the main writer thread.
4. Run focused tests while implementing, then the applicable fast verification components.
5. Give a fresh `yuuka_verifier` thread the original goal, approved plan, acceptance criteria, base
   branch, and diff. The Verifier reruns focused checks and every applicable deterministic full gate
   before it can report PASS.
6. On FAIL, return the findings to the Implementer, correct them, rerun focused checks, and request
   verification again.
7. On PASS, give a fresh `yuuka_reviewer` thread the same task context, verifier evidence, and diff.
8. On review findings, return them to the Implementer and repeat verification before review.
9. When verification passes and review is clean, review the final diff and sensitive-file check,
   commit, push, and open a PR into `master`.
10. Let GitHub CI run. Correct CI failures with the same Implementer-to-Verifier loop. A human merges
    the PR; Codex does not merge unless explicitly instructed.

If a required gate is blocked only because local infrastructure is unavailable, the Verifier must
still report FAIL with that evidence. The Implementer may open a draft PR solely to obtain CI
evidence, then return the CI result to a fresh Verifier. The PR remains draft until verification
passes and independent review is clean.

## Handoffs

Keep handoffs transient in agent output, task context, the PR body, or GitHub comments. Do not add
`PLAN.md`, `VERIFICATION.md`, or review-result files for ordinary work.

Planner to Implementer:

- requested and confirmed current behavior;
- relevant components and constraints;
- bounded implementation steps;
- test plan and acceptance criteria;
- risks and assumptions.

Verifier to Implementer:

- failed acceptance criteria and evidence;
- exact failing commands and relevant output;
- likely affected behavior;
- required correction and reruns;
- anything that could not be verified.

Reviewer to Implementer:

- severity and affected file or behavior;
- evidence and user/domain impact;
- expected correction;
- residual unverified risk.

## Verification interfaces

Install mobile dependencies once after checkout or lockfile changes:

```sh
cd mobile
npm ci
```

From the repository root, use:

```sh
./scripts/verify-fast.sh
./scripts/verify-full.sh
```

Fast verification runs backend `check`, the mobile format/lint/type/coverage gates, Codex workflow
validation, and quiet Compose validation. It is the implementation-iteration baseline, although
backend PostgreSQL Testcontainers still require Docker.

Full verification adds PIT, backend packaging, Expo Doctor, Android export, the deterministic audit
policy test, the live production dependency audit, production-preflight tests, and the hardened
backend image build.

Run one component when only that layer is relevant:

```sh
./scripts/verify-backend.sh fast
./scripts/verify-mobile.sh fast
./scripts/verify-infrastructure.sh fast
```

Replace `fast` with `full` for that component's pre-PR gates. See `docs/testing.md` for prerequisites,
underlying checks, reports, and environment-dependent exclusions.

## Bounded repair behavior

Do not repeat an unchanged failure indefinitely. Stop after two reasonable correction attempts for
the same material verification or review finding, or stop sooner when:

- the failure is environmental or external;
- requirements conflict;
- repository architecture contradicts the plan;
- permissions or required tools are unavailable;
- a fix would weaken a test, assertion, validation rule, or domain invariant;
- a fix requires unrelated redesign.

Report the failure, attempts, current evidence, and why further automatic work would be unsafe or
unproductive. Maestro retains the separate two-attempt rule and diagnostic requirements in
`docs/android-e2e-maestro.md`.

## Automation boundaries

| Area | Responsibility |
| --- | --- |
| Role selection and handoffs during an active task | Agent-driven; the user approves the plan |
| Focused, fast, and full local checks | Agent or developer runs the repository scripts |
| Normal PR checks | GitHub CI automatically calls the same backend, mobile, and infrastructure component scripts |
| CodeQL and Dependency Review | GitHub-only PR workflows |
| Android Maestro | Nightly or manually dispatched GitHub workflow |
| Physical device, private Tailscale, deployment, and restore drills | Manual/private-environment checks |
| Merge | Human action unless explicitly delegated |

Skills and custom-agent files do not create a persistent autonomous daemon. During an active task,
Codex can inspect available CI results and continue the correction loop. A failure that arrives
after the task ends still requires a new Codex request, such as a CI-fix task or `@codex fix the CI
failures` when Codex cloud is configured.

Repository review guidance lives in `AGENTS.md`. If Codex cloud review is enabled in repository
settings, request it with `@codex review` or enable automatic reviews there. Repository files can
guide that review but cannot enable the external setting. No automatic merge is configured.
