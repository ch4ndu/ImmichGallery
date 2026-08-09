#!/usr/bin/env python3
"""Validate the generic TRIP package, its source parity map, and private runtime phases."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import stat
import sys
from typing import Any

PUBLIC_SKILLS = (
    "trip-init", "trip-1-plan", "trip-2-implement", "trip-3-release", "trip-review",
    "trip-test", "trip-hotfix", "trip-research", "trip-compact", "trip-upgrade",
)
RUNTIME_MODULES = ("plan-review", "implementation", "code-review", "ask")
EXPECTED_PHASE_ROLES = {
    "plan-review": {"start": ["sol-review"], "resume": ["sol-review"]},
    "implementation": {"start": ["sol-implement"], "continue": ["sol-implement"]},
    "code-review": {
        "start": ["sol-review"], "resume": ["sol-review"], "synthesize": ["sol-review"],
        "review-response": ["sol-review"], "fix": ["sol-review"], "final-gate": ["sol-final"],
    },
    "ask": {"start": ["terra"], "follow-up": ["terra"]},
}
EXPECTED_MODULE_PINS = {
    "plan-review": {"model": "gpt-5.6-sol", "effort": "xhigh", "default_role": "sol-review"},
    "implementation": {"model": "gpt-5.6-sol", "effort": "high", "default_role": "sol-implement"},
    "code-review": {"model": "gpt-5.6-sol", "effort": "xhigh", "default_role": "sol-review"},
    "ask": {"model": "gpt-5.6-terra", "effort": "xhigh", "default_role": "terra"},
}
EXECUTABLES = ("install_trip.py", "initialize_trip.py", "launch_runtime.py", "merge_upgrade.py", "apply_upgrade.py", "token_estimate.py", "count-tokens.sh", "validate_installed.py")
PLACEHOLDERS = ("[PROJECT_NAME]", "[VERSION_FILE]", "[MAIN_BRANCH]", "[WEEK_ANCHOR_DATE]", "[WEEK_ANCHOR_EPOCH]", "[LINT_COMMAND]", "[TYPECHECK_COMMAND]", "[TEST_COMMAND]")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def frontmatter(path: Path) -> dict[str, str]:
    text = path.read_text(encoding="utf-8")
    match = re.match(r"^---\n(.*?)\n---\n", text, re.DOTALL)
    if not match:
        raise ValueError("missing YAML frontmatter")
    pairs: dict[str, str] = {}
    for line in match.group(1).splitlines():
        if ":" not in line:
            raise ValueError(f"invalid frontmatter line: {line}")
        name, value = line.split(":", 1)
        pairs[name.strip()] = value.strip().strip('"')
    if set(pairs) != {"name", "description"}:
        raise ValueError("frontmatter must contain only name and description")
    return pairs


def headings(path: Path) -> list[str]:
    return [line.strip() for line in path.read_text(encoding="utf-8").splitlines() if re.match(r"^#{1,3}\s+", line)]


def manifest(root: Path) -> dict[str, object]:
    files = sorted(path for path in root.rglob("*") if path.is_file() and "__pycache__" not in path.parts)
    return {
        "schema": 1,
        "version": (root / "VERSION").read_text(encoding="utf-8").strip(),
        "files": {str(path.relative_to(root)): sha256(path) for path in files},
    }


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("must be an object")
    return value


def validate_parity(root: Path, problems: list[str]) -> None:
    parity_path = root / "references" / "source-parity.json"
    try:
        parity = load_json(parity_path)
        source = parity["authoritative_source"]
        source_root = Path(source["root"])
        source_files = source["files"]
        if not isinstance(source_files, dict) or not source_files:
            raise ValueError("source file manifest is empty")
        if source_root.is_dir():
            for relative, digest in source_files.items():
                path = source_root / relative
                if not path.is_file() or sha256(path) != digest:
                    problems.append(f"authoritative source changed or is incomplete: {relative}")
        public = parity["public_workflows"]
        if set(public) != set(PUBLIC_SKILLS):
            problems.append("source parity map does not cover exactly the ten public workflows")
        for name in PUBLIC_SKILLS:
            item = public[name]
            source_path = source_root / item["source"]
            target_path = root / item["target"]
            if not target_path.is_file():
                problems.append(f"{name}: mapped Codex workflow target is missing")
                continue
            if source_path.is_file() and headings(source_path) != item["source_headings"]:
                problems.append(f"{name}: authoritative source step/phase map drifted")
            if headings(target_path) != item["codex_headings"]:
                problems.append(f"{name}: Codex workflow lost or reordered a mapped source step/phase")
        runtime = parity["private_runtime"]
        if set(runtime) != set(RUNTIME_MODULES):
            problems.append("source parity map does not cover exactly the four private runtime modules")
        for module in RUNTIME_MODULES:
            module_phases = runtime[module]["phases"]
            for phase, item in module_phases.items():
                target = root / item["target"]
                source_path = source_root / item["source"]
                if not target.is_file() or sha256(target) != item["target_sha256"]:
                    problems.append(f"{module}/{phase}: private source-derived phase is missing or altered")
                if source_path.is_file() and sha256(source_path) != item["source_sha256"]:
                    problems.append(f"{module}/{phase}: authoritative private phase drifted")
                if target.is_file() and len(target.read_text(encoding="utf-8").splitlines()) < int(item["source_lines"]):
                    problems.append(f"{module}/{phase}: private phase was collapsed below its source progression")
    except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
        problems.append(f"source parity map: {exc}")


def validate(root: Path) -> list[str]:
    problems: list[str] = []
    version_path = root / "VERSION"
    version = version_path.read_text(encoding="utf-8").strip() if version_path.is_file() else ""
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:[-+][A-Za-z0-9.]+)?", version):
        problems.append("VERSION must contain a semantic version")
    try:
        skill = frontmatter(root / "SKILL.md")
        if skill["name"] != "trip-install" or "TODO" in skill["description"]:
            problems.append("bootstrap SKILL.md name/description is invalid")
        if "launch-runtime.py" in (root / "SKILL.md").read_text(encoding="utf-8"):
            problems.append("bootstrap SKILL.md references the wrong runtime launcher name")
    except (OSError, ValueError) as exc:
        problems.append(f"bootstrap SKILL.md: {exc}")
    expected_yaml = 'interface:\n  display_name: "TRIP Installer"\n  short_description: "Install and upgrade project-local TRIP workflows"\n  default_prompt: "Use $trip-install to install the generic TRIP workflow suite in this project."\n'
    yaml_path = root / "agents" / "openai.yaml"
    if not yaml_path.is_file() or yaml_path.read_text(encoding="utf-8") != expected_yaml:
        problems.append("agents/openai.yaml does not match the deterministic interface metadata")
    template_root = root / "assets" / "templates"
    if {path.name for path in template_root.iterdir() if path.is_dir()} != set(PUBLIC_SKILLS):
        problems.append("templates must contain exactly the ten public skills; helper workflows are private")
    for name in PUBLIC_SKILLS:
        path = template_root / name / "SKILL.md"
        try:
            data = frontmatter(path)
            if data["name"] != name or not data["description"]:
                problems.append(f"{name}: invalid lowercase name or description")
        except (OSError, ValueError) as exc:
            problems.append(f"{name}: {exc}")
    runtime_root = root / "assets" / "runtime"
    if {path.name for path in runtime_root.iterdir() if path.is_dir()} != set(RUNTIME_MODULES):
        problems.append("runtime must contain exactly the four private modules")
    for module in RUNTIME_MODULES:
        try:
            config = load_json(runtime_root / module / "module.json")
            phases = config.get("phases")
            if not isinstance(phases, dict) or not phases:
                problems.append(f"{module}: module config has no phases")
                continue
            if set(phases) != set(EXPECTED_PHASE_ROLES[module]):
                problems.append(f"{module}: phase set does not match the workflow contract")
            expected_pin = EXPECTED_MODULE_PINS[module]
            if any(config.get(key) != value for key, value in expected_pin.items()):
                problems.append(f"{module}: model/effort/default-role pin is invalid")
            for phase, detail in phases.items():
                if not isinstance(detail, dict) or not isinstance(detail.get("roles"), list) or not detail["roles"]:
                    problems.append(f"{module}/{phase}: phase role ownership is invalid")
                elif detail["roles"] != EXPECTED_PHASE_ROLES[module].get(phase):
                    problems.append(f"{module}/{phase}: role ownership does not match the workflow contract")
                if not (runtime_root / module / "phases" / f"{phase}.md").is_file():
                    problems.append(f"{module}/{phase}: phase file is missing")
            if module == "code-review" and config.get("sol_final") != {"effort": "high", "model": "gpt-5.6-sol"}:
                problems.append("code-review: Sol final-gate model/effort pin is invalid")
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            problems.append(f"{module}: invalid module config: {exc}")
    for script in EXECUTABLES:
        path = root / "scripts" / script
        if not path.is_file():
            problems.append(f"missing script: {script}")
        elif not (path.stat().st_mode & stat.S_IXUSR):
            problems.append(f"script is not executable: {script}")
    text_files = [path for path in template_root.rglob("*") if path.is_file()] + [path for path in runtime_root.rglob("*") if path.is_file()]
    all_text = "\n".join(path.read_text(encoding="utf-8") for path in text_files)
    if "[ADAPT_TO_PROJECT" not in all_text or not all(token in all_text for token in PLACEHOLDERS):
        problems.append("generic templates lost required project placeholders")
    runtime_text = "\n".join((runtime_root / module / "module.json").read_text(encoding="utf-8") for module in RUNTIME_MODULES if (runtime_root / module / "module.json").is_file())
    required_pins = ("gpt-5.6-terra", "gpt-5.6-sol", "xhigh", "high")
    if not all(value in runtime_text for value in required_pins):
        problems.append("runtime model pins are incomplete")
    for forbidden in (".claude/", "JellyScope", "state/"):
        if forbidden in all_text:
            problems.append(f"generic templates contain forbidden source-specific text: {forbidden}")
    validate_parity(root, problems)
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--write-manifest", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    problems = validate(root)
    if problems:
        print("\n".join(f"error: {problem}" for problem in problems), file=sys.stderr)
        return 1
    data = manifest(root)
    if args.write_manifest:
        args.write_manifest.parent.mkdir(parents=True, exist_ok=True)
        args.write_manifest.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(data, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
