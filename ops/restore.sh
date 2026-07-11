#!/usr/bin/env sh
set -eu

if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
  echo "Usage: YUUKA_CONFIRM_RESTORE=RESTORE $0 backups/yuuka-TIMESTAMP.dump" >&2
  exit 2
fi
if [ "${YUUKA_CONFIRM_RESTORE:-}" != "RESTORE" ]; then
  echo "Restore refused. Set YUUKA_CONFIRM_RESTORE=RESTORE after verifying the backup path." >&2
  exit 3
fi

source_path=$1
temporary=/tmp/yuuka-restore.dump

docker compose cp "$source_path" "postgres:${temporary}"
docker compose exec -T postgres pg_restore --list "$temporary" >/dev/null
docker compose stop backend

cleanup() {
  docker compose exec -T postgres rm -f "$temporary" >/dev/null 2>&1 || true
  docker compose start backend >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

docker compose exec -T postgres sh -c \
  'PGPASSWORD="$POSTGRES_PASSWORD" pg_restore --clean --if-exists --no-owner --no-acl --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" /tmp/yuuka-restore.dump'

echo "Restore completed. Waiting for the backend health check."
