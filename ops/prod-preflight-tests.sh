#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SCRIPT="$ROOT_DIR/ops/prod-preflight.sh"
COMPOSE_FILE="$ROOT_DIR/docker-compose.yml"
TMP_DIR=$(mktemp -d)

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT INT TERM

write_valid_env() {
  path=$1
  cat > "$path" <<'ENV'
SPRING_PROFILES_ACTIVE=prod
POSTGRES_PASSWORD=ci_database_password_32_chars
SPRING_DATASOURCE_PASSWORD=ci_database_password_32_chars
YUUKA_JWT_SECRET=ci-jwt-secret-that-is-at-least-32-bytes
YUUKA_OWNER_EMAIL=owner@example.test
YUUKA_OWNER_PASSWORD_HASH=$2a$12$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyzaBCDE
YUUKA_OWNER_TOTP_SECRET=JBSWY3DPEHPK3PXP
YUUKA_OWNER_BOOTSTRAP_PASSWORD=
YUUKA_BIND_ADDRESS=127.0.0.1
YUUKA_CORS_ALLOWED_ORIGINS=https://yuuka.example.test
ENV
}

run_preflight() {
  env -i PATH="$PATH" "$SCRIPT" --env-file "$1" --compose-file "$COMPOSE_FILE"
}

expect_pass() {
  name=$1
  env_file="$TMP_DIR/$name.env"
  write_valid_env "$env_file"
  if ! output=$(run_preflight "$env_file" 2>&1); then
    printf 'FAIL %s: expected pass\n%s\n' "$name" "$output" >&2
    exit 1
  fi
  printf 'ok %s\n' "$name"
}

expect_fail() {
  name=$1
  override=$2
  expected=$3
  env_file="$TMP_DIR/$name.env"
  write_valid_env "$env_file"
  printf '%s\n' "$override" >> "$env_file"
  if output=$(run_preflight "$env_file" 2>&1); then
    printf 'FAIL %s: expected failure\n%s\n' "$name" "$output" >&2
    exit 1
  fi
  if ! printf '%s' "$output" | grep -F "$expected" >/dev/null; then
    printf 'FAIL %s: expected message containing "%s"\n%s\n' "$name" "$expected" "$output" >&2
    exit 1
  fi
  printf 'ok %s\n' "$name"
}

expect_pass valid_environment
expect_fail missing_prod_profile 'SPRING_PROFILES_ACTIVE=demo' 'SPRING_PROFILES_ACTIVE must include prod'
expect_fail default_database_password 'POSTGRES_PASSWORD=yuuka_dev_password' 'POSTGRES_PASSWORD must not use the development default'
expect_fail default_jwt_secret 'YUUKA_JWT_SECRET=change-me-to-a-32-byte-minimum-secret' 'YUUKA_JWT_SECRET must not use the development default'
expect_fail missing_owner_email 'YUUKA_OWNER_EMAIL=' 'Owner email is required'
expect_fail missing_owner_hash 'YUUKA_OWNER_PASSWORD_HASH=' 'Owner password hash is required'
expect_fail missing_owner_totp 'YUUKA_OWNER_TOTP_SECRET=' 'Owner TOTP secret is required'
expect_fail plaintext_bootstrap_password 'YUUKA_OWNER_BOOTSTRAP_PASSWORD=temporary-password' 'YUUKA_OWNER_BOOTSTRAP_PASSWORD must be blank in production'
expect_fail http_cors_origin 'YUUKA_CORS_ALLOWED_ORIGINS=http://yuuka.example.test' 'YUUKA_CORS_ALLOWED_ORIGINS must contain HTTPS origins only'
expect_fail localhost_cors_origin 'YUUKA_CORS_ALLOWED_ORIGINS=https://localhost:8081' 'YUUKA_CORS_ALLOWED_ORIGINS must not contain localhost origins'
expect_fail public_bind_address 'YUUKA_BIND_ADDRESS=0.0.0.0' 'YUUKA_BIND_ADDRESS must be 127.0.0.1'

printf 'Yuuka production preflight validation tests passed.\n'
