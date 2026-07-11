#!/usr/bin/env sh
set -eu

if [ "${YUUKA_CONFIRM_REVOKE:-}" != "REVOKE" ]; then
  echo "Session revocation refused. Run with YUUKA_CONFIRM_REVOKE=REVOKE." >&2
  exit 3
fi

docker compose exec -T postgres sh -c \
  'PGPASSWORD="$POSTGRES_PASSWORD" psql --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --set=ON_ERROR_STOP=1 --command="UPDATE refresh_tokens SET revoked_at = CURRENT_TIMESTAMP WHERE revoked_at IS NULL;"'

echo "All refresh sessions are revoked. Existing access tokens expire within the configured access-token TTL."
