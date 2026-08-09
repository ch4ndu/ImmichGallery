#!/usr/bin/env python3
"""Transactionally install the public TRIP skills or stage a safe upgrade."""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
from datetime import datetime, timezone
from typing import Iterator

PUBLIC_SKILLS = (
    "trip-init", "trip-1-plan", "trip-2-implement", "trip-3-release", "trip-review",
    "trip-test", "trip-hotfix", "trip-research", "trip-compact", "trip-upgrade",
)


def copytree(source: Path, destination: Path) -> None:
    shutil.copytree(source, destination, copy_function=shutil.copy2)


def file_hashes(root: Path) -> dict[str, str]:
    return {
        str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted(root.rglob("*")) if path.is_file()
    }


def load_module(script: Path):
    import importlib.util
    spec = importlib.util.spec_from_file_location("trip_validate", script)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def package_root() -> Path:
    return Path(__file__).resolve().parents[1]


@contextmanager
def install_lock(project: Path) -> Iterator[None]:
    """Serialize fresh installs and upgrade staging without creating mutable suite state."""
    path = project / ".local" / "trip" / "install-upgrade.lock"
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a+") as handle:
        try:
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise RuntimeError(f"another TRIP install or upgrade staging operation is active: {path}") from exc
        try:
            yield
        finally:
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def stage_upgrade(root: Path, project: Path, version: str) -> Path:
    staging = project / ".local" / "trip" / "upgrade" / version
    if staging.exists():
        raise RuntimeError(f"upgrade staging already exists: {staging}")
    staging.mkdir(parents=True)
    copytree(root / "assets" / "templates", staging / "new-base" / "skills")
    copytree(root / "assets" / "runtime", staging / "new-base" / "runtime")
    copytree(root / "scripts", staging / "new-base" / "bin")
    (staging / "handoff.json").write_text(json.dumps({
        "action": "invoke $trip-upgrade; create a proposal, resolve conflicts, then run apply_upgrade.py --approve",
        "current_skills": str(project / ".agents" / "skills"),
        "new_base": str(staging / "new-base"),
        "version": version,
    }, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return staging


def fresh_install(root: Path, project: Path, version: str) -> Path:
    skills_target = project / ".agents" / "skills"
    trip_target = project / ".agents" / "trip"
    existing = [name for name in PUBLIC_SKILLS if (skills_target / name).exists()]
    if existing or trip_target.exists():
        raise RuntimeError("fresh install target appeared during staging; refusing to overwrite")
    local_root = project / ".local" / "trip" / "install"
    local_root.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=f"{version}-", dir=local_root) as temp:
        staging = Path(temp)
        staged_skills = staging / "skills"
        staged_trip = staging / "trip"
        copytree(root / "assets" / "templates", staged_skills)
        copytree(root / "assets" / "runtime", staged_trip / "runtime")
        copytree(root / "scripts", staged_trip / "bin")
        (staged_trip / "base" / version).mkdir(parents=True)
        copytree(root / "assets" / "templates", staged_trip / "base" / version / "skills")
        copytree(root / "assets" / "runtime", staged_trip / "base" / version / "runtime")
        copytree(root / "scripts", staged_trip / "base" / version / "bin")
        manifest = {
            "installed_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
            "schema": 1,
            "version": version,
            "skills": file_hashes(staged_skills),
            "runtime": file_hashes(staged_trip / "runtime"),
            "bin": file_hashes(staged_trip / "bin"),
        }
        (staged_trip / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        validator = load_module(root / "scripts" / "validate_package.py")
        errors = validator.validate(root)
        if errors:
            raise RuntimeError("package validation failed: " + "; ".join(errors))
        skills_target.mkdir(parents=True, exist_ok=True)
        moved: list[Path] = []
        try:
            for name in PUBLIC_SKILLS:
                target = skills_target / name
                if target.exists():
                    raise RuntimeError(f"refusing to overwrite existing skill: {target}")
                os.replace(staged_skills / name, target)
                moved.append(target)
            if trip_target.exists():
                raise RuntimeError(f"refusing to overwrite existing runtime: {trip_target}")
            os.replace(staged_trip, trip_target)
        except Exception:
            for target in reversed(moved):
                if target.exists():
                    shutil.rmtree(target)
            raise
    return trip_target / "manifest.json"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, default=Path.cwd())
    args = parser.parse_args()
    root = package_root()
    project = args.project.resolve()
    if not (project / ".git").exists():
        print(f"error: project root must contain .git: {project}", file=sys.stderr)
        return 2
    version = (root / "VERSION").read_text(encoding="utf-8").strip()
    try:
        with install_lock(project):
            existing = any((project / ".agents" / "skills" / name).exists() for name in PUBLIC_SKILLS) or (project / ".agents" / "trip" / "manifest.json").is_file()
            if existing:
                staging = stage_upgrade(root, project, version)
                print(json.dumps({"result": "upgrade-staged", "staging": str(staging)}, sort_keys=True))
                return 3
            manifest = fresh_install(root, project, version)
    except RuntimeError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps({"manifest": str(manifest), "result": "installed", "version": version}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
