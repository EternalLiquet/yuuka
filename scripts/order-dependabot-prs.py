#!/usr/bin/env python3
"""Order Dependabot pull requests to reduce avoidable merge conflicts."""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

_VERSION_BUMP_RE = re.compile(
    r"\bfrom\s+v?(?P<old>\d+)(?:\.\d+){0,3}[^\n]*?\bto\s+v?(?P<new>\d+)(?:\.\d+){0,3}\b",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class PullRequest:
    number: int
    title: str
    created_at: str
    files: frozenset[str]
    payload: dict[str, Any]

    @property
    def major_bump(self) -> bool:
        match = _VERSION_BUMP_RE.search(self.title)
        return bool(match and match.group("old") != match.group("new"))

    @property
    def native_mobile(self) -> bool:
        title = self.title.lower()
        return "/mobile" in title and any(
            marker in title
            for marker in (
                "react-native-",
                "expo",
                "gesture-handler",
                "safe-area-context",
                "screens",
                "reanimated",
            )
        )

    @property
    def risk_rank(self) -> int:
        if self.major_bump:
            return 3
        if self.native_mobile:
            return 2
        if any(path.startswith("mobile/") for path in self.files):
            return 1
        return 0


def _extract_files(raw_files: Any, pr_number: int) -> frozenset[str]:
    if not isinstance(raw_files, list):
        raise ValueError(f"PR #{pr_number}: files must be a list")

    paths: set[str] = set()
    for item in raw_files:
        if isinstance(item, str):
            path = item
        elif isinstance(item, dict) and isinstance(item.get("path"), str):
            path = item["path"]
        elif isinstance(item, dict) and isinstance(item.get("filename"), str):
            path = item["filename"]
        else:
            raise ValueError(f"PR #{pr_number}: unsupported file entry {item!r}")

        path = path.strip()
        if not path:
            raise ValueError(f"PR #{pr_number}: file path must not be empty")
        paths.add(path)

    return frozenset(paths)


def parse_pull_requests(payload: Any) -> list[PullRequest]:
    if not isinstance(payload, list):
        raise ValueError("input must be a JSON array")

    seen: set[int] = set()
    parsed: list[PullRequest] = []
    for item in payload:
        if not isinstance(item, dict):
            raise ValueError("every pull request must be a JSON object")

        number = item.get("number")
        if not isinstance(number, int) or number <= 0:
            raise ValueError(f"invalid pull request number: {number!r}")
        if number in seen:
            raise ValueError(f"duplicate pull request number: {number}")
        seen.add(number)

        title = item.get("title", "")
        created_at = item.get("createdAt", "")
        if not isinstance(title, str) or not isinstance(created_at, str):
            raise ValueError(f"PR #{number}: title and createdAt must be strings")

        parsed.append(
            PullRequest(
                number=number,
                title=title,
                created_at=created_at,
                files=_extract_files(item.get("files", []), number),
                payload=item,
            )
        )

    return parsed


def _score(candidate: PullRequest, remaining: Iterable[PullRequest]) -> tuple[Any, ...]:
    peers = [other for other in remaining if other.number != candidate.number]
    intersections = [candidate.files & other.files for other in peers]
    overlapping_peers = sum(bool(paths) for paths in intersections)
    overlapping_paths = sum(len(paths) for paths in intersections)

    return (
        overlapping_peers,
        overlapping_paths,
        candidate.risk_rank,
        len(candidate.files),
        candidate.created_at,
        candidate.number,
    )


def order_pull_requests(pull_requests: list[PullRequest]) -> list[dict[str, Any]]:
    remaining = list(pull_requests)
    ordered: list[dict[str, Any]] = []

    while remaining:
        ranked = sorted((_score(pr, remaining), pr) for pr in remaining)
        score, selected = ranked[0]
        ordered.append(
            {
                **selected.payload,
                "maintenanceOrder": len(ordered) + 1,
                "conflictScore": {
                    "overlappingPeers": score[0],
                    "overlappingPaths": score[1],
                    "riskRank": score[2],
                    "changedFiles": score[3],
                },
            }
        )
        remaining = [pr for pr in remaining if pr.number != selected.number]

    return ordered


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="JSON file produced by gh pr list --json ...")
    args = parser.parse_args()

    try:
        with args.input.open(encoding="utf-8") as stream:
            payload = json.load(stream)
        ordered = order_pull_requests(parse_pull_requests(payload))
    except (OSError, json.JSONDecodeError, ValueError) as error:
        print(f"order-dependabot-prs: {error}", file=sys.stderr)
        return 2

    json.dump(ordered, sys.stdout, indent=2, sort_keys=True)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
