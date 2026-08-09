#!/usr/bin/env python3
"""Transactionally apply an approved, conflict-free TRIP upgrade proposal."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
from datetime import datetime, timezone

from install_trip import PUBLIC_SKILLS, file_hashes, install_lock

LEGACY_PUBLIC_NAMES = {"trip-review": "trip-3-review", "trip-test": "trip-4-test"}


def validate_merged(merged: Path) -> None:
    roots = {path.name for path in merged.iterdir()} if merged.is_dir() else set()
    unexpected = sorted(roots - set(PUBLIC_SKILLS))
    missing = sorted(set(PUBLIC_SKILLS) - roots)
    if unexpected or missing:
        raise RuntimeError(f"proposal must contain only the ten suite-owned skills; missing={missing}, unexpected={unexpected}")
    for name in PUBLIC_SKILLS:
        skill = merged / name / "SKILL.md"
        if not skill.is_file():
            raise RuntimeError(f"proposal is missing {name}/SKILL.md")
    for path in merged.rglob("*"):
        if path.is_symlink():
            raise RuntimeError(f"proposal must not contain symlinks: {path}")
        if not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError as exc:
            raise RuntimeError(f"proposal file is not valid UTF-8: {path}") from exc
        if "<<<<<<< " in text or "=======\n" in text or ">>>>>>> " in text:
            raise RuntimeError(f"proposal has unresolved conflict markers: {path}")


def manifest_for(version: str, skills: Path, runtime: Path, bin_dir: Path) -> dict[str, object]:
    return {
        "installed_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "schema": 1,
        "version": version,
        "skills": file_hashes(skills),
        "runtime": file_hashes(runtime),
        "bin": file_hashes(bin_dir),
    }


def copytree(source: Path, destination: Path) -> None:
    shutil.copytree(source, destination, copy_function=shutil.copy2)


def apply(project: Path, proposal: Path, approved: bool) -> Path:
    if not approved:
        raise RuntimeError("refusing to apply an upgrade without explicit --approve")
    conflicts_path = proposal / "conflicts.json"
    if not conflicts_path.is_file():
        raise RuntimeError("proposal conflicts.json is missing")
    try:
        conflicts = json.loads(conflicts_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise RuntimeError("proposal conflicts.json is invalid") from exc
    if conflicts.get("conflicts"):
        raise RuntimeError("refusing to apply an upgrade with unresolved conflicts")
    merged = proposal / "merged"
    validate_merged(merged)
    staging = proposal.parent
    handoff = staging / "handoff.json"
    if not handoff.is_file():
        raise RuntimeError("upgrade handoff metadata is missing")
    data = json.loads(handoff.read_text(encoding="utf-8"))
    version = data.get("version")
    if not isinstance(version, str) or not version:
        raise RuntimeError("upgrade handoff has no target version")
    new_base = staging / "new-base"
    for name in ("skills", "runtime", "bin"):
        if not (new_base / name).is_dir():
            raise RuntimeError(f"upgrade staging is missing new-base/{name}")
    current_trip = project / ".agents" / "trip"
    skills_root = project / ".agents" / "skills"
    if not current_trip.is_dir():
        raise RuntimeError("installed runtime is missing")
    current_paths: dict[str, Path] = {}
    for name in PUBLIC_SKILLS:
        current = skills_root / name
        legacy = skills_root / LEGACY_PUBLIC_NAMES[name] if name in LEGACY_PUBLIC_NAMES else None
        if current.is_dir() and legacy is not None and legacy.is_dir():
            raise RuntimeError(f"installed suite has both current and legacy names for {name}; resolve the ambiguity first")
        if not current.is_dir() and legacy is not None and legacy.is_dir():
            current = legacy
        if not current.is_dir():
            raise RuntimeError(f"installed suite is missing expected owned skill: {name}")
        current_paths[name] = current
    local_root = project / ".local" / "trip" / "upgrade" / version
    with tempfile.TemporaryDirectory(prefix="apply-", dir=local_root) as raw:
        work = Path(raw)
        next_skills = work / "skills"
        next_trip = work / "trip"
        copytree(merged, next_skills)
        copytree(current_trip, next_trip)
        shutil.rmtree(next_trip / "runtime")
        shutil.rmtree(next_trip / "bin")
        copytree(new_base / "runtime", next_trip / "runtime")
        copytree(new_base / "bin", next_trip / "bin")
        base_version = next_trip / "base" / version
        if base_version.exists():
            shutil.rmtree(base_version)
        copytree(new_base / "skills", base_version / "skills")
        copytree(new_base / "runtime", base_version / "runtime")
        copytree(new_base / "bin", base_version / "bin")
        manifest = manifest_for(version, new_base / "skills", next_trip / "runtime", next_trip / "bin")
        (next_trip / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        backup = work / "backup"
        moved_skills: list[tuple[str, Path, Path]] = []
        trip_moved = False
        try:
            for name in PUBLIC_SKILLS:
                old = current_paths[name]
                target = skills_root / name
                saved = backup / "skills" / old.name
                saved.parent.mkdir(parents=True, exist_ok=True)
                os.replace(old, saved)
                moved_skills.append((name, old, saved))
                os.replace(next_skills / name, target)
            os.replace(current_trip, backup / "trip")
            trip_moved = True
            os.replace(next_trip, current_trip)
        except Exception as exc:
            if current_trip.exists() and trip_moved:
                shutil.rmtree(current_trip)
            if trip_moved and (backup / "trip").exists():
                os.replace(backup / "trip", current_trip)
            for name, old, saved in reversed(moved_skills):
                target = skills_root / name
                if target.exists():
                    shutil.rmtree(target)
                if saved.exists():
                    os.replace(saved, old)
            raise RuntimeError(f"upgrade apply failed and was rolled back: {exc}") from exc
    return current_trip / "manifest.json"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, default=Path.cwd())
    parser.add_argument("--proposal", type=Path, required=True)
    parser.add_argument("--approve", action="store_true")
    args = parser.parse_args()
    project = args.project.resolve()
    try:
        with install_lock(project):
            manifest = apply(project, args.proposal.resolve(), args.approve)
        print(json.dumps({"manifest": str(manifest), "result": "upgrade-applied"}, sort_keys=True))
        return 0
    except RuntimeError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
