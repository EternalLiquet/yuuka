#!/usr/bin/env sh
set -eu

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 owner@example.com" >&2
  exit 2
fi

YUUKA_OWNER_EMAIL=$1
export YUUKA_OWNER_EMAIL
printf "Owner password (12+ characters): " >&2
stty -echo
IFS= read -r YUUKA_PASSWORD_TO_HASH
stty echo
printf "\n" >&2
export YUUKA_PASSWORD_TO_HASH

cleanup() {
  unset YUUKA_PASSWORD_TO_HASH
}
trap cleanup EXIT INT TERM

echo "Password hash:"
(cd backend && ./gradlew -q printPasswordHash)
echo
echo "Authenticator enrollment:"
(cd backend && ./gradlew -q printTotpSecret)
