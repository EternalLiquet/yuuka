#!/usr/bin/env sh
set -eu
umask 077

timestamp=$(date -u +%Y%m%dT%H%M%SZ)
destination=${1:-"backups/yuuka-${timestamp}.dump"}
directory=$(dirname "$destination")
mkdir -p "$directory"

temporary=/tmp/yuuka-backup.dump
docker compose exec -T postgres sh -c \
  'PGPASSWORD="$POSTGRES_PASSWORD" pg_dump --format=custom --no-owner --no-acl --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --file=/tmp/yuuka-backup.dump'
docker compose cp "postgres:${temporary}" "$destination"
docker compose exec -T postgres rm -f "$temporary"

echo "Backup written to $destination"
