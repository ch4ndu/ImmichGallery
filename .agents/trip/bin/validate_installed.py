#!/usr/bin/env python3
"""Validate hashes of an installed TRIP suite without modifying the project."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys


PUBLIC_SKILLS = (
    "trip-init", "trip-1-plan", "trip-2-implement", "trip-3-release", "trip-review",
    "trip-test", "trip-hotfix", "trip-research", "trip-compact", "trip-upgrade",
)


def hashes(root: Path) -> dict[str, str]:
    if not root.is_dir():
        return {}
    return {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(root.rglob("*")) if path.is_file()}


def skill_hashes(root: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for skill in PUBLIC_SKILLS:
        result.update({f"{skill}/{relative}": digest for relative, digest in hashes(root / skill).items()})
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, default=Path.cwd())
    args = parser.parse_args()
    project = args.project.resolve()
    trip = project / ".agents" / "trip"
    manifest_path = trip / "manifest.json"
    if not manifest_path.is_file():
        print(f"error: no installed manifest at {manifest_path}", file=sys.stderr)
        return 2
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    current_skills = skill_hashes(project / ".agents" / "skills")
    expected_skills = manifest.get("skills")
    base_skills = hashes(trip / "base" / str(manifest.get("version")) / "skills")
    actual_runtime = hashes(trip / "runtime")
    expected_runtime = manifest.get("runtime")
    actual_bin = hashes(trip / "bin")
    expected_bin = manifest.get("bin")
    customized_files = sorted(path for path in set(current_skills) | set(expected_skills or {}) if current_skills.get(path) != (expected_skills or {}).get(path))
    result = {
        "customized_skill_files": customized_files,
        "generic_base_matches": base_skills == expected_skills,
        "runtime_matches": actual_runtime == expected_runtime,
        "bin_matches": actual_bin == expected_bin,
        "version": manifest.get("version"),
    }
    print(json.dumps(result, sort_keys=True))
    return 0 if result["generic_base_matches"] and result["runtime_matches"] and result["bin_matches"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
