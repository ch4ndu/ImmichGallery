#!/usr/bin/env python3
"""Remove ignored per-target TRIP runtime artifacts after a completed release."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import shutil


MODULES = ("plan-review", "implementation", "code-review", "ask")


def target_key(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "_", value).strip("_") or "target"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, default=Path.cwd())
    parser.add_argument("--target", required=True)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    project = args.project.resolve()
    runtime = project / ".local" / "trip" / "runtime"
    if not runtime.is_dir():
        parser.error(f"runtime directory does not exist: {runtime}")

    key = target_key(args.target)
    candidates = [runtime / "invocations" / module / key for module in MODULES]
    candidates += [runtime / "threads" / module / f"{key}.txt" for module in MODULES]
    removed: list[str] = []

    for candidate in candidates:
        if not candidate.exists():
            continue
        candidate.relative_to(runtime)
        removed.append(str(candidate.relative_to(project)))
        if args.dry_run:
            continue
        if candidate.is_dir():
            shutil.rmtree(candidate)
        else:
            candidate.unlink()

    print(json.dumps({"dry_run": args.dry_run, "removed": removed}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
