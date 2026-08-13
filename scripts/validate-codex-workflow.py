#!/usr/bin/env python3
"""Validate Yuuka's repository-scoped Codex role configuration and CI wiring."""

from __future__ import annotations

import os
import re
import sys
import tomllib
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parent.parent
EXPECTED_SKILLS = {
    "yuuka-plan": "yuuka_planner",
    "yuuka-verify": "yuuka_verifier",
    "yuuka-review": "yuuka_reviewer",
}
ALL_SKILLS = {*EXPECTED_SKILLS, "yuuka-implement"}
EXPECTED_SANDBOX = {
    "yuuka_planner": "read-only",
    "yuuka_verifier": "workspace-write",
    "yuuka_reviewer": "read-only",
}


def fail(message: str) -> None:
    print(f"workflow validation: {message}", file=sys.stderr)
    raise SystemExit(1)


def parse_skill(skill_name: str) -> None:
    skill_file = REPO_ROOT / ".agents" / "skills" / skill_name / "SKILL.md"
    if not skill_file.is_file():
        fail(f"missing {skill_file.relative_to(REPO_ROOT)}")

    content = skill_file.read_text(encoding="utf-8")
    match = re.match(r"\A---\n(.*?)\n---\n", content, re.DOTALL)
    if match is None:
        fail(f"{skill_name} has invalid YAML frontmatter boundaries")

    fields: dict[str, str] = {}
    for line in match.group(1).splitlines():
        key, separator, value = line.partition(":")
        if not separator or not key.strip() or not value.strip():
            fail(f"{skill_name} has unsupported frontmatter line: {line!r}")
        fields[key.strip()] = value.strip()

    if set(fields) != {"name", "description"}:
        fail(f"{skill_name} frontmatter must contain only name and description")
    if fields["name"] != skill_name:
        fail(f"{skill_name} frontmatter name does not match its directory")
    if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", fields["name"]):
        fail(f"{skill_name} is not a valid hyphen-case skill name")
    if len(fields["description"]) > 1024:
        fail(f"{skill_name} description exceeds 1024 characters")
    if "TODO" in content:
        fail(f"{skill_name} still contains a TODO placeholder")


def parse_agent(skill_name: str, agent_name: str) -> None:
    agent_file = REPO_ROOT / ".codex" / "agents" / f"{agent_name}.toml"
    if not agent_file.is_file():
        fail(f"missing {agent_file.relative_to(REPO_ROOT)}")

    with agent_file.open("rb") as handle:
        data = tomllib.load(handle)

    required = {"name", "description", "developer_instructions"}
    if not required.issubset(data):
        fail(f"{agent_name} is missing required custom-agent fields")
    if data["name"] != agent_name:
        fail(f"{agent_name} TOML name does not match its filename")
    if data.get("sandbox_mode") != EXPECTED_SANDBOX[agent_name]:
        fail(f"{agent_name} has the wrong sandbox_mode")
    if f"${skill_name}" not in data["developer_instructions"]:
        fail(f"{agent_name} does not invoke ${skill_name}")
    if "model" in data or "model_reasoning_effort" in data:
        fail(f"{agent_name} must inherit the active model configuration")


def validate_ci_wiring() -> None:
    workflow = (REPO_ROOT / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")
    for component in ("backend", "mobile", "infrastructure"):
        expected = f"./scripts/verify-{component}.sh full"
        if expected not in workflow:
            fail(f"CI does not invoke {expected}")


def validate_executables() -> None:
    scripts = [
        *sorted((REPO_ROOT / "scripts").glob("*.sh")),
        *sorted((REPO_ROOT / "scripts" / "tests").glob("*.sh")),
    ]
    for script in scripts:
        if not os.access(script, os.X_OK):
            fail(f"{script.relative_to(REPO_ROOT)} is not executable")


def main() -> None:
    for skill_name in sorted(ALL_SKILLS):
        parse_skill(skill_name)
    for skill_name, agent_name in EXPECTED_SKILLS.items():
        parse_agent(skill_name, agent_name)
    validate_ci_wiring()
    validate_executables()
    print("Codex workflow metadata and CI wiring are valid.")


if __name__ == "__main__":
    main()
