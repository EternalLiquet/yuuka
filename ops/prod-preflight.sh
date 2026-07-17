#!/usr/bin/env sh
set -eu

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

registration_value_from_env_file() {
  awk '
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
      if (name != "YUUKA_AUTH_REGISTRATION_ENABLED") {
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

check_registration_explicitly_disabled() {
  if value=$(printenv YUUKA_AUTH_REGISTRATION_ENABLED); then
    :
  elif value=$(registration_value_from_env_file 2>/dev/null); then
    :
  else
    fail "YUUKA_AUTH_REGISTRATION_ENABLED must be explicitly false"
    return
  fi

  normalized=$(printf '%s' "$value" | tr '[:upper:]' '[:lower:]')
  if [ "$normalized" != "false" ]; then
    fail "YUUKA_AUTH_REGISTRATION_ENABLED must be explicitly false"
  fi
}

check_file_permissions() {
  if [ ! -f "$ENV_FILE" ]; then
    fail "Environment file not found: $ENV_FILE"
    return
  fi

  mode=$(stat -c '%a' "$ENV_FILE" 2>/dev/null) || {
    fail "Unable to inspect environment file permissions: $ENV_FILE"
    return
  }

  group_digit=$(((mode / 10) % 10))
  other_digit=$((mode % 10))
  if [ $((group_digit & 6)) -ne 0 ] || [ $((other_digit & 6)) -ne 0 ]; then
    fail "Environment file must not be readable or writable by group or others: $ENV_FILE"
  fi
}

run_compose_quiet_validation() {
  if [ ! -f "$COMPOSE_FILE" ]; then
    fail "Compose file not found: $COMPOSE_FILE"
    return
  fi

  if ! docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" config --quiet >/dev/null 2>&1; then
    fail "Docker Compose configuration is invalid"
  fi
}

run_effective_config_validation() {
  if ! command -v python3 >/dev/null 2>&1; then
    fail "python3 is required for resolved Docker Compose validation"
    return
  fi

  validation_output=$(
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" config --format json 2>/dev/null \
      | python3 -c '
import json
import re
import sys

DEVELOPMENT_DATABASE_PASSWORD = "yuuka_dev_password"
DEVELOPMENT_JWT_SECRET = "change-me-to-a-32-byte-minimum-secret"
BCRYPT_COST_12 = re.compile(r"^\$2[aby]\$12\$[./A-Za-z0-9]{53}$")
BASE32 = re.compile(r"^[A-Z2-7]+=*$")


def emit(message):
    print(message)


def as_environment(value):
    if isinstance(value, dict):
        return {str(key): "" if val is None else str(val).replace("$$", "$") for key, val in value.items()}
    if isinstance(value, list):
        result = {}
        for item in value:
            text = str(item)
            if "=" in text:
                key, val = text.split("=", 1)
                result[key] = val
        return result
    return {}


def env_value(environment, key):
    return environment.get(key, "")


def is_blank(value):
    return value is None or str(value).strip() == ""


def require_nonblank(environment, key):
    if is_blank(env_value(environment, key)):
        emit(f"{key} is required")


def has_prod_profile(value):
    return "prod" in [profile.strip().lower() for profile in str(value).split(",")]


def validate_password(value, key):
    if is_blank(value):
        emit(f"{key} is required")
        return
    if value == DEVELOPMENT_DATABASE_PASSWORD:
        emit(f"{key} must not use the development default")
    if len(value) < 16 or len(value.encode("utf-8")) < 16:
        emit(f"{key} must be at least 16 characters")


def validate_cors(value):
    if is_blank(value):
        emit("YUUKA_CORS_ALLOWED_ORIGINS is required")
        return
    if value.startswith(",") or value.endswith(",") or ",," in value:
        emit("YUUKA_CORS_ALLOWED_ORIGINS contains a blank origin")
    for origin in [part.strip() for part in value.split(",")]:
        if origin == "":
            emit("YUUKA_CORS_ALLOWED_ORIGINS contains a blank origin")
            continue
        if origin == "*":
            emit("YUUKA_CORS_ALLOWED_ORIGINS must not allow wildcard origins")
        if not origin.startswith("https://"):
            emit("YUUKA_CORS_ALLOWED_ORIGINS must contain HTTPS origins only")
        if "localhost" in origin or "127.0.0.1" in origin or "[::1]" in origin:
            emit("YUUKA_CORS_ALLOWED_ORIGINS must not contain localhost origins")


def port_target(port):
    target = port.get("target")
    try:
        return int(target)
    except (TypeError, ValueError):
        return None


try:
    config = json.load(sys.stdin)
except json.JSONDecodeError:
    emit("Docker Compose resolved JSON could not be parsed")
    sys.exit(0)

services = config.get("services")
if not isinstance(services, dict):
    emit("Docker Compose services could not be found")
    sys.exit(0)

backend = services.get("backend")
postgres = services.get("postgres")
if not isinstance(backend, dict):
    emit("Docker Compose backend service could not be found")
    backend = {}
if not isinstance(postgres, dict):
    emit("Docker Compose postgres service could not be found")
    postgres = {}

if str(backend.get("network_mode", "")).lower() == "host":
    emit("backend service must not use host networking")

ports = backend.get("ports")
if ports is None:
    ports = []
if not isinstance(ports, list):
    emit("backend service ports could not be parsed")
    ports = []

published_ports = [port for port in ports if isinstance(port, dict) and port.get("published") not in (None, "")]
safe_bindings = 0
for port in published_ports:
    target = port_target(port)
    host_ip = str(port.get("host_ip", ""))
    if target == 8080 and host_ip == "127.0.0.1":
        safe_bindings += 1
    elif target == 8080 and host_ip == "":
        emit("backend port 8080 must declare host_ip 127.0.0.1")
    elif target == 8080:
        emit("backend port 8080 must bind only to host_ip 127.0.0.1")
    else:
        emit("backend service must not publish ports other than container port 8080")

if safe_bindings == 0:
    emit("backend service must publish container port 8080 on host_ip 127.0.0.1")
if len(published_ports) != 1:
    emit("backend service must publish exactly one public port")

backend_environment = as_environment(backend.get("environment"))
postgres_environment = as_environment(postgres.get("environment"))

profiles = env_value(backend_environment, "SPRING_PROFILES_ACTIVE")
if is_blank(profiles) or not has_prod_profile(profiles):
    emit("SPRING_PROFILES_ACTIVE must include prod")

registration = env_value(backend_environment, "YUUKA_AUTH_REGISTRATION_ENABLED")
if registration.lower() != "false":
    emit("YUUKA_AUTH_REGISTRATION_ENABLED must be explicitly false")

require_nonblank(backend_environment, "YUUKA_OWNER_EMAIL")

password_hash = env_value(backend_environment, "YUUKA_OWNER_PASSWORD_HASH")
if is_blank(password_hash):
    emit("YUUKA_OWNER_PASSWORD_HASH is required")
elif not BCRYPT_COST_12.match(password_hash.strip()):
    emit("YUUKA_OWNER_PASSWORD_HASH must be a BCrypt cost-12 hash")

totp_secret = env_value(backend_environment, "YUUKA_OWNER_TOTP_SECRET")
if is_blank(totp_secret):
    emit("YUUKA_OWNER_TOTP_SECRET is required")
else:
    normalized_totp = "".join(str(totp_secret).split()).upper()
    if len(normalized_totp) < 16 or not BASE32.match(normalized_totp):
        emit("YUUKA_OWNER_TOTP_SECRET must be Base32 encoded")

if not is_blank(env_value(backend_environment, "YUUKA_OWNER_BOOTSTRAP_PASSWORD")):
    emit("YUUKA_OWNER_BOOTSTRAP_PASSWORD must be blank in production")

jwt_secret = env_value(backend_environment, "YUUKA_JWT_SECRET")
if is_blank(jwt_secret):
    emit("YUUKA_JWT_SECRET is required")
elif jwt_secret == DEVELOPMENT_JWT_SECRET:
    emit("YUUKA_JWT_SECRET must not use the development default")
elif len(jwt_secret.encode("utf-8")) < 32:
    emit("YUUKA_JWT_SECRET must be at least 32 UTF-8 bytes")

validate_cors(env_value(backend_environment, "YUUKA_CORS_ALLOWED_ORIGINS"))

postgres_password = env_value(postgres_environment, "POSTGRES_PASSWORD")
datasource_password = env_value(backend_environment, "SPRING_DATASOURCE_PASSWORD")
validate_password(postgres_password, "POSTGRES_PASSWORD")
validate_password(datasource_password, "SPRING_DATASOURCE_PASSWORD")
if not is_blank(postgres_password) and not is_blank(datasource_password) and postgres_password != datasource_password:
    emit("SPRING_DATASOURCE_PASSWORD must match POSTGRES_PASSWORD for the Compose deployment")
'
  ) || {
    fail "Docker Compose resolved configuration could not be inspected"
    return
  }

  if [ -n "$validation_output" ]; then
    printf '%s\n' "$validation_output" | while IFS= read -r line; do
      [ -n "$line" ] && printf 'preflight: %s\n' "$line" >&2
    done
    validation_count=$(printf '%s\n' "$validation_output" | awk 'NF { count++ } END { print count + 0 }')
    failures=$((failures + validation_count))
  fi
}

check_file_permissions

if [ "$failures" -eq 0 ]; then
  check_registration_explicitly_disabled
fi

if [ "$failures" -eq 0 ]; then
  run_compose_quiet_validation
fi

if [ "$failures" -eq 0 ]; then
  run_effective_config_validation
fi

if [ "$failures" -gt 0 ]; then
  printf 'Yuuka production preflight failed with %s problem(s).\n' "$failures" >&2
  exit 1
fi

printf 'Yuuka production preflight passed.\n'
