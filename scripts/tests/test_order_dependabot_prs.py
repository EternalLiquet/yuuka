from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[1] / "order-dependabot-prs.py"
SPEC = importlib.util.spec_from_file_location("order_dependabot_prs", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class OrderDependabotPullRequestsTest(unittest.TestCase):
    def order(self, payload: list[dict]) -> list[int]:
        parsed = MODULE.parse_pull_requests(payload)
        return [item["number"] for item in MODULE.order_pull_requests(parsed)]

    def test_isolated_changes_are_processed_before_overlapping_changes(self) -> None:
        payload = [
            self.pr(1, ["mobile/package.json", "mobile/package-lock.json"]),
            self.pr(2, ["mobile/package.json", "mobile/package-lock.json"]),
            self.pr(3, [".github/workflows/codeql.yml"]),
        ]

        self.assertEqual([3, 1, 2], self.order(payload))

    def test_fewer_shared_paths_win_when_peer_count_matches(self) -> None:
        payload = [
            self.pr(1, ["shared-a", "shared-b"]),
            self.pr(2, ["shared-a", "shared-b", "only-2"]),
            self.pr(3, ["shared-a", "only-3"]),
        ]

        self.assertEqual(3, self.order(payload)[0])

    def test_major_native_mobile_bump_is_last_for_equivalent_conflicts(self) -> None:
        payload = [
            self.pr(
                1,
                ["mobile/package.json", "mobile/package-lock.json"],
                "bump react-hook-form from 7.1.0 to 7.2.0 in /mobile",
            ),
            self.pr(
                2,
                ["mobile/package.json", "mobile/package-lock.json"],
                "bump react-native-gesture-handler from 2.0.0 to 3.0.0 in /mobile",
            ),
        ]

        self.assertEqual([1, 2], self.order(payload))

    def test_duplicate_pull_request_numbers_are_rejected(self) -> None:
        payload = [self.pr(1, ["a"]), self.pr(1, ["b"])]

        with self.assertRaisesRegex(ValueError, "duplicate pull request number"):
            MODULE.parse_pull_requests(payload)

    @staticmethod
    def pr(number: int, files: list[str], title: str = "dependency update") -> dict:
        return {
            "number": number,
            "title": title,
            "createdAt": f"2026-08-{number:02d}T00:00:00Z",
            "files": [{"path": path} for path in files],
        }


if __name__ == "__main__":
    unittest.main()
