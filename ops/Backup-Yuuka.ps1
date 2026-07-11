param(
  [string]$OutputPath = (Join-Path 'backups' ("yuuka-{0}.dump" -f (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')))
)

$ErrorActionPreference = 'Stop'
$temporary = '/tmp/yuuka-backup.dump'
$parent = Split-Path -Parent $OutputPath
if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }

try {
  docker compose exec -T postgres sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" pg_dump --format=custom --no-owner --no-acl --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --file=/tmp/yuuka-backup.dump'
  if ($LASTEXITCODE -ne 0) { throw 'pg_dump failed.' }
  docker compose cp "postgres:$temporary" $OutputPath
  if ($LASTEXITCODE -ne 0) { throw 'Copying the backup failed.' }
}
finally {
  docker compose exec -T postgres rm -f $temporary | Out-Null
}

Write-Host "Backup written to $OutputPath"
