# ImmichGallery Agent Instructions

ImmichGallery is a Kotlin Multiplatform Compose read-only gallery client for self-hosted Immich photo servers. It uses the Immich API, authenticated Coil image loading, Room cache tables, Koin DI, and justified photo grids across Android, iOS, and JVM Desktop.

Read this file first. Do not eagerly load every AI instruction document. Load only the files below that match the task.

## Load Only When Needed

| Task                                      | Load                                |
|-------------------------------------------|-------------------------------------|
| Architecture, source layout, data flow    | `docs/ai/architecture.md`           |
| Feature implementation, bug fixes, refactors | `docs/ai/feature-implementation.md` |
| Code review or architecture audit         | `docs/ai/audit.md`                  |
| UI, Compose, previews, recomposition      | `docs/ai/ui.md`                     |
| Room cache, repositories, Immich API, time | `docs/ai/data-cache-time.md`        |

## Always-On Rules

- Read existing code before editing; follow local patterns over inventing new ones.
- Prefer `commonMain`; use platform code only when necessary.
- Preserve the project layering: data repositories, domain UseCases/Actions, ViewModels, then UI.
- ViewModels use UseCases and Actions, never repositories directly.
- Ask clarifying questions before making architecture-affecting decisions; do not silently choose persistence shape, shared models, threading, or cross-screen ownership when multiple reasonable options exist.
- Never use Kotlin `!!`; use safe calls, early returns, defaults, or smart-cast locals.
- Reuse existing UseCases, Actions, repositories, composables, utilities, strings, icons, and theme dimensions before creating new ones.
- Keep changes scoped. Do not refactor unrelated code while implementing a feature or fix.
- When durable architecture, workflow, or verification rules change, update the relevant `docs/ai/*` file. Do not duplicate those rules in tool-specific shims.
- After running Gradle, stop Gradle daemons with `./gradlew --stop` before finishing the task to avoid orphaned processes.

## Karpathy-Style Coding Discipline

Use these behavioral guardrails for every coding, review, and refactor task. They bias toward caution, simplicity, and verifiable progress; for truly trivial work, apply judgment without adding ceremony.

### 1. Think Before Coding

- Do not assume intent when the request or code is ambiguous. State assumptions clearly.
- If there are multiple reasonable interpretations, surface them instead of silently choosing one.
- If a simpler approach exists, mention it and prefer it unless the task requires more.
- Push back when a requested or implied approach adds unnecessary complexity or risk.
- If something is unclear enough to change the implementation meaningfully, stop, name the uncertainty, and ask.

### 2. Simplicity First

- Write the smallest code that fully solves the requested problem.
- Do not add speculative features, options, extensibility, or configurability.
- Do not create abstractions for one-off use cases.
- Do not add error handling for states that cannot occur under the established app contracts.
- If an implementation grows large and a much smaller version would solve the same problem, simplify before finishing.
- Ask whether a senior engineer would consider the change overcomplicated. If yes, reduce it.

### 3. Surgical Changes

- Touch only files and lines needed for the user's request.
- Do not improve adjacent code, comments, formatting, naming, or structure unless required.
- Do not refactor unrelated code while passing through an area.
- Match the existing local style even when another style seems preferable.
- If unrelated dead code or cleanup is discovered, mention it rather than deleting it.
- Remove only the imports, variables, functions, and files made obsolete by your own change.
- Every changed line should trace back to the task.

### 4. Goal-Driven Execution

- Turn each task into concrete success criteria before or during implementation.
- For bug fixes, prefer a reproducing test or focused verification before claiming success.
- For validation work, verify invalid and valid paths where practical.
- For refactors, preserve behavior and run the relevant compile/tests before and after when feasible.
- For multi-step work, use a brief plan with a verification point for each step.
- Keep looping until the defined verification passes or a blocker is clearly reported.
