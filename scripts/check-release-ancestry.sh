#!/usr/bin/env bash
set -euo pipefail

target="${1:-HEAD}"
target_commit="$(git rev-parse "${target}^{commit}")"
latest="$(git tag --list 'v[0-9]*.[0-9]*.[0-9]*' | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -n 1 || true)"

if [[ -z "$latest" ]]; then
  echo "No semantic-version release tag exists; first release may proceed." >&2
  printf '%s\n' "first-release"
  exit 0
fi

latest_commit="$(git rev-list -n 1 "$latest")"

if [[ "$latest_commit" == "$target_commit" ]]; then
  echo "Latest release $latest already references target $target_commit; idempotent rerun may proceed." >&2
  printf '%s\n' "same-commit"
  exit 0
fi

if git merge-base --is-ancestor "$latest_commit" "$target_commit"; then
  echo "Latest release $latest ($latest_commit) is an ancestor of target $target_commit." >&2
  printf '%s\n' "forward"
  exit 0
fi

if git merge-base --is-ancestor "$target_commit" "$latest_commit"; then
  echo "Stale release target: newer commit $latest_commit is already released as $latest; refusing to release older target $target_commit. Dispatch the desired bump again from current master." >&2
  exit 65
fi

echo "Divergent release target: latest release $latest ($latest_commit) is not an ancestor of target $target_commit. Refusing to assign a higher semantic version across divergent history. Reconcile master and dispatch the desired bump again from current master." >&2
exit 65
