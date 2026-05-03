---
name: audit
description: Audit the ImmichGallery codebase against architecture rules routed from AGENTS.md. Reports violations with file paths, line numbers, and fixes.
allowed-tools: Read, Grep, Glob, Agent
---

# Architecture Audit

Read `AGENTS.md`, then load `docs/ai/audit.md`.

For focused audits, also load the relevant docs:

- UI: `docs/ai/ui.md`
- Room cache, Immich API, settings, time: `docs/ai/data-cache-time.md`
- Architecture: `docs/ai/architecture.md`

Report violations only unless explicitly asked to fix them.

Do not duplicate durable project rules in this Claude skill. Update the relevant `docs/ai/*` file instead.
