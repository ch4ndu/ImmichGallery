#!/usr/bin/env python3
"""Offline token estimates and explicit exact Responses input-token counting."""
from __future__ import annotations

import argparse
import json
import math
import os
from pathlib import Path
import sys
import urllib.error
import urllib.request


def estimate_text(text: str, utf8_bytes: int, suffix: str) -> tuple[int, int, int]:
    """Return weighted markdown estimate, byte estimate, and accepted selected estimate."""
    byte_estimate = math.ceil(utf8_bytes / 4)
    if suffix.lower() in {".md", ".markdown", ".mdx"}:
        words = len(text.split())
        headings = sum(1 for line in text.splitlines() if line.lstrip().startswith("#"))
        fences = text.count("```") // 2
        weighted = math.ceil(words * 1.28 + headings * 2 + fences * 4)
    else:
        weighted = math.ceil(len(text.split()) * 1.28)
    selected = math.ceil(max(weighted, byte_estimate))
    return weighted, byte_estimate, max(1, selected) if text else 0


def read_utf8(path: Path) -> tuple[bytes, str]:
    data = path.read_bytes()
    return data, data.decode("utf-8", errors="strict")


def exact_count(text: str, model: str) -> int:
    key = os.environ.get("OPENAI_API_KEY")
    if not key:
        raise RuntimeError("exact mode requires OPENAI_API_KEY")
    if not model:
        raise RuntimeError("exact mode requires --model")
    body = json.dumps({"model": model, "input": text}).encode("utf-8")
    request = urllib.request.Request(
        "https://api.openai.com/v1/responses/input_tokens",
        data=body,
        method="POST",
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = json.load(response)
    except urllib.error.URLError as exc:
        raise RuntimeError(f"exact token count failed: {exc}") from exc
    count = payload.get("input_tokens")
    if not isinstance(count, int) or count < 0:
        raise RuntimeError("exact token response did not contain a valid input_tokens value")
    return count


def file_metrics(path: Path, exact_model: str | None) -> dict[str, object]:
    data, text = read_utf8(path)
    weighted, byte_estimate, estimate = estimate_text(text, len(data), path.suffix)
    payload: dict[str, object] = {
        "byte_estimate": byte_estimate,
        "lines": len(text.splitlines()),
        "path": str(path),
        "unicode_characters": len(text),
        "utf8_bytes": len(data),
        "weighted_markdown_estimate": weighted,
        "words": len(text.split()),
    }
    if exact_model is None:
        payload["tokens"] = estimate
    else:
        payload["tokens"] = exact_count(text, exact_model)
    return payload


def totals(items: list[dict[str, object]]) -> dict[str, int]:
    keys = ("tokens", "lines", "words", "unicode_characters", "utf8_bytes", "weighted_markdown_estimate", "byte_estimate")
    return {key: sum(int(item[key]) for item in items) for key in keys}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path, nargs="+")
    parser.add_argument("--json", action="store_true", dest="as_json")
    parser.add_argument("--exact", action="store_true")
    parser.add_argument("--model")
    args = parser.parse_args()
    if args.exact and not args.model:
        print("error: exact mode requires --model", file=sys.stderr)
        return 2
    results: list[dict[str, object]] = []
    errors: list[dict[str, str]] = []
    for path in args.path:
        if not path.is_file():
            errors.append({"path": str(path), "error": "not a file"})
            continue
        try:
            results.append(file_metrics(path, args.model if args.exact else None))
        except (OSError, UnicodeDecodeError, RuntimeError) as exc:
            errors.append({"path": str(path), "error": str(exc)})
    payload: dict[str, object] = {
        "errors": errors,
        "files": results,
        "mode": "exact" if args.exact else "estimate",
        "totals": totals(results),
    }
    if args.exact:
        payload["model"] = args.model
    if args.as_json:
        print(json.dumps(payload, sort_keys=True, separators=(",", ":")))
    else:
        for item in results:
            print(f"{item['path']}: {item['lines']} lines, {item['words']} words, {item['unicode_characters']} Unicode characters, {item['utf8_bytes']} UTF-8 bytes, ~{item['tokens']} tokens")
        for error in errors:
            print(f"error: {error['path']}: {error['error']}", file=sys.stderr)
        if len(results) > 1:
            summary = payload["totals"]
            print(f"total: {summary['lines']} lines, {summary['words']} words, {summary['unicode_characters']} Unicode characters, {summary['utf8_bytes']} UTF-8 bytes, ~{summary['tokens']} tokens")
    if errors:
        return 2 if args.exact else 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
