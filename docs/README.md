# Yuuka Documentation

The numbered files are the authoritative Yuuka product and engineering specification.

Current implemented domains include authentication, Paychecks, entries, Templates, Recurring Bills,
Spending Bucket transactions/performance, Paybacks, global entry search, audit history, and Expense
Ledgers. The mobile current state includes the Home financial dashboard and the five-tab navigation
for Active, Paybacks, Home, Planned Savings, and History.

Operational guides:

- `architecture-decisions.md` - implemented system boundaries and invariants.
- `security.md` - security controls and residual risks.
- `owner-onboarding.md` - owner credentials, authenticator enrollment, and recovery.
- `deployment.md` - Docker homelab, Tailscale, and Android installation.
- `backup-restore.md` - protected backups and destructive restore procedure.
- `testing.md` - local and CI quality gates.
- `android-e2e-maestro.md` - Android E2E and Maestro workflow runbook.
- `openapi.json` - committed backend API contract snapshot.
