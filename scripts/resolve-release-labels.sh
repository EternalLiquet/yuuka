#!/usr/bin/env bash
set -euo pipefail

labels="$(cat)"
requested=()

for bump in patch minor major; do
  if grep -Fxq "release:$bump" <<< "$labels"; then
    requested+=("$bump")
  fi
done

case "${#requested[@]}" in
  0)
    echo "none"
    ;;
  1)
    echo "${requested[0]}"
    ;;
  *)
    printf 'Multiple release labels are not allowed:' >&2
    printf ' release:%s' "${requested[@]}" >&2
    printf '\n' >&2
    exit 64
    ;;
esac
