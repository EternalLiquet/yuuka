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
RELEASE_BUMPS = ("patch", "minor", "major")
RELEASE_PIPELINE_GROUP = "yuuka-release-pipeline"
PUBLICATION_GROUP = "yuuka-release-master"


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


def require_fragment(content: str, fragment: str, description: str) -> None:
    if fragment not in content:
        fail(description)


def extract_job(workflow: str, job_name: str) -> str:
    match = re.search(
        rf"(?ms)^  {re.escape(job_name)}:\n(?P<body>.*?)(?=^  [A-Za-z0-9_-]+:\n|\Z)",
        workflow,
    )
    if match is None:
        fail(f"CI does not contain the {job_name} job")
    return match.group("body")


def extract_folded_scalar(job: str, key: str) -> str:
    match = re.search(
        rf"(?m)^    {re.escape(key)}: >-\n(?P<body>(?:^      [^\n]*(?:\n|\Z))+)",
        job,
    )
    if match is None:
        fail(f"CI {key} is not expressed as the expected folded scalar")
    return " ".join(line.strip() for line in match.group("body").splitlines())


def extract_simple_mapping(job: str, key: str) -> tuple[str, ...]:
    match = re.search(
        rf"(?m)^    {re.escape(key)}:\n(?P<body>(?:^      [^\n]*(?:\n|\Z))+)",
        job,
    )
    if match is None:
        fail(f"CI does not define the {key} mapping in the expected job")
    return tuple(line.strip() for line in match.group("body").splitlines())


def extract_top_level_mapping(workflow: str, key: str) -> tuple[str, ...]:
    match = re.search(
        rf"(?m)^{re.escape(key)}:\n(?P<body>(?:^  [^\n]*(?:\n|\Z))+)",
        workflow,
    )
    if match is None:
        fail(f"CI does not define the workflow-level {key} mapping")
    return tuple(line.strip() for line in match.group("body").splitlines())


def validate_ci_wiring() -> None:
    workflow = (REPO_ROOT / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")
    for component in ("backend", "mobile", "infrastructure"):
        expected = f"./scripts/verify-{component}.sh full"
        require_fragment(workflow, expected, f"CI does not invoke {expected}")

    require_fragment(workflow, "workflow_call:", "CI is not reusable by manual release workflows")
    require_fragment(workflow, "release_bump:", "CI does not define the manual release bump input")
    require_fragment(workflow, "release-decision:", "CI does not contain a release decision job")
    require_fragment(
        workflow,
        "./scripts/resolve-release-labels.sh",
        "CI does not resolve opt-in pull-request release labels",
    )
    require_fragment(
        workflow,
        "needs.release-decision.outputs.bump != 'none'",
        "CI release publication is not gated by an explicit release decision",
    )

    expected_pipeline_group = (
        "group: ${{ ((github.event_name == 'push' && github.ref == 'refs/heads/master') || "
        "inputs.release_bump != '') && 'yuuka-release-pipeline' || "
        "format('yuuka-ci-run-{0}', github.run_id) }}"
    )
    expected_workflow_concurrency = (
        expected_pipeline_group,
        "cancel-in-progress: false",
        "queue: max",
    )
    actual_workflow_concurrency = extract_top_level_mapping(workflow, "concurrency")
    if actual_workflow_concurrency != expected_workflow_concurrency:
        fail(
            "CI workflow-level concurrency must serialize master pushes and nonempty reusable "
            "release inputs in yuuka-release-pipeline while isolating PR and ordinary manual validation"
        )
    if RELEASE_PIPELINE_GROUP == PUBLICATION_GROUP:
        fail("CI release pipeline and publication concurrency groups must be distinct")

    cancellable_pr_only = "cancel-in-progress: ${{ github.event_name == 'pull_request' }}"
    if workflow.count(cancellable_pr_only) != 3:
        fail("CI must cancel stale component jobs only for pull-request runs")

    isolated_non_pr = "github.event_name == 'pull_request' && github.ref || github.run_id"
    if workflow.count(isolated_non_pr) != 3:
        fail("CI must isolate master and manual component runs from concurrency replacement")

    release_decision_job = extract_job(workflow, "release-decision")
    expected_release_condition = (
        "(github.event_name == 'push' && github.ref == 'refs/heads/master') || "
        "inputs.release_bump != ''"
    )
    actual_release_condition = extract_folded_scalar(release_decision_job, "if")
    if actual_release_condition != expected_release_condition:
        fail(
            "CI release decision must run for master pushes and every nonempty release_bump "
            "so unsupported inputs reach shell validation"
        )
    require_fragment(
        release_decision_job,
        'if [[ "$GITHUB_REF" != "refs/heads/master" ]]; then',
        "CI manual releases do not fail closed outside master",
    )
    require_fragment(
        release_decision_job,
        "Unsupported manual release bump: $REQUESTED_BUMP",
        "CI does not reject unsupported nonempty manual release bumps in the shell decision",
    )

    release_job = extract_job(workflow, "release")
    expected_release_concurrency = (
        f"group: {PUBLICATION_GROUP}",
        "cancel-in-progress: false",
        "queue: max",
    )
    actual_release_concurrency = extract_simple_mapping(release_job, "concurrency")
    if actual_release_concurrency != expected_release_concurrency:
        fail(
            "CI release concurrency must use the complete shared serialized publication queue: "
            f"group {PUBLICATION_GROUP}, cancel-in-progress false, queue max"
        )
    if any(RELEASE_PIPELINE_GROUP in line for line in actual_release_concurrency):
        fail("CI publication queue must use a group distinct from the workflow-level release pipeline")
    require_fragment(
        release_job,
        './scripts/check-release-ancestry.sh "$GITHUB_SHA"',
        "CI release version step does not invoke the fail-closed release ancestry guard",
    )

    for bump in RELEASE_BUMPS:
        wrapper_path = REPO_ROOT / ".github" / "workflows" / f"release-{bump}.yml"
        if not wrapper_path.is_file():
            fail(f"missing {wrapper_path.relative_to(REPO_ROOT)}")
        wrapper = wrapper_path.read_text(encoding="utf-8")
        require_fragment(wrapper, "workflow_dispatch:", f"{wrapper_path.name} is not manual-only")
        require_fragment(
            wrapper,
            "uses: ./.github/workflows/ci.yml",
            f"{wrapper_path.name} does not reuse CI verification",
        )
        require_fragment(
            wrapper,
            f"release_bump: {bump}",
            f"{wrapper_path.name} requests the wrong semantic-version bump",
        )
        require_fragment(
            wrapper,
            "contents: write",
            f"{wrapper_path.name} cannot publish release tags and assets",
        )
        require_fragment(
            wrapper,
            "pull-requests: read",
            f"{wrapper_path.name} cannot satisfy the shared release-decision permissions",
        )


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
