#!/usr/bin/env python3
"""Adapt an installed TRIP suite from explicit, repository-exploration evidence."""
from __future__ import annotations

import argparse
import datetime as dt
import json
from pathlib import Path
import re
import subprocess
import sys
from typing import Any

SKILLS = ("trip-1-plan", "trip-2-implement", "trip-3-release", "trip-review", "trip-test", "trip-hotfix", "trip-research", "trip-compact", "trip-upgrade")
MUTABLE_SKILLS = ("trip-1-plan", "trip-2-implement", "trip-3-release", "trip-review", "trip-test", "trip-hotfix")
REQUIRED = ("PROJECT_NAME", "VERSION_FILE", "MAIN_BRANCH", "LINT_COMMAND", "TYPECHECK_COMMAND", "TEST_COMMAND", "TEST_COMMAND_ALL", "TEST_COMMAND_SINGLE", "TEST_COMMAND_COVERAGE")
ADAPTATION_FILES = (
    "trip-1-plan/SKILL.md",
    "trip-2-implement/SKILL.md",
    "trip-review/checklist.md",
    "trip-review/cr-template.md",
    "trip-test/SKILL.md",
)
MARKER_RE = re.compile(r"\[ADAPT_TO_PROJECT(?:\:[^\]]+)?\]")
PLACEHOLDER_RE = re.compile(r"\[(?:PROJECT_NAME|VERSION_FILE|MAIN_BRANCH|WEEK_ANCHOR_DATE|WEEK_ANCHOR_EPOCH|LINT_COMMAND|TYPECHECK_COMMAND|TEST_COMMAND(?:_[A-Z]+)?|TUTORIAL_STEP|ADAPT_TO_PROJECT[^\]]*)\]")
REQUIRED_DOCS = ("ARCHI.md", "ARCHI-rules.md", "2-changelog/changelog_table.md", "4-unit-tests/TESTING.md")


def git(project: Path, *args: str) -> str:
    result = subprocess.run(["git", "-C", str(project), *args], text=True, capture_output=True, check=False)
    return result.stdout.strip() if result.returncode == 0 else ""


def discover_version(project: Path) -> str:
    candidates = ("package.json", "pyproject.toml", "Cargo.toml", "gradle.properties", "build.gradle.kts", "build.gradle", "CMakeLists.txt")
    return next((candidate for candidate in candidates if (project / candidate).is_file()), "")


def adaptation_slots(project: Path) -> list[dict[str, object]]:
    slots: list[dict[str, object]] = []
    for relative in ADAPTATION_FILES:
        path = project / ".agents" / "skills" / relative
        text = path.read_text(encoding="utf-8")
        for occurrence, match in enumerate(MARKER_RE.finditer(text), start=1):
            slots.append({"id": f"{relative}#{occurrence}", "path": relative, "marker": match.group(0), "occurrence": occurrence})
    return slots


def load_adaptations(path: Path, expected_slots: list[dict[str, object]]) -> tuple[dict[str, str], dict[str, Any], dict[str, str]]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"adaptation mapping must be valid JSON: {exc}") from exc
    if not isinstance(data, dict):
        raise RuntimeError("adaptation mapping must be a JSON object")
    exploration = data.get("exploration")
    if not isinstance(exploration, dict) or not isinstance(exploration.get("summary"), str) or not exploration["summary"].strip() or not isinstance(exploration.get("project_type"), str) or not exploration["project_type"].strip() or not isinstance(exploration.get("files_inspected"), list) or not exploration["files_inspected"]:
        raise RuntimeError("adaptation mapping must include exploration.summary, exploration.project_type, and nonempty exploration.files_inspected")
    markers = data.get("markers")
    expected_ids = {str(slot["id"]) for slot in expected_slots}
    if not isinstance(markers, dict) or set(markers) != expected_ids:
        missing = sorted(expected_ids - set(markers) if isinstance(markers, dict) else expected_ids)
        extra = sorted(set(markers) - expected_ids) if isinstance(markers, dict) else []
        raise RuntimeError(f"adaptation mapping must cover every active marker exactly once; missing={missing}, extra={extra}")
    if not all(isinstance(value, str) for value in markers.values()):
        raise RuntimeError("each adaptation marker value must be a string (empty is valid only when intentionally removing a marker comment)")
    documents = data.get("documents")
    if not isinstance(documents, dict) or set(documents) != set(REQUIRED_DOCS) or not all(isinstance(documents[name], str) and documents[name].strip() for name in REQUIRED_DOCS):
        raise RuntimeError("adaptation mapping must include nonempty project-adapted documents for " + ", ".join(REQUIRED_DOCS))
    return {name: value for name, value in markers.items()}, exploration, {name: documents[name] for name in REQUIRED_DOCS}


def replace_placeholders(text: str, values: dict[str, str]) -> str:
    for name, value in values.items():
        text = text.replace(f"[{name}]", value)
    return text


def apply_adaptations(project: Path, values: dict[str, str], mappings: dict[str, str], slots: list[dict[str, object]], tutorial_step: str | None) -> dict[Path, str]:
    candidates: dict[Path, str] = {}
    slots_by_path: dict[str, list[dict[str, object]]] = {}
    for slot in slots:
        slots_by_path.setdefault(str(slot["path"]), []).append(slot)
    for skill in MUTABLE_SKILLS:
        for path in (project / ".agents" / "skills" / skill).rglob("*"):
            if path.is_file():
                candidates[path] = replace_placeholders(path.read_text(encoding="utf-8"), values)
    for relative, path_slots in slots_by_path.items():
        path = project / ".agents" / "skills" / relative
        text = candidates[path]
        for slot in path_slots:
            marker = str(slot["marker"])
            if marker not in text:
                raise RuntimeError(f"adaptation marker unexpectedly missing before replacement: {slot['id']}")
            text = text.replace(marker, mappings[str(slot["id"])], 1)
        candidates[path] = text
    release_path = project / ".agents" / "skills" / "trip-3-release" / "SKILL.md"
    release = candidates[release_path]
    tutorial_block = re.compile(r"\n\[TUTORIAL_STEP\]\n\n<!--.*?-->\n", re.DOTALL)
    if tutorial_step is None:
        release, replacements = tutorial_block.subn("\n", release, count=1)
        if replacements != 1:
            raise RuntimeError("could not remove the disabled tutorial block")
    else:
        release = release.replace("[TUTORIAL_STEP]", tutorial_step, 1)
        release = re.sub(r"\n<!--.*?tutorials.*?-->\n", "\n", release, flags=re.DOTALL | re.IGNORECASE)
    candidates[release_path] = release
    unresolved = sorted(str(path.relative_to(project)) for path, text in candidates.items() if PLACEHOLDER_RE.search(text))
    if unresolved:
        raise RuntimeError("initialization would leave unresolved active placeholders:\n" + "\n".join(unresolved))
    return candidates


def write_docs(project: Path, documents: dict[str, str]) -> None:
    docs = project / "docs"
    for name in ("1-plans", "2-changelog", "3-code-review", "4-unit-tests", "6-memo"):
        (docs / name).mkdir(parents=True, exist_ok=True)
    for relative, contents in documents.items():
        target = docs / relative
        if not target.exists():
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(contents.rstrip() + "\n", encoding="utf-8")


def values_from_args(args: argparse.Namespace, project: Path) -> dict[str, str]:
    monday = dt.date.today() - dt.timedelta(days=dt.date.today().weekday())
    epoch = int(dt.datetime.combine(monday, dt.time(), tzinfo=dt.timezone.utc).timestamp())
    return {
        "PROJECT_NAME": args.project_name or project.name,
        "VERSION_FILE": args.version_file or discover_version(project),
        "MAIN_BRANCH": args.main_branch or git(project, "symbolic-ref", "--short", "refs/remotes/origin/HEAD").removeprefix("origin/") or git(project, "branch", "--show-current"),
        "WEEK_ANCHOR_DATE": monday.isoformat(),
        "WEEK_ANCHOR_EPOCH": str(epoch),
        "LINT_COMMAND": args.lint_command or "",
        "TYPECHECK_COMMAND": args.typecheck_command or "",
        "TEST_COMMAND": args.test_command or "",
        "TEST_COMMAND_ALL": args.test_command_all or args.test_command or "",
        "TEST_COMMAND_SINGLE": args.test_command_single or args.test_command or "",
        "TEST_COMMAND_COVERAGE": args.test_command_coverage or args.test_command or "",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, default=Path.cwd())
    parser.add_argument("--project-name")
    parser.add_argument("--version-file")
    parser.add_argument("--main-branch")
    parser.add_argument("--lint-command")
    parser.add_argument("--typecheck-command")
    parser.add_argument("--test-command")
    parser.add_argument("--test-command-all")
    parser.add_argument("--test-command-single")
    parser.add_argument("--test-command-coverage")
    parser.add_argument("--adaptations", type=Path)
    parser.add_argument("--tutorial-step", help="approved tutorial section; omit to disable tutorials and remove the source block")
    parser.add_argument("--approved-architecture", action="store_true", help="required after the user reviews the generated ARCHI.md")
    parser.add_argument("--print-adaptation-slots", action="store_true")
    args = parser.parse_args()
    project = args.project.resolve()
    if not (project / ".agents" / "trip" / "manifest.json").is_file():
        print("error: install TRIP before initialization", file=sys.stderr)
        return 2
    try:
        slots = adaptation_slots(project)
        if args.print_adaptation_slots:
            print(json.dumps({"slots": slots}, indent=2, sort_keys=True))
            return 0
        if not args.adaptations:
            raise RuntimeError("initialization requires --adaptations JSON generated after repository exploration")
        if not args.approved_architecture:
            raise RuntimeError("initialization requires --approved-architecture after the mandatory user review")
        values = values_from_args(args, project)
        missing = [name for name in REQUIRED if not values[name]]
        if missing:
            raise RuntimeError("initialization requires explicit values for " + ", ".join(missing))
        mappings, exploration, documents = load_adaptations(args.adaptations, slots)
        candidates = apply_adaptations(project, values, mappings, slots, args.tutorial_step)
        for path, text in candidates.items():
            path.write_text(text, encoding="utf-8")
        write_docs(project, documents)
        marker = project / ".agents" / "trip" / "initialized.json"
        marker.write_text(json.dumps({"initialized_at": dt.datetime.now(dt.timezone.utc).isoformat(), "project": values["PROJECT_NAME"], "values": values, "exploration": exploration, "adaptation_slots": slots}, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(json.dumps({"initialized": str(marker), "project": values["PROJECT_NAME"]}, sort_keys=True))
        return 0
    except RuntimeError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
