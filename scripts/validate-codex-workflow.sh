#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"

while IFS= read -r -d '' script; do
  bash -n "$script"
done < <(find "$script_dir" -type f -name '*.sh' -print0)

python3 "$script_dir/validate-codex-workflow.py"
"$script_dir/tests/verification-scripts.test.sh"
git -C "$repo_root" diff --check HEAD --

echo "Codex workflow validation passed."
