#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

"$script_dir/verify-backend.sh" full
"$script_dir/verify-mobile.sh" full
"$script_dir/verify-infrastructure.sh" full
