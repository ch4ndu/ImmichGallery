---
name: audit
description: Audit the ImmichGallery codebase for architecture, Compose correctness and performance, and data or cache contract issues. Use for code reviews, architecture audits, performance or recomposition audits, optimization reviews, and broad "check the codebase" requests. Report findings with file paths, line numbers, evidence levels, and fixes.
allowed-tools: Read, Grep, Glob, Agent
---

# Architecture Audit

Read `AGENTS.md`, then load `docs/ai/audit.md`.

For focused audits, also load the relevant docs:

- UI: `docs/ai/ui.md`
- Compose performance, recomposition, effects, or lazy layouts: `docs/ai/compose-performance-audit.md` and `docs/ai/ui.md`
- Room cache, Immich API, settings, time: `docs/ai/data-cache-time.md`
- Architecture: `docs/ai/architecture.md`

Report findings only unless explicitly asked to fix them.

Do not duplicate durable project rules in this Claude skill. Update the relevant `docs/ai/*` file instead.
