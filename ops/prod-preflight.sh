#!/usr/bin/env sh
set -eu
set -f

ENV_FILE=.env
COMPOSE_FILE=docker-compose.yml

usage() {
  cat >&2 <<'USAGE'
Usage: ./ops/prod-preflight.sh [--env-file PATH] [--compose-file PATH]

Validates a Yuuka production environment before docker compose up.
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file)
      [ "$#" -ge 2 ] || {
        usage
        exit 2
      }
      ENV_FILE=$2
      shift 2
      ;;
    --compose-file)
      [ "$#" -ge 2 ] || {
        usage
        exit 2
      }
      COMPOSE_FILE=$2
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage
      exit 2
      ;;
  esac
done

failures=0

fail() {
  printf 'preflight: %s\n' "$1" >&2
  failures=$((failures + 1))
}

trim() {
  printf '%s' "$1" | awk '{ sub(/^[[:space:]]+/, ""); sub(/[[:space:]]+$/, ""); print }'
}

value_from_env_file() {
  key=$1
  [ -f "$ENV_FILE" ] || return 1
  awk -v wanted="$key" '
    function trim_value(value) {
      sub(/^[[:space:]]+/, "", value)
      sub(/[[:space:]]+$/, "", value)
      return value
    }
    BEGIN {
      found = 0
      value = ""
    }
    {
      sub(/\r$/, "")
      if ($0 ~ /^[[:space:]]*($|#)/) {
        next
      }
      equals = index($0, "=")
      if (equals == 0) {
        next
      }
      name = trim_value(substr($0, 1, equals - 1))
      if (name != wanted) {
        next
      }
      raw = trim_value(substr($0, equals + 1))
      if ((raw ~ /^".*"$/) || (raw ~ /^'\''.*'\''$/)) {
        raw = substr(raw, 2, length(raw) - 2)
      }
      value = raw
      found = 1
    }
    END {
      if (!found) {
        exit 1
      }
      print value
    }
  ' "$ENV_FILE"
}

env_value() {
  key=$1
  if value=$(printenv "$key"); then
    printf '%s' "$value"
    return 0
  fi
  value_from_env_file "$key"
}

get_value() {
  env_value "$1" 2>/dev/null || true
}

byte_length() {
  printf '%s' "$1" | wc -c | tr -d '[:space:]'
}

require_present() {
  key=$1
  description=$2
  value=$(get_value "$key")
  if [ -z "$value" ]; then
    fail "$description is required ($key)"
  fi
}

contains_prod_profile() {
  profiles=$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')
  case ",$profiles," in
    *,prod,*)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

check_prod_profile() {
  profiles=$(get_value SPRING_PROFILES_ACTIVE)
  if [ -z "$profiles" ]; then
    fail "SPRING_PROFILES_ACTIVE must include prod"
  elif ! contains_prod_profile "$profiles"; then
    fail "SPRING_PROFILES_ACTIVE must include prod"
  fi
}

check_database_secret() {
  password=$(get_value POSTGRES_PASSWORD)
  if [ -z "$password" ]; then
    fail "POSTGRES_PASSWORD is required"
    return
  fi
  if [ "$password" = "yuuka_dev_password" ]; then
    fail "POSTGRES_PASSWORD must not use the development default"
  fi
  if [ "$(byte_length "$password")" -lt 16 ]; then
    fail "POSTGRES_PASSWORD must be at least 16 bytes"
  fi

  datasource_password=$(get_value SPRING_DATASOURCE_PASSWORD)
  if [ -n "$datasource_password" ] && [ "$datasource_password" != "$password" ]; then
    fail "SPRING_DATASOURCE_PASSWORD must match POSTGRES_PASSWORD for the Compose deployment"
  fi
}

check_jwt_secret() {
  secret=$(get_value YUUKA_JWT_SECRET)
  if [ -z "$secret" ]; then
    fail "YUUKA_JWT_SECRET is required"
    return
  fi
  if [ "$secret" = "change-me-to-a-32-byte-minimum-secret" ]; then
    fail "YUUKA_JWT_SECRET must not use the development default"
  fi
  if [ "$(byte_length "$secret")" -lt 32 ]; then
    fail "YUUKA_JWT_SECRET must be at least 32 bytes"
  fi
}

check_owner_credentials() {
  require_present YUUKA_OWNER_EMAIL "Owner email"
  require_present YUUKA_OWNER_PASSWORD_HASH "Owner password hash"
  require_present YUUKA_OWNER_TOTP_SECRET "Owner TOTP secret"

  password_hash=$(get_value YUUKA_OWNER_PASSWORD_HASH)
  if [ -n "$password_hash" ] \
    && ! printf '%s' "$password_hash" | grep -Eq '^\$2[aby]\$12\$[./A-Za-z0-9]{53}$'; then
    fail "YUUKA_OWNER_PASSWORD_HASH must be a BCrypt cost-12 hash"
  fi

  totp_secret=$(get_value YUUKA_OWNER_TOTP_SECRET)
  if [ -n "$totp_secret" ]; then
    normalized_totp=$(printf '%s' "$totp_secret" | tr -d '[:space:]' | tr '[:lower:]' '[:upper:]')
    if [ "$(byte_length "$normalized_totp")" -lt 16 ] \
      || ! printf '%s' "$normalized_totp" | grep -Eq '^[A-Z2-7]+=*$'; then
      fail "YUUKA_OWNER_TOTP_SECRET must be Base32 encoded"
    fi
  fi

  bootstrap_password=$(get_value YUUKA_OWNER_BOOTSTRAP_PASSWORD)
  if [ -n "$bootstrap_password" ]; then
    fail "YUUKA_OWNER_BOOTSTRAP_PASSWORD must be blank in production"
  fi
}

check_cors_origins() {
  origins=$(get_value YUUKA_CORS_ALLOWED_ORIGINS)
  if [ -z "$origins" ]; then
    fail "YUUKA_CORS_ALLOWED_ORIGINS is required"
    return
  fi
  case "$origins" in
    ,*|*,|*,,*)
      fail "YUUKA_CORS_ALLOWED_ORIGINS contains a blank origin"
      ;;
  esac

  old_ifs=$IFS
  IFS=,
  set -- $origins
  IFS=$old_ifs

  for origin in "$@"; do
    origin=$(trim "$origin")
    if [ -z "$origin" ]; then
      fail "YUUKA_CORS_ALLOWED_ORIGINS contains a blank origin"
      continue
    fi
    if [ "$origin" = "*" ]; then
      fail "YUUKA_CORS_ALLOWED_ORIGINS must not allow wildcard origins"
    fi
    case "$origin" in
      https://*)
        ;;
      *)
        fail "YUUKA_CORS_ALLOWED_ORIGINS must contain HTTPS origins only"
        ;;
    esac
    case "$origin" in
      *localhost*|*127.0.0.1*|*'[::1]'*)
        fail "YUUKA_CORS_ALLOWED_ORIGINS must not contain localhost origins"
        ;;
    esac
  done
}

check_localhost_bind() {
  bind_address=$(get_value YUUKA_BIND_ADDRESS)
  if [ "$bind_address" != "127.0.0.1" ]; then
    fail "YUUKA_BIND_ADDRESS must be 127.0.0.1"
  fi

  if [ ! -f "$COMPOSE_FILE" ]; then
    fail "Compose file not found: $COMPOSE_FILE"
    return
  fi

  if ! grep -F '${YUUKA_BIND_ADDRESS:-127.0.0.1}:${YUUKA_API_PORT:-8080}:8080' "$COMPOSE_FILE" >/dev/null; then
    fail "docker-compose.yml must preserve the 127.0.0.1 backend port binding default"
  fi
}

if [ ! -f "$ENV_FILE" ]; then
  fail "Environment file not found: $ENV_FILE"
else
  check_prod_profile
  check_database_secret
  check_jwt_secret
  check_owner_credentials
  check_cors_origins
  check_localhost_bind
fi

if [ "$failures" -gt 0 ]; then
  printf 'Yuuka production preflight failed with %s problem(s).\n' "$failures" >&2
  exit 1
fi

printf 'Yuuka production preflight passed.\n'
