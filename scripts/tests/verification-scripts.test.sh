#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source_scripts="$(cd -- "$script_dir/.." && pwd)"
repo_root="$(cd -- "$source_scripts/.." && pwd)"
fixture_root="$(mktemp -d)"
trap 'rm -rf "$fixture_root"' EXIT

cp "$source_scripts/verify-fast.sh" "$fixture_root/verify-fast.sh"
cp "$source_scripts/verify-full.sh" "$fixture_root/verify-full.sh"

cat > "$fixture_root/component-stub.sh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
component="$(basename "$0" .sh)"
printf '%s:%s\n' "$component" "$1" >> "$YUUKA_VERIFY_TEST_LOG"
if [[ "${YUUKA_VERIFY_TEST_FAIL:-}" == "$component" ]]; then
  exit 23
fi
STUB

for component in backend mobile infrastructure; do
  cp "$fixture_root/component-stub.sh" "$fixture_root/verify-$component.sh"
done
chmod +x "$fixture_root"/*.sh
mkdir "$fixture_root/unrelated-cwd"

assert_log() {
  local expected="$1"
  local actual
  actual="$(cat "$YUUKA_VERIFY_TEST_LOG")"
  if [[ "$actual" != "$expected" ]]; then
    echo "Unexpected verification order:" >&2
    printf 'expected:\n%s\nactual:\n%s\n' "$expected" "$actual" >&2
    exit 1
  fi
}

export YUUKA_VERIFY_TEST_LOG="$fixture_root/invocations.log"
: > "$YUUKA_VERIFY_TEST_LOG"
(cd "$fixture_root/unrelated-cwd" && "$fixture_root/verify-fast.sh")
assert_log $'verify-backend:fast\nverify-mobile:fast\nverify-infrastructure:fast'

: > "$YUUKA_VERIFY_TEST_LOG"
(cd "$fixture_root/unrelated-cwd" && "$fixture_root/verify-full.sh")
assert_log $'verify-backend:full\nverify-mobile:full\nverify-infrastructure:full'

: > "$YUUKA_VERIFY_TEST_LOG"
set +e
(cd "$fixture_root/unrelated-cwd" && YUUKA_VERIFY_TEST_FAIL=verify-mobile "$fixture_root/verify-fast.sh")
status=$?
set -e
if [[ "$status" -ne 23 ]]; then
  echo "Expected child exit 23, got $status" >&2
  exit 1
fi
assert_log $'verify-backend:fast\nverify-mobile:fast'

echo "Verification entry-point orchestration tests passed."

resolver="$source_scripts/resolve-release-labels.sh"

assert_release_bump() {
  local labels="$1"
  local expected="$2"
  local actual
  actual="$(printf '%s' "$labels" | "$resolver")"
  if [[ "$actual" != "$expected" ]]; then
    echo "Unexpected release bump: expected $expected, got $actual" >&2
    exit 1
  fi
}

assert_release_bump "" "none"
assert_release_bump "documentation" "none"
assert_release_bump "release:patch" "patch"
assert_release_bump $'maintenance\nrelease:minor\n' "minor"
assert_release_bump "release:major" "major"
assert_release_bump $'release:patch\nrelease:patch\n' "patch"

set +e
multiple_output="$(printf '%s\n' release:patch release:minor | "$resolver" 2>&1)"
multiple_status=$?
set -e
if [[ "$multiple_status" -ne 64 ]]; then
  echo "Expected multiple release labels to exit 64, got $multiple_status" >&2
  exit 1
fi
if [[ "$multiple_output" != *"Multiple release labels are not allowed"* ]]; then
  echo "Expected a clear multiple-label error, got: $multiple_output" >&2
  exit 1
fi

echo "Release-label resolution tests passed."

validator_fixture="$fixture_root/validator-repo"
mkdir -p "$validator_fixture/.github" "$validator_fixture/scripts"
cp -a "$repo_root/.agents" "$validator_fixture/.agents"
cp -a "$repo_root/.codex" "$validator_fixture/.codex"
cp -a "$repo_root/.github/workflows" "$validator_fixture/.github/workflows"
cp -a "$source_scripts/." "$validator_fixture/scripts/"

python3 "$validator_fixture/scripts/validate-codex-workflow.py" >/dev/null

assert_validator_rejects_mutation() {
  local mutation="$1"
  local expected_message="$2"
  local case_root="$fixture_root/validator-$mutation"
  local output
  local status

  cp -a "$validator_fixture" "$case_root"
  python3 - "$case_root/.github/workflows/ci.yml" "$mutation" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
mutation = sys.argv[2]
workflow = path.read_text(encoding="utf-8")

if mutation == "missing-publication-queue":
    old = """    concurrency:\n      group: yuuka-release-master\n      cancel-in-progress: false\n      queue: max\n"""
    new = """    concurrency:\n      group: yuuka-release-master\n      cancel-in-progress: false\n"""
elif mutation == "missing-release-pipeline-queue":
    old = """concurrency:\n  group: ${{ ((github.event_name == 'push' && github.ref == 'refs/heads/master') || inputs.release_bump != '') && 'yuuka-release-pipeline' || format('yuuka-ci-run-{0}', github.run_id) }}\n  cancel-in-progress: false\n  queue: max\n\n"""
    new = ""
elif mutation == "prefiltered-release-input":
    old = """    if: >-\n      (github.event_name == 'push' && github.ref == 'refs/heads/master') ||\n      inputs.release_bump != ''\n"""
    new = """    if: >-\n      (github.event_name == 'push' && github.ref == 'refs/heads/master') ||\n      inputs.release_bump == 'patch' ||\n      inputs.release_bump == 'minor' ||\n      inputs.release_bump == 'major'\n"""
else:
    raise SystemExit(f"unsupported mutation: {mutation}")

if workflow.count(old) != 1:
    raise SystemExit(f"expected exactly one mutation target for {mutation}")
path.write_text(workflow.replace(old, new, 1), encoding="utf-8")
PY

  set +e
  output="$(python3 "$case_root/scripts/validate-codex-workflow.py" 2>&1)"
  status=$?
  set -e

  if [[ "$status" -eq 0 ]]; then
    echo "Expected workflow validator to reject $mutation" >&2
    exit 1
  fi
  if [[ "$output" != *"$expected_message"* ]]; then
    echo "Unexpected validator output for $mutation: $output" >&2
    exit 1
  fi
}

assert_validator_rejects_mutation \
  "missing-publication-queue" \
  "complete shared serialized publication queue"
assert_validator_rejects_mutation \
  "missing-release-pipeline-queue" \
  "workflow-level concurrency"
assert_validator_rejects_mutation \
  "prefiltered-release-input" \
  "every nonempty release_bump"

echo "Release workflow validator regression tests passed."
