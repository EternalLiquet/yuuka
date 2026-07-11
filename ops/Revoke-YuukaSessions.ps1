param([switch]$Force)

$ErrorActionPreference = 'Stop'
if (-not $Force) {
  $confirmation = Read-Host 'Type REVOKE to invalidate every Yuuka refresh session'
  if ($confirmation -cne 'REVOKE') { throw 'Session revocation cancelled.' }
}

docker compose exec -T postgres sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" psql --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --set=ON_ERROR_STOP=1 --command="UPDATE refresh_tokens SET revoked_at = CURRENT_TIMESTAMP WHERE revoked_at IS NULL;"'
if ($LASTEXITCODE -ne 0) { throw 'Session revocation failed.' }
Write-Host 'All refresh sessions are revoked. Existing access tokens expire within the configured access-token TTL.'
