#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SCRIPT="$ROOT_DIR/ops/prod-preflight.sh"
CANONICAL_COMPOSE="$ROOT_DIR/docker-compose.yml"
TMP_DIR=$(mktemp -d)

DB_SECRET=ci_database_password_32_chars
JWT_SECRET=ci-jwt-secret-that-is-at-least-32-bytes
BCRYPT_HASH='$2a$12$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyzaBCDE'
TOTP_SECRET=JBSWY3DPEHPK3PXP
BOOTSTRAP_SECRET=temporary-bootstrap-secret

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT INT TERM

write_valid_env() {
  path=$1
  cat > "$path" <<ENV
SPRING_PROFILES_ACTIVE=prod
POSTGRES_PASSWORD=$DB_SECRET
SPRING_DATASOURCE_PASSWORD=$DB_SECRET
YUUKA_JWT_SECRET=$JWT_SECRET
YUUKA_OWNER_EMAIL=owner@example.test
YUUKA_OWNER_PASSWORD_HASH='$BCRYPT_HASH'
YUUKA_OWNER_TOTP_SECRET=$TOTP_SECRET
YUUKA_OWNER_BOOTSTRAP_PASSWORD=
YUUKA_AUTH_REGISTRATION_ENABLED=false
YUUKA_BIND_ADDRESS=127.0.0.1
YUUKA_CORS_ALLOWED_ORIGINS=https://yuuka.example.test
ENV
  chmod 600 "$path"
}

append_env() {
  path=$1
  line=$2
  printf '%s\n' "$line" >> "$path"
}

write_minimal_compose() {
  path=$1
  ports_block=$2
  network_mode_block=${3:-}
  registration_value=${4:-'${YUUKA_AUTH_REGISTRATION_ENABLED}'}
  cat > "$path" <<COMPOSE
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_PASSWORD: \${POSTGRES_PASSWORD}
  backend:
    image: busybox:latest
$network_mode_block
    environment:
      SPRING_DATASOURCE_PASSWORD: \${POSTGRES_PASSWORD}
      SPRING_PROFILES_ACTIVE: \${SPRING_PROFILES_ACTIVE}
      YUUKA_AUTH_REGISTRATION_ENABLED: "$registration_value"
      YUUKA_OWNER_EMAIL: \${YUUKA_OWNER_EMAIL}
      YUUKA_OWNER_PASSWORD_HASH: \${YUUKA_OWNER_PASSWORD_HASH}
      YUUKA_OWNER_TOTP_SECRET: \${YUUKA_OWNER_TOTP_SECRET}
      YUUKA_OWNER_BOOTSTRAP_PASSWORD: \${YUUKA_OWNER_BOOTSTRAP_PASSWORD}
      YUUKA_JWT_SECRET: \${YUUKA_JWT_SECRET}
      YUUKA_CORS_ALLOWED_ORIGINS: \${YUUKA_CORS_ALLOWED_ORIGINS}
$ports_block
COMPOSE
}

write_valid_compose() {
  path=$1
  write_minimal_compose "$path" '    ports:
      - target: 8080
        published: "8080"
        host_ip: 127.0.0.1
        protocol: tcp'
}

write_alternate_safe_compose() {
  path=$1
  write_minimal_compose "$path" '    ports:
      - "127.0.0.1:8080:8080/tcp"'
}

run_preflight() {
  env_file=$1
  compose_file=$2
  shift 2
  env -i PATH="$PATH" "$@" "$SCRIPT" --env-file "$env_file" --compose-file "$compose_file"
}

assert_no_secrets() {
  output=$1
  for secret in "$DB_SECRET" "$JWT_SECRET" "$BCRYPT_HASH" "$TOTP_SECRET" "$BOOTSTRAP_SECRET"; do
    if printf '%s' "$output" | grep -F "$secret" >/dev/null; then
      printf 'FAIL: output leaked dummy secret value\n' >&2
      exit 1
    fi
  done
}

expect_pass() {
  name=$1
  env_file=$2
  compose_file=$3
  shift 3
  if ! output=$(run_preflight "$env_file" "$compose_file" "$@" 2>&1); then
    assert_no_secrets "$output"
    printf 'FAIL %s: expected pass\n%s\n' "$name" "$output" >&2
    exit 1
  fi
  assert_no_secrets "$output"
  printf 'ok %s\n' "$name"
}

expect_fail() {
  name=$1
  env_file=$2
  compose_file=$3
  expected=$4
  shift 4
  if output=$(run_preflight "$env_file" "$compose_file" "$@" 2>&1); then
    printf 'FAIL %s: expected failure\n%s\n' "$name" "$output" >&2
    exit 1
  fi
  assert_no_secrets "$output"
  if ! printf '%s' "$output" | grep -F "$expected" >/dev/null; then
    printf 'FAIL %s: expected message containing "%s"\n%s\n' "$name" "$expected" "$output" >&2
    exit 1
  fi
  printf 'ok %s\n' "$name"
}

valid_env="$TMP_DIR/valid.env"
write_valid_env "$valid_env"

valid_compose="$TMP_DIR/valid-compose.yml"
write_valid_compose "$valid_compose"

alternate_compose="$TMP_DIR/alternate-compose.yml"
write_alternate_safe_compose "$alternate_compose"

expect_pass valid_secure_environment_and_canonical_compose "$valid_env" "$CANONICAL_COMPOSE"
expect_pass alternate_safe_compose_formatting "$valid_env" "$alternate_compose"

insecure_env="$TMP_DIR/insecure.env"
write_valid_env "$insecure_env"
chmod 644 "$insecure_env"
expect_fail insecure_env_file_mode "$insecure_env" "$CANONICAL_COMPOSE" "Environment file must not be readable or writable by group or others"

registration_env="$TMP_DIR/registration.env"
write_valid_env "$registration_env"
append_env "$registration_env" "YUUKA_AUTH_REGISTRATION_ENABLED=true"
expect_fail registration_true_env "$registration_env" "$CANONICAL_COMPOSE" "YUUKA_AUTH_REGISTRATION_ENABLED must be explicitly false"

missing_registration_env="$TMP_DIR/missing-registration.env"
write_valid_env "$missing_registration_env"
grep -v '^YUUKA_AUTH_REGISTRATION_ENABLED=' "$valid_env" > "$missing_registration_env"
chmod 600 "$missing_registration_env"
expect_fail registration_missing_env "$missing_registration_env" "$CANONICAL_COMPOSE" "YUUKA_AUTH_REGISTRATION_ENABLED must be explicitly false"

registration_compose="$TMP_DIR/registration-compose.yml"
write_minimal_compose "$registration_compose" '    ports:
      - target: 8080
        published: "8080"
        host_ip: 127.0.0.1
        protocol: tcp' "" "true"
expect_fail registration_true_resolved_service "$valid_env" "$registration_compose" "YUUKA_AUTH_REGISTRATION_ENABLED must be explicitly false"

malformed_compose="$TMP_DIR/malformed-compose.yml"
printf 'services:\n  backend:\n    ports:\n      - [\n' > "$malformed_compose"
expect_fail malformed_compose "$valid_env" "$malformed_compose" "Docker Compose configuration is invalid"

public_bind_compose="$TMP_DIR/public-bind-compose.yml"
write_minimal_compose "$public_bind_compose" '    ports:
      - target: 8080
        published: "8080"
        host_ip: 0.0.0.0
        protocol: tcp'
expect_fail public_backend_binding "$valid_env" "$public_bind_compose" "backend port 8080 must bind only to host_ip 127.0.0.1"

missing_host_ip_compose="$TMP_DIR/missing-host-ip-compose.yml"
write_minimal_compose "$missing_host_ip_compose" '    ports:
      - target: 8080
        published: "8080"
        protocol: tcp'
expect_fail missing_host_ip "$valid_env" "$missing_host_ip_compose" "backend port 8080 must declare host_ip 127.0.0.1"

comment_only_compose="$TMP_DIR/comment-only-compose.yml"
write_minimal_compose "$comment_only_compose" '    # 127.0.0.1:8080:8080
    expose:
      - "8080"'
expect_fail safe_binding_text_only_in_comment "$valid_env" "$comment_only_compose" "backend service must publish container port 8080 on host_ip 127.0.0.1"

second_public_compose="$TMP_DIR/second-public-compose.yml"
write_minimal_compose "$second_public_compose" '    ports:
      - target: 8080
        published: "8080"
        host_ip: 127.0.0.1
        protocol: tcp
      - target: 8080
        published: "18080"
        host_ip: 0.0.0.0
        protocol: tcp'
expect_fail safe_plus_second_public_binding "$valid_env" "$second_public_compose" "backend port 8080 must bind only to host_ip 127.0.0.1"

host_network_compose="$TMP_DIR/host-network-compose.yml"
write_minimal_compose "$host_network_compose" '    expose:
      - "8080"' "    network_mode: host"
expect_fail host_networking "$valid_env" "$host_network_compose" "backend service must not use host networking"

weak_lengths_env="$TMP_DIR/weak-lengths.env"
write_valid_env "$weak_lengths_env"
append_env "$weak_lengths_env" "POSTGRES_PASSWORD=short"
append_env "$weak_lengths_env" "YUUKA_JWT_SECRET=short"
expect_fail weak_database_and_jwt_lengths "$weak_lengths_env" "$CANONICAL_COMPOSE" "POSTGRES_PASSWORD must be at least 16 characters"

mismatch_env="$TMP_DIR/mismatch.env"
write_valid_env "$mismatch_env"
append_env "$mismatch_env" "SPRING_DATASOURCE_PASSWORD=different_database_password"
mismatch_compose="$TMP_DIR/mismatch-compose.yml"
cat > "$mismatch_compose" <<'COMPOSE'
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
  backend:
    image: busybox:latest
    environment:
      SPRING_DATASOURCE_PASSWORD: ${SPRING_DATASOURCE_PASSWORD}
      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE}
      YUUKA_AUTH_REGISTRATION_ENABLED: ${YUUKA_AUTH_REGISTRATION_ENABLED}
      YUUKA_OWNER_EMAIL: ${YUUKA_OWNER_EMAIL}
      YUUKA_OWNER_PASSWORD_HASH: ${YUUKA_OWNER_PASSWORD_HASH}
      YUUKA_OWNER_TOTP_SECRET: ${YUUKA_OWNER_TOTP_SECRET}
      YUUKA_OWNER_BOOTSTRAP_PASSWORD: ${YUUKA_OWNER_BOOTSTRAP_PASSWORD}
      YUUKA_JWT_SECRET: ${YUUKA_JWT_SECRET}
      YUUKA_CORS_ALLOWED_ORIGINS: ${YUUKA_CORS_ALLOWED_ORIGINS}
    ports:
      - "127.0.0.1:8080:8080"
COMPOSE
expect_fail database_password_mismatch "$mismatch_env" "$mismatch_compose" "SPRING_DATASOURCE_PASSWORD must match POSTGRES_PASSWORD for the Compose deployment"

wildcard_cors_env="$TMP_DIR/wildcard-cors.env"
write_valid_env "$wildcard_cors_env"
append_env "$wildcard_cors_env" "YUUKA_CORS_ALLOWED_ORIGINS=*"
expect_fail wildcard_cors "$wildcard_cors_env" "$CANONICAL_COMPOSE" "YUUKA_CORS_ALLOWED_ORIGINS must not allow wildcard origins"

http_cors_env="$TMP_DIR/http-cors.env"
write_valid_env "$http_cors_env"
append_env "$http_cors_env" "YUUKA_CORS_ALLOWED_ORIGINS=http://yuuka.example.test"
expect_fail http_cors "$http_cors_env" "$CANONICAL_COMPOSE" "YUUKA_CORS_ALLOWED_ORIGINS must contain HTTPS origins only"

blank_cors_env="$TMP_DIR/blank-cors.env"
write_valid_env "$blank_cors_env"
append_env "$blank_cors_env" "YUUKA_CORS_ALLOWED_ORIGINS=https://yuuka.example.test,,https://other.example.test"
expect_fail blank_cors "$blank_cors_env" "$CANONICAL_COMPOSE" "YUUKA_CORS_ALLOWED_ORIGINS contains a blank origin"

localhost_cors_env="$TMP_DIR/localhost-cors.env"
write_valid_env "$localhost_cors_env"
append_env "$localhost_cors_env" "YUUKA_CORS_ALLOWED_ORIGINS=https://localhost:8081"
expect_fail localhost_cors "$localhost_cors_env" "$CANONICAL_COMPOSE" "YUUKA_CORS_ALLOWED_ORIGINS must not contain localhost origins"

invalid_hash_env="$TMP_DIR/invalid-hash.env"
write_valid_env "$invalid_hash_env"
append_env "$invalid_hash_env" "YUUKA_OWNER_PASSWORD_HASH=not-a-bcrypt-hash"
expect_fail invalid_bcrypt "$invalid_hash_env" "$CANONICAL_COMPOSE" "YUUKA_OWNER_PASSWORD_HASH must be a BCrypt cost-12 hash"

invalid_totp_env="$TMP_DIR/invalid-totp.env"
write_valid_env "$invalid_totp_env"
append_env "$invalid_totp_env" "YUUKA_OWNER_TOTP_SECRET=not-base32!"
expect_fail invalid_base32 "$invalid_totp_env" "$CANONICAL_COMPOSE" "YUUKA_OWNER_TOTP_SECRET must be Base32 encoded"

precedence_env="$TMP_DIR/precedence.env"
write_valid_env "$precedence_env"
append_env "$precedence_env" "YUUKA_JWT_SECRET=short"
expect_pass shell_environment_precedence_matches_compose "$precedence_env" "$CANONICAL_COMPOSE" "YUUKA_JWT_SECRET=$JWT_SECRET"
expect_fail shell_registration_precedence_matches_compose "$valid_env" "$CANONICAL_COMPOSE" "YUUKA_AUTH_REGISTRATION_ENABLED must be explicitly false" "YUUKA_AUTH_REGISTRATION_ENABLED=true"

registration_precedence_env="$TMP_DIR/registration-precedence.env"
write_valid_env "$registration_precedence_env"
append_env "$registration_precedence_env" "YUUKA_AUTH_REGISTRATION_ENABLED=true"
expect_pass shell_false_registration_precedence_matches_compose "$registration_precedence_env" "$CANONICAL_COMPOSE" "YUUKA_AUTH_REGISTRATION_ENABLED=false"

secret_output_env="$TMP_DIR/secret-output.env"
write_valid_env "$secret_output_env"
append_env "$secret_output_env" "YUUKA_OWNER_BOOTSTRAP_PASSWORD=$BOOTSTRAP_SECRET"
expect_fail failure_output_omits_dummy_secrets "$secret_output_env" "$CANONICAL_COMPOSE" "YUUKA_OWNER_BOOTSTRAP_PASSWORD"

printf 'Yuuka production preflight validation tests passed.\n'
