#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

"$script_dir/verify-backend.sh" fast
"$script_dir/verify-mobile.sh" fast
"$script_dir/verify-infrastructure.sh" fast
