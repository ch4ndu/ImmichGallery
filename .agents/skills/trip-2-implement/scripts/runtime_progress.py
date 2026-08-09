#!/usr/bin/env python3
"""Summarize new status events from a live Codex JSONL invocation stream."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


def compact(value: str, limit: int = 240) -> str:
    normalized = " ".join(value.split())
    return normalized if len(normalized) <= limit else normalized[: limit - 1] + "…"


def summarize(payload: dict[str, Any]) -> dict[str, str] | None:
    event_type = payload.get("type")
    if event_type in {"thread.started", "turn.started", "turn.completed", "turn.failed"}:
        return {"kind": str(event_type), "message": str(event_type).replace(".", " ")}

    item = payload.get("item")
    if not isinstance(item, dict):
        return None
    item_type = item.get("type")
    status = item.get("status")

    if item_type in {"agent_message", "reasoning"} and status != "in_progress":
        body = item.get("text")
        if isinstance(body, str) and body.strip():
            return {"kind": str(item_type), "message": compact(body)}

    if item_type == "command_execution":
        command = item.get("command")
        if not isinstance(command, str) or not command.strip():
            return None
        if status == "in_progress":
            return {"kind": "command_started", "message": compact(command)}
        exit_code = item.get("exit_code")
        return {
            "kind": "command_completed",
            "message": f"exit={exit_code}: {compact(command)}",
        }

    if item_type == "todo_list":
        todos = item.get("items")
        if isinstance(todos, list):
            completed = sum(
                1 for todo in todos if isinstance(todo, dict) and todo.get("completed") is True
            )
            return {
                "kind": "todo_progress",
                "message": f"{completed}/{len(todos)} delegated tasks complete",
            }
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("events", type=Path)
    parser.add_argument("--after", type=int, default=0, help="One-based cursor returned by the previous call")
    args = parser.parse_args()

    if args.after < 0:
        parser.error("--after must be non-negative")
    if not args.events.is_file():
        parser.error(f"events file does not exist: {args.events}")

    updates: list[dict[str, object]] = []
    cursor = 0
    with args.events.open(encoding="utf-8", errors="replace") as stream:
        for cursor, line in enumerate(stream, start=1):
            if cursor <= args.after:
                continue
            try:
                payload = json.loads(line)
            except json.JSONDecodeError:
                continue
            if not isinstance(payload, dict):
                continue
            summary = summarize(payload)
            if summary is not None:
                updates.append({"line": cursor, **summary})

    print(json.dumps({"cursor": cursor, "updates": updates}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
