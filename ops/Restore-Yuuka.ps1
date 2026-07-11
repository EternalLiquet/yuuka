param(
  [Parameter(Mandatory = $true)]
  [string]$BackupPath,
  [switch]$Force
)

$ErrorActionPreference = 'Stop'
$resolvedBackup = (Resolve-Path -LiteralPath $BackupPath).Path
if (-not $Force) {
  $confirmation = Read-Host "Type RESTORE to replace the Yuuka database from $resolvedBackup"
  if ($confirmation -cne 'RESTORE') { throw 'Restore cancelled.' }
}

$temporary = '/tmp/yuuka-restore.dump'
docker compose cp $resolvedBackup "postgres:$temporary"
if ($LASTEXITCODE -ne 0) { throw 'Copying the backup into PostgreSQL failed.' }
docker compose exec -T postgres pg_restore --list $temporary | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'The backup could not be read by pg_restore.' }

docker compose stop backend
try {
  docker compose exec -T postgres sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" pg_restore --clean --if-exists --no-owner --no-acl --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" /tmp/yuuka-restore.dump'
  if ($LASTEXITCODE -ne 0) { throw 'Database restore failed.' }
}
finally {
  docker compose exec -T postgres rm -f $temporary | Out-Null
  docker compose start backend | Out-Null
}

Write-Host 'Restore completed. Verify /health/ready before signing in.'
