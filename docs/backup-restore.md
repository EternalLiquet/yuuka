# Backup and Restore

The PostgreSQL volume is the source of truth. A Docker volume is not itself a backup.

## Backup

Linux/macOS:

```sh
./ops/backup.sh
./ops/backup.sh /secure/off-host/yuuka.dump
```

Windows PowerShell:

```powershell
.\ops\Backup-Yuuka.ps1
.\ops\Backup-Yuuka.ps1 -OutputPath N:\Backups\yuuka.dump
```

The helpers create PostgreSQL custom-format dumps without ownership metadata. Store at least one encrypted copy off the homelab host. Keep backup permissions private because the dump contains all financial records, account email, password hash, and token hashes.

A practical schedule is daily with at least 14 daily and 3 monthly restore points. Use an encrypted tool such as restic or Borg for retention; neither requires a paid service.

## Verify

List a dump before relying on it:

```sh
docker compose cp backups/yuuka-TIMESTAMP.dump postgres:/tmp/verify.dump
docker compose exec -T postgres pg_restore --list /tmp/verify.dump
docker compose exec -T postgres rm -f /tmp/verify.dump
```

Listing checks readability, not a full recovery. Perform a test restore into an isolated Yuuka stack after schema changes and at least quarterly.

## Restore

Restore replaces current database objects. Take a safety backup first and verify the selected path.

Linux/macOS:

```sh
YUUKA_CONFIRM_RESTORE=RESTORE ./ops/restore.sh backups/yuuka-TIMESTAMP.dump
```

Windows PowerShell:

```powershell
.\ops\Restore-Yuuka.ps1 -BackupPath N:\Backups\yuuka.dump
```

The helper validates the archive, stops the backend, runs `pg_restore --clean --if-exists`, removes the temporary in-container file, and starts the backend again. Verify:

```sh
curl --fail http://127.0.0.1:8080/health/ready
docker compose logs --tail=100 backend
```

Sign in and inspect an Active paycheck, History, Paybacks, Templates, and status history before
declaring recovery complete. If template data matters for the restore, verify active and archived
templates through the mobile Templates tab or the authenticated template API.

## Secret recovery

Database dumps do not contain `.env`, the JWT signing secret, or the raw TOTP secret. Back up `.env` separately in an encrypted secret store.

If the authenticator is lost but the homelab remains under your control, generate a new TOTP secret with the owner helper, replace only `YUUKA_OWNER_TOTP_SECRET`, restart the backend, and enroll the new URI. For suspected compromise, run the session-revocation helper and rotate `YUUKA_JWT_SECRET` so both refresh and access credentials are invalidated.
