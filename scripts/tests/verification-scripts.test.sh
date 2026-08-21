#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source_scripts="$(cd -- "$script_dir/.." && pwd)"
fixture_root="$(mktemp -d)"
trap 'rm -rf "$fixture_root"' EXIT

cp "$source_scripts/verify-fast.sh" "$fixture_root/verify-fast.sh"
cp "$source_scripts/verify-full.sh" "$fixture_root/verify-full.sh"

cat > "$fixture_root/component-stub.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
component="$(basename "$0" .sh)"
printf '%s:%s\n' "$component" "$1" >> "$YUUKA_VERIFY_TEST_LOG"
if [[ "${YUUKA_VERIFY_TEST_FAIL:-}" == "$component" ]]; then
  exit 23
fi
EOF

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
