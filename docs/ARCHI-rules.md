# Architecture Documentation Rules

[ARCHI.md](ARCHI.md) is the system-level architecture map for ImmichGallery.
After each task, determine whether the implementation changed an architectural
fact recorded there.

## When To Update

Update `ARCHI.md` after a change alters any of the following:

- Project Classification, Product Scope, Technology Stack, or Repository Structure
- Core Architecture Principles or dependency direction
- Application Composition and Dependency Injection
- Startup, Authentication, Session Ownership, or Navigation
- the Immich API Boundary
- Persistence and Cache Ownership
- Timeline, Album, Person, Search, grid, or media ownership across layers
- Android, iOS, or JVM platform responsibilities
- Configuration, Versioning, Security, Build, or Packaging
- Testing and Verification Strategy
- Performance, Concurrency, Error Handling, or Observability
- a system-level data-flow diagram or documentation ownership rule

## How To Update By Change Type

### Major Feature Or Refactor

Review every affected ownership section, platform boundary, data-flow diagram,
verification requirement, and documented constraint. Update the focused
`docs/ai/*` feature contract at the same time.

### Minor Feature Or Enhancement

Update `ARCHI.md` only when ownership, dependency direction, persistence
classification, platform behavior, configuration, or verification requirements
changed. Keep detailed screen behavior in its focused document.

### Bug Fix

Usually no system-level update is needed. Update `ARCHI.md` when the fix changes
or corrects a documented invariant, boundary, data flow, or platform contract.

### Dependency Or Toolchain Change

Update Technology Stack and Build and Packaging, then any affected platform,
testing, security, or compatibility sections.

### Room Or Immich API Change

Review the Immich API Boundary, Persistence and Cache Ownership, feature data
flow, migration policy, error handling, and verification sections. Also update
`docs/ai/data-cache-time.md` and the owning feature document.

## Guidelines

- Describe the current repository, not a proposed design.
- Keep ownership and dependency direction explicit.
- Reference real paths and stable identifiers.
- Update Mermaid diagrams when system-level flow changes.
- Record meaningful Android, iOS, and JVM differences.
- Keep detailed algorithms and screen contracts in `docs/ai/*` or
  `docs/timeline.md`; link to them instead of duplicating them.
- Keep user-facing setup and feature descriptions in the root `README.md`.
