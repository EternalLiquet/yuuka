#!/usr/bin/env bash
set -euo pipefail

mode="${1:-}"
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
mobile_dir="$repo_root/mobile"

if [[ "$mode" != "fast" && "$mode" != "full" ]]; then
  echo "Usage: $0 <fast|full>" >&2
  exit 64
fi

if [[ ! -d "$mobile_dir/node_modules" ]]; then
  echo "Mobile dependencies are missing. Run: (cd mobile && npm ci)" >&2
  exit 66
fi

run_npm() {
  local task="$1"
  echo "Mobile: npm run $task"
  (cd "$mobile_dir" && npm run "$task")
}

run_npm format:check
run_npm lint
run_npm typecheck
run_npm test:coverage

if [[ "$mode" == "full" ]]; then
  echo "Mobile: Expo Doctor"
  (cd "$mobile_dir" && npx expo-doctor)
  echo "Mobile: Android export"
  (cd "$mobile_dir" && npx expo export --platform android --output-dir dist/android)
  run_npm test:audit-policy
  run_npm audit:production
fi
