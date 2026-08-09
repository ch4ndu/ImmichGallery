---
name: trip-test
description: Author and run focused tests following project standards
---

# Testing Mode

## Initialization Gate

Before any action, require `.agents/trip/initialized.json`. If it is absent, stop and invoke `trip-init`; do not author or run project tests through this workflow.

You are now in **testing mode** for **[PROJECT_NAME]**.

This skill is the **deep test-authoring reference**: the `trip-2-implement` testing gate points here for heavy authoring work and full guidance. Invoke it standalone for test backfill or coverage work outside an implementation session.

## Prerequisites - Read First

Before testing, you MUST read:

1. @docs/ARCHI.md - Understand system architecture
2. @docs/4-unit-tests/TESTING.md - Testing guidelines

## Your Task

Test: $ARGUMENTS

---

## Testing Guidelines

### Scope

- Only run tests for relevant files that changed (not the whole project)
- Focus on the new feature/fix/refactor

### Commands

```bash
# Run all tests
[TEST_COMMAND_ALL]

# Run a specific test
[TEST_COMMAND_SINGLE]

# With coverage (if configured)
[TEST_COMMAND_COVERAGE]
```

### Test Structure

[ADAPT_TO_PROJECT]

<!-- trip-init replaces the marker above with the project's actual test
organization: where tests live, file naming conventions, frameworks used,
and any layering rules (which kinds of tests go where). -->

### Testing Priorities

[ADAPT_TO_PROJECT]

<!-- trip-init replaces the marker above with what actually deserves testing
in this project, grouped by suite (unit / integration / hardware-in-loop /
E2E as applicable), plus a "What to Test" list (normal paths, error
conditions, boundary values) and a note of what is NOT unit-testable here
(manual or instrumented verification instead). -->

---

## Hard-to-Test Code

Seam ladder, cheapest first: **exported pure helper → injectable client/adapter → module mock → integration/emulator test**. Take the first rung that works; refactor for a seam only if the refactor is smaller than the feature you're shipping — otherwise it's coverage debt. Before refactoring legacy code, pin it with characterization tests (assert current behavior as-is, then refactor safely).

Uncovered risky paths: one line each in `docs/4-unit-tests/COVERAGE-DEBT.md` (`path | why hard | escape plan`). Delete a ledger line in the same change that gives its path meaningful coverage.

---

## Post-Testing Summary

After completing tests, create a summary file:

**File**: `docs/4-unit-tests/wa_vx.y.z_test.md`
(a = project week, x.y.z = version)

**Content**:

```markdown
# Test Summary - Week a, V. x.y.z

## What Was Tested

[List of tested components/functions]

## Test Results

- Total tests: X
- Passed: X
- Failed: X
- Coverage: X%

## Key Findings

[Any issues discovered, edge cases found, etc.]

## Notes

[Additional context or recommendations]
```
