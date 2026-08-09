#!/usr/bin/env python3
"""Launch pinned private TRIP runtime phases and verify persisted Codex metadata."""
from __future__ import annotations

import argparse
from contextlib import ExitStack, contextmanager
import datetime as dt
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid
from typing import Any, Iterator

ROLE_PINS = {
    "terra": {"model": "gpt-5.6-terra", "effort": "xhigh"},
    "sol-review": {"model": "gpt-5.6-sol", "effort": "xhigh"},
    "sol-implement": {"model": "gpt-5.6-sol", "effort": "high"},
    "sol-final": {"model": "gpt-5.6-sol", "effort": "high"},
}
MODULES = {"plan-review", "implementation", "code-review", "ask"}


def codex_executable(explicit: Path | None) -> str:
    candidates = [
        explicit,
        Path(os.environ["TRIP_CODEX_BIN"]) if os.environ.get("TRIP_CODEX_BIN") else None,
        Path("/Applications/ChatGPT.app/Contents/Resources/codex"),
        Path(shutil.which("codex")) if shutil.which("codex") else None,
    ]
    for candidate in candidates:
        if candidate is not None and candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate)
    raise RuntimeError("no executable Codex CLI found; pass --codex or set TRIP_CODEX_BIN")


def key(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "_", value).strip("_") or "target"


def read_json_lines(path: Path) -> Iterator[dict[str, Any]]:
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        try:
            value = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict):
            yield value


def event_thread(events: Path) -> str | None:
    for payload in read_json_lines(events):
        if payload.get("type") == "thread.started" and isinstance(payload.get("thread_id"), str):
            return payload["thread_id"]
    return None


def usage(events: Path) -> dict[str, int] | None:
    """Keep all reported categories separate; cached/reasoning are never summed twice."""
    totals = {"input_tokens": 0, "cached_input_tokens": 0, "output_tokens": 0, "reasoning_output_tokens": 0}
    found = False
    aliases = {
        "input_tokens": ("input_tokens", "input"),
        "cached_input_tokens": ("cached_input_tokens", "cached_input"),
        "output_tokens": ("output_tokens", "output"),
        "reasoning_output_tokens": ("reasoning_output_tokens", "reasoning"),
    }
    for event in read_json_lines(events):
        if event.get("type") != "turn.completed" or not isinstance(event.get("usage"), dict):
            continue
        data = event["usage"]
        for destination, candidates in aliases.items():
            value = next((data[name] for name in candidates if isinstance(data.get(name), int)), 0)
            totals[destination] += value
        found = True
    return totals if found else None


def rollout_files(thread_id: str) -> list[Path]:
    codex_home = Path(os.environ.get("CODEX_HOME") or (Path.home() / ".codex"))
    sessions = codex_home / "sessions"
    if not sessions.is_dir():
        return []
    return sorted(sessions.glob(f"**/rollout-*-{thread_id}.jsonl"), key=lambda path: path.stat().st_mtime, reverse=True)


def context_effort(payload: dict[str, Any]) -> str | None:
    for name in ("effort", "model_reasoning_effort", "reasoning_effort"):
        value = payload.get(name)
        if isinstance(value, str):
            return value
    collaboration = payload.get("collaboration")
    if isinstance(collaboration, dict):
        for name in ("reasoning_effort", "effort"):
            value = collaboration.get(name)
            if isinstance(value, str):
                return value
    return None


def verify_rollout(thread_id: str, pin: dict[str, str]) -> dict[str, object]:
    """Read the latest persisted turn_context for the captured thread, fail closed."""
    for candidate in rollout_files(thread_id):
        contexts = [item.get("payload") for item in read_json_lines(candidate) if item.get("type") == "turn_context"]
        payload = next((item for item in reversed(contexts) if isinstance(item, dict)), None)
        if payload is None:
            continue
        observed_model = payload.get("model") if isinstance(payload.get("model"), str) else None
        observed_effort = context_effort(payload)
        verified = observed_model == pin["model"] and observed_effort == pin["effort"]
        return {
            "expected_model": pin["model"],
            "expected_effort": pin["effort"],
            "model": observed_model,
            "effort": observed_effort,
            "source_path": str(candidate),
            "verification": "persisted-session-turn_context",
            "verified": verified,
        }
    return {
        "expected_model": pin["model"],
        "expected_effort": pin["effort"],
        "model": None,
        "effort": None,
        "source_path": None,
        "verification": "unavailable",
        "verified": False,
    }


@contextmanager
def lock(path: Path) -> Iterator[None]:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a+") as handle:
        try:
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise RuntimeError(f"target/path lock is already held: {path}") from exc
        try:
            yield
        finally:
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


@contextmanager
def blocking_lock(path: Path) -> Iterator[None]:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a+") as handle:
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


@contextmanager
def locks(paths: list[Path]) -> Iterator[None]:
    with ExitStack() as stack:
        for path in sorted(paths):
            stack.enter_context(lock(path))
        yield


def append_ledger(path: Path, item: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(item, sort_keys=True) + "\n")


def normalize_ownership(project: Path, values: list[str]) -> list[str]:
    result: set[str] = set()
    for value in values:
        for part in value.split(","):
            raw = part.strip()
            if not raw:
                continue
            resolved = (project / raw).resolve() if not Path(raw).is_absolute() else Path(raw).resolve()
            try:
                relative = resolved.relative_to(project)
            except ValueError as exc:
                raise RuntimeError(f"owned path must stay inside the project: {raw}") from exc
            normalized = relative.as_posix()
            if normalized in {"", "."}:
                raise RuntimeError("owned path must be narrower than the project root")
            result.add(normalized)
    return sorted(result)


def paths_overlap(left: str, right: str) -> bool:
    return left == right or left.startswith(right + "/") or right.startswith(left + "/")


def pid_alive(pid: object) -> bool:
    if not isinstance(pid, int) or pid <= 0:
        return False
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def write_registry(path: Path, entries: list[dict[str, object]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{os.getpid()}.{uuid.uuid4().hex}.tmp")
    temporary.write_text(json.dumps(entries, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(temporary, path)


@contextmanager
def ownership_reservation(runtime: Path, ownership: list[str], target: str) -> Iterator[None]:
    if not ownership:
        yield
        return
    registry = runtime / "locks" / "active-ownership.json"
    registry_guard = runtime / "locks" / "ownership-registry.lock"
    token = uuid.uuid4().hex
    with blocking_lock(registry_guard):
        try:
            loaded = json.loads(registry.read_text(encoding="utf-8")) if registry.is_file() else []
        except json.JSONDecodeError as exc:
            raise RuntimeError(f"ownership registry is invalid: {registry}") from exc
        entries = [entry for entry in loaded if isinstance(entry, dict) and pid_alive(entry.get("pid"))]
        conflicts: list[str] = []
        for entry in entries:
            other_paths = entry.get("paths")
            if not isinstance(other_paths, list):
                continue
            for owned in ownership:
                for other in other_paths:
                    if isinstance(other, str) and paths_overlap(owned, other):
                        conflicts.append(f"{owned} overlaps {other} held by {entry.get('target', 'another target')}")
        if conflicts:
            raise RuntimeError("owned path reservation conflicts: " + "; ".join(sorted(set(conflicts))))
        entries.append({"paths": ownership, "pid": os.getpid(), "target": target, "token": token})
        write_registry(registry, entries)
    try:
        yield
    finally:
        with blocking_lock(registry_guard):
            try:
                loaded = json.loads(registry.read_text(encoding="utf-8")) if registry.is_file() else []
            except json.JSONDecodeError:
                loaded = []
            remaining = [entry for entry in loaded if not isinstance(entry, dict) or entry.get("token") != token]
            write_registry(registry, remaining)


def module_phase(project: Path, module: str, phase: str, role: str, target: str, extra: str) -> str:
    config_path = project / ".agents" / "trip" / "runtime" / module / "module.json"
    if not config_path.is_file():
        raise RuntimeError(f"private runtime module is missing: {config_path}")
    try:
        config = json.loads(config_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"runtime module config is invalid: {config_path}") from exc
    phases = config.get("phases")
    if not isinstance(phases, dict) or phase not in phases:
        raise RuntimeError(f"unsupported phase for {module}: {phase}")
    allowed_roles = phases[phase].get("roles") if isinstance(phases[phase], dict) else None
    if not isinstance(allowed_roles, list) or role not in allowed_roles:
        raise RuntimeError(f"phase {module}/{phase} is not owned by role {role}")
    phase_path = project / ".agents" / "trip" / "runtime" / module / "phases" / f"{phase}.md"
    if not phase_path.is_file():
        raise RuntimeError(f"private runtime phase is missing: {phase_path}")
    prompt = phase_path.read_text(encoding="utf-8")
    prompt = prompt.replace("{{TARGET}}", target)
    if "{{IMPLEMENTER_NOTES}}" in prompt:
        return prompt.replace("{{IMPLEMENTER_NOTES}}", extra).replace("{{EXTRA_PROMPT}}", "")
    return prompt.replace("{{EXTRA_PROMPT}}", extra)


def record_result(path: Path, ledger: Path, result: str, record: dict[str, object]) -> None:
    path.write_text(json.dumps(record, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    append_ledger(ledger, {"result": result, **record})


def run(args: argparse.Namespace) -> int:
    if args.module not in MODULES:
        raise RuntimeError(f"unknown runtime module: {args.module}")
    pin = ROLE_PINS[args.role]
    codex_bin = codex_executable(args.codex)
    if args.model and args.model != pin["model"]:
        raise RuntimeError(f"model override rejected; {args.role} requires {pin['model']}")
    if args.effort and args.effort != pin["effort"]:
        raise RuntimeError(f"effort override rejected; {args.role} requires {pin['effort']}")
    if args.round < 1 or args.round > 5:
        raise RuntimeError("round must be from 1 through 5")
    project = args.project.resolve()
    runtime = project / ".local" / "trip" / "runtime"
    target_key = key(args.target)
    invocation = runtime / "invocations" / args.module / target_key / key(args.phase) / f"round-{args.round:02d}"
    ownership = normalize_ownership(project, args.owns)
    if (args.module == "implementation" or args.phase == "fix") and not ownership:
        raise RuntimeError("write-capable launches require at least one --owns path")
    extra = args.prompt or ""
    if args.prompt_file:
        extra = Path(args.prompt_file).read_text(encoding="utf-8")
    prompt = module_phase(project, args.module, args.phase, args.role, args.target, extra)
    events = invocation / "events.jsonl"
    final = invocation / "final.txt"
    stderr = invocation / "stderr.txt"
    metadata = invocation / "metadata.json"
    thread_path = runtime / "threads" / args.module / f"{target_key}.txt"
    lock_paths = [runtime / "locks" / f"target-{target_key}.lock"]
    with locks(lock_paths), ownership_reservation(runtime, ownership, args.target):
        if invocation.exists():
            raise RuntimeError(f"invocation already exists: {invocation}")
        if args.resume:
            if not thread_path.is_file():
                raise RuntimeError("cannot resume before a thread id was captured")
            thread_id = thread_path.read_text(encoding="utf-8").strip()
            if not thread_id:
                raise RuntimeError("stored thread id is empty")
            command = [codex_bin, "-a", "never", "exec", "resume", thread_id, "--skip-git-repo-check", "--json", "-m", pin["model"], "-c", f'model_reasoning_effort="{pin["effort"]}"', "-o", str(final), prompt]
        else:
            sandbox = "workspace-write" if args.module == "implementation" or args.phase == "fix" else "read-only"
            command = [codex_bin, "-a", "never", "exec", "--json", "--skip-git-repo-check", "--sandbox", sandbox, "--color", "never", "-m", pin["model"], "-c", f'model_reasoning_effort="{pin["effort"]}"', "-o", str(final), "-C", str(project), prompt]
            thread_id = ""
        invocation.mkdir(parents=True)
        record: dict[str, object] = {
            "command": command[:-1] + ["<rendered-phase-prompt>"],
            "module": args.module,
            "ownership": ownership,
            "phase": args.phase,
            "role": args.role,
            "round": args.round,
            "selected": {"model": pin["model"], "effort": pin["effort"], "verified": False, "verification": "pending"},
            "target": args.target,
            "started_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        }
        if args.dry_run:
            record_result(metadata, runtime / "ledger.jsonl", "dry-run", record)
            print(json.dumps({"dry_run": True, **record}, sort_keys=True))
            return 0
        with events.open("w", encoding="utf-8") as stdout, stderr.open("w", encoding="utf-8") as err:
            completed = subprocess.run(command, cwd=project, stdin=subprocess.DEVNULL, stdout=stdout, stderr=err, text=True, check=False)
        record["exit_code"] = completed.returncode
        record["ended_at"] = dt.datetime.now(dt.timezone.utc).isoformat()
        if completed.returncode != 0:
            record_result(metadata, runtime / "ledger.jsonl", "failed", record)
            raise RuntimeError(f"codex launch failed; inspect {stderr}")
        if not args.resume:
            thread_id = event_thread(events) or ""
            if not thread_id:
                record_result(metadata, runtime / "ledger.jsonl", "failed", record)
                raise RuntimeError(f"no thread.started event captured in {events}")
            thread_path.parent.mkdir(parents=True, exist_ok=True)
            thread_path.write_text(thread_id + "\n", encoding="utf-8")
        record["thread_id"] = thread_id
        record["usage"] = usage(events)
        selected = verify_rollout(thread_id, pin)
        record["selected"] = selected
        if not selected["verified"]:
            record_result(metadata, runtime / "ledger.jsonl", "unverified", record)
            raise RuntimeError("persisted Codex session model/effort verification failed; inspect metadata")
        record_result(metadata, runtime / "ledger.jsonl", "completed", record)
    print(json.dumps({"final": str(final), "metadata": str(metadata), "thread_id": thread_id, "usage": record["usage"]}, sort_keys=True))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    start = subparsers.add_parser("run")
    start.add_argument("--project", type=Path, default=Path.cwd())
    start.add_argument("--module", required=True)
    start.add_argument("--phase", required=True)
    start.add_argument("--role", choices=tuple(ROLE_PINS), default="terra")
    start.add_argument("--target", required=True)
    start.add_argument("--round", type=int, default=1)
    start.add_argument("--owns", action="append", default=[])
    start.add_argument("--prompt")
    start.add_argument("--prompt-file")
    start.add_argument("--resume", action="store_true")
    start.add_argument("--model")
    start.add_argument("--effort")
    start.add_argument("--dry-run", action="store_true")
    start.add_argument("--codex", type=Path, help="Codex CLI path; defaults to TRIP_CODEX_BIN, ChatGPT.app, then PATH")
    usage_parser = subparsers.add_parser("usage")
    usage_parser.add_argument("events", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "usage":
            result = usage(args.events)
            print(json.dumps({"usage": result}, sort_keys=True))
            return 0 if result is not None else 3
        return run(args)
    except RuntimeError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
