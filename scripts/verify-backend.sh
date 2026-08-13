#!/usr/bin/env bash
set -euo pipefail

mode="${1:-}"
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"

if [[ "$mode" != "fast" && "$mode" != "full" ]]; then
  echo "Usage: $0 <fast|full>" >&2
  exit 64
fi

if [[ ! -x "$repo_root/backend/gradlew" ]]; then
  echo "backend/gradlew is not executable" >&2
  exit 66
fi

run_gradle() {
  local task="$1"
  echo "Backend: $task"
  (cd "$repo_root/backend" && ./gradlew "$task" --no-daemon)
}

run_gradle check

if [[ "$mode" == "full" ]]; then
  run_gradle pitest
  run_gradle bootJar
fi
