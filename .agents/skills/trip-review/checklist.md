# Code Review Checklist

This file is the **single source of truth** for code-review criteria. Both human-driven reviews via `.agents/skills/trip-review` and Sol-driven private runtime reviews via `.agents/trip/runtime/code-review` apply the criteria below — referenced, not copied — so the two review surfaces cannot drift.

## Systematic Review Checklist

### 1. Functional Requirements

- [ ] Implementation logic matches requirements correctly
- [ ] Interface/API matches documented specifications
- [ ] Error scenarios handled with proper feedback
- [ ] Edge cases and boundary conditions validated

### 2. Code Quality

- [ ] Proper typing (no unjustified dynamic types)
- [ ] DRY principle - no code duplication
- [ ] KISS principle - not unnecessarily complex
- [ ] Consistent, descriptive naming conventions
- [ ] Complex logic has explanatory comments
- [ ] Files/modules not excessively large
- [ ] Imports/includes organized, unused ones removed

### 3. Architectural Compliance

- [ ] Code follows established patterns from ARCHI.md
- [ ] Proper separation of concerns
- [ ] Appropriate abstractions used
- [ ] Consistent with existing codebase style

### 4. KMP Layers And Lifecycle

- [ ] Shared behavior lives in `commonMain` unless native APIs require an `actual` implementation
- [ ] ViewModels inject UseCases and Actions, never repositories
- [ ] Reads, writes, side effects, dispatchers, cancellation, and lifecycle ownership follow `ARCHI.md`
- [ ] Koin registrations and every affected platform implementation are complete
- [ ] Typed routes and screen ownership do not leak one screen's behavior into another

### 5. Immich API, Room, And Cache

- [ ] Endpoint, authentication, pagination-token, response-shape, and compatibility behavior match the current contract
- [ ] Only supported visible asset types cross API/cache/domain boundaries
- [ ] Room writes and owner relationships are scoped, transactional, and cache-preserving on failure
- [ ] Schema/version changes include exported schemas, migrations, and proportional migration verification
- [ ] No-op detection, ordered fingerprints, edited-asset enrichment, and Mosaic invalidation remain aligned
- [ ] Logout clears server-scoped data before credentials and settings

### 6. Compose UI And Photo Grids

- [ ] Composables render projected state and send events; filtering, grouping, row packing, and Mosaic work stay outside Compose
- [ ] Lazy layouts retain stable keys, virtualization, exact placeholders, and consistent display indexes
- [ ] State/effect lifetimes, cancellation, stale-response guards, and pagination duplicate guards are correct
- [ ] Shared resources, theme dimensions, insets, accessibility, adaptive layouts, and existing viewer/grid components are reused
- [ ] Exact user interactions and return-target behavior are wired on every affected screen

### 7. Platform Integration

- [ ] Android application/library ownership, manifests, permissions, service startup, and lifecycle behavior are preserved
- [ ] iOS Swift/Xcode host, framework inputs, local-network rules, and AVPlayer behavior are preserved
- [ ] JVM lifecycle, input, libVLC authentication, filesystem paths, and package behavior are preserved
- [ ] Native behavior has the required explicitly authorized device, simulator, real-server, or packaged-app verification
- [ ] API keys and credential-bearing media URLs are absent from logs and committed configuration

### 8. Error Handling

- [ ] Errors are properly caught and handled
- [ ] Error messages are clear and actionable
- [ ] Failure modes are graceful
- [ ] Logging is appropriate (not too verbose, not silent)

### 9. Security

- [ ] Input validation implemented
- [ ] No sensitive data exposed in logs, errors, or responses
- [ ] Authentication/authorization handled correctly
- [ ] No obvious vulnerabilities

### 10. Performance

- [ ] No obvious performance issues
- [ ] Resource cleanup implemented (no leaks)
- [ ] Appropriate data structures used
- [ ] No unnecessary operations in hot paths

---

## Issue Severity Classification

**Critical (Block Deployment)**:

- Security vulnerabilities
- Data corruption risks
- Breaking API/interface changes
- Authentication bypasses

**Major (Require Immediate Fix)**:

- Incorrect business logic
- Significant performance degradation
- Missing error handling
- Compilation/build errors

**Minor (Should Fix)**:

- Code style inconsistencies
- Missing documentation
- Code duplication
- Missing edge case handling

**Suggestions (Nice to Have)**:

- Performance optimizations
- Readability improvements
- Additional test coverage

---

## Review Completion Criteria (Approval Gate)

Minimum for approval:

- [ ] All functional requirements implemented
- [ ] No critical or major issues remaining
- [ ] Build/compilation successful (`./gradlew :composeApp:compileKotlinJvm :composeApp:compileKotlinIosArm64 :composeApp:compileKotlinIosSimulatorArm64 :composeApp:compileAndroidMain :androidApp:assembleDebug`)
- [ ] Affected unit tests pass (per the trip-2 testing gate: `./gradlew :composeApp:jvmTest :composeApp:testAndroidHostTest`)
- [ ] New logic has test coverage (or a coverage-debt ledger entry per the hard-to-cover policy)
- [ ] Documentation updated per project standards
