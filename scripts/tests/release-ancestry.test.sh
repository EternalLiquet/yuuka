#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
resolver="$(cd -- "$script_dir/.." && pwd)/check-release-ancestry.sh"
fixture_root="$(mktemp -d)"
trap 'rm -rf "$fixture_root"' EXIT

new_repo() {
  local path="$1"
  git init -q "$path"
  git -C "$path" config user.name "Yuuka Test"
  git -C "$path" config user.email "yuuka-test@example.invalid"
}

commit_file() {
  local repo="$1"
  local value="$2"
  printf '%s\n' "$value" > "$repo/state.txt"
  git -C "$repo" add state.txt
  git -C "$repo" commit -q -m "$value"
  git -C "$repo" rev-parse HEAD
}

assert_result() {
  local repo="$1"
  local target="$2"
  local expected="$3"
  local actual
  actual="$(git -C "$repo" -c advice.detachedHead=false rev-parse "$target" >/dev/null && cd "$repo" && "$resolver" "$target")"
  if [[ "$actual" != "$expected" ]]; then
    echo "Expected release ancestry result $expected, got $actual" >&2
    exit 1
  fi
}

forward_repo="$fixture_root/forward"
new_repo "$forward_repo"
a="$(commit_file "$forward_repo" A)"
git -C "$forward_repo" tag v1.0.0 "$a"
b="$(commit_file "$forward_repo" B)"
assert_result "$forward_repo" "$b" "forward"

same_repo="$fixture_root/same"
new_repo "$same_repo"
commit_file "$same_repo" A >/dev/null
b="$(commit_file "$same_repo" B)"
git -C "$same_repo" tag v1.0.1 "$b"
assert_result "$same_repo" "$b" "same-commit"

stale_repo="$fixture_root/stale"
new_repo "$stale_repo"
a="$(commit_file "$stale_repo" A)"
b="$(commit_file "$stale_repo" B)"
git -C "$stale_repo" tag v1.0.1 "$b"
set +e
stale_output="$(cd "$stale_repo" && "$resolver" "$a" 2>&1)"
stale_status=$?
set -e
if [[ "$stale_status" -ne 65 ]]; then
  echo "Expected stale target to exit 65, got $stale_status" >&2
  exit 1
fi
if [[ "$stale_output" != *"Stale release target"* || "$stale_output" != *"Dispatch the desired bump again from current master"* ]]; then
  echo "Expected actionable stale-release error, got: $stale_output" >&2
  exit 1
fi

divergent_repo="$fixture_root/divergent"
new_repo "$divergent_repo"
a="$(commit_file "$divergent_repo" A)"
git -C "$divergent_repo" branch release-side "$a"
b="$(commit_file "$divergent_repo" B)"
git -C "$divergent_repo" tag v1.0.1 "$b"
git -C "$divergent_repo" checkout -q release-side
c="$(commit_file "$divergent_repo" C)"
set +e
divergent_output="$(cd "$divergent_repo" && "$resolver" "$c" 2>&1)"
divergent_status=$?
set -e
if [[ "$divergent_status" -ne 65 ]]; then
  echo "Expected divergent target to exit 65, got $divergent_status" >&2
  exit 1
fi
if [[ "$divergent_output" != *"Divergent release target"* ]]; then
  echo "Expected clear divergent-release error, got: $divergent_output" >&2
  exit 1
fi

echo "Release ancestry regression tests passed."
