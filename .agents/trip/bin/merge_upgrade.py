#!/usr/bin/env python3
"""Produce a non-destructive three-way TRIP upgrade proposal."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import shutil
import sys

LEGACY_NAMES = {"trip-3-review": "trip-review", "trip-4-test": "trip-test"}
PUBLIC_SKILLS = {
    "trip-init", "trip-1-plan", "trip-2-implement", "trip-3-release", "trip-review",
    "trip-test", "trip-hotfix", "trip-research", "trip-compact", "trip-upgrade",
}


def files(root: Path) -> dict[str, Path]:
    if not root.is_dir():
        return {}
    return {str(path.relative_to(root)): path for path in root.rglob("*") if path.is_file()}


def text_or_none(path: Path | None) -> str | None:
    return path.read_text(encoding="utf-8") if path else None


def merge_file(base: str | None, current: str | None, new: str | None) -> tuple[str | None, bool]:
    if current == base:
        return new, False
    if new == base or current == new:
        return current, False
    if base is None and current is None:
        return new, False
    if base is None and new is None:
        return current, False
    return "".join(("<<<<<<< current\n", current or "", "=======\n", new or "", ">>>>>>> new-generic-base\n")), True


def canonical_tree(root: Path) -> dict[str, Path]:
    result: dict[str, Path] = {}
    for relative, path in files(root).items():
        parts = relative.split("/", 1)
        mapped = LEGACY_NAMES.get(parts[0], parts[0])
        if mapped not in PUBLIC_SKILLS:
            continue
        result["/".join((mapped, *parts[1:])) if len(parts) == 2 else mapped] = path
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, required=True, help="previous generic base skills directory")
    parser.add_argument("--current", type=Path, required=True, help="customized installed skills directory")
    parser.add_argument("--new-base", type=Path, required=True, help="new generic base skills directory")
    parser.add_argument("--output", type=Path, required=True, help="empty proposal directory")
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists():
        print(f"error: output already exists: {output}", file=sys.stderr)
        return 2
    base = canonical_tree(args.base.resolve())
    current = canonical_tree(args.current.resolve())
    new = canonical_tree(args.new_base.resolve())
    if not current or not new:
        print("error: current and new-base must contain skills", file=sys.stderr)
        return 2
    output.mkdir(parents=True)
    conflicts: list[str] = []
    for relative in sorted(set(base) | set(current) | set(new)):
        merged, conflicted = merge_file(text_or_none(base.get(relative)), text_or_none(current.get(relative)), text_or_none(new.get(relative)))
        if merged is None:
            continue
        target = output / "merged" / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(merged, encoding="utf-8")
        if conflicted:
            conflicts.append(relative)
    # Copy newly added non-text assets exactly only when no current asset exists.
    for relative, path in new.items():
        if relative not in current and relative not in base and path.suffix not in {".md", ".json", ".txt", ".sh", ".py", ".yaml"}:
            target = output / "merged" / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)
    report = {
        "conflicts": conflicts,
        "legacy_renames": LEGACY_NAMES,
        "result": "conflicts" if conflicts else "proposal-ready",
        "writes_current": False,
    }
    (output / "conflicts.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(report, sort_keys=True))
    return 1 if conflicts else 0


if __name__ == "__main__":
    raise SystemExit(main())
