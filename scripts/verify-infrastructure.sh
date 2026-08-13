#!/usr/bin/env bash
set -euo pipefail

mode="${1:-}"
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"

if [[ "$mode" != "fast" && "$mode" != "full" ]]; then
  echo "Usage: $0 <fast|full>" >&2
  exit 64
fi

"$script_dir/validate-codex-workflow.sh"

if [[ "$mode" == "full" ]]; then
  echo "Infrastructure: production preflight tests"
  (cd "$repo_root" && ./ops/prod-preflight-tests.sh)
fi

echo "Infrastructure: Compose configuration"
(cd "$repo_root" && docker compose config --quiet)

if [[ "$mode" == "full" ]]; then
  echo "Infrastructure: backend image build"
  (cd "$repo_root" && YUUKA_VERSION="${YUUKA_VERSION:-0.0.0-local}" docker compose build backend)
fi
