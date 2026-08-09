---
name: trip-2-implement
description: Implement an approved TRIP plan with Sol review gates
---

# Implementation Mode

## Initialization Gate

Before any action, require `.agents/trip/initialized.json`. If it is absent, stop and invoke `trip-init`; do not create a branch or modify project files.

You are now in **implementation mode** for **[PROJECT_NAME]**.

## Prerequisites - Read First

Before implementing, you MUST read ALL THE LINES of:

1. @docs/ARCHI.md - Understand current system architecture

## Your Task

Implement: $ARGUMENTS

---

## Step 0: Create a Branch (Pre-Implementation)

**Always** create a dedicated branch before implementing — no need to ask. `trip-3-release` merges it back into the main branch with fast-forward, keeping a single clean linear history.

```bash
git checkout -b feat/[short-description]   # or fix/[short-description]
```

Derive the short description from the plan/feature name. If already on a dedicated branch for this work (e.g., resuming a session), continue on it.

---

## Implementation Phase — Delegate to Sol

The manager owns orchestration. Sol at high reasoning owns the bounded implementation work. Sol at xhigh owns the later code-review loop. Do not expose the private implementation workflow as a public skill. (Exception: trivial unplanned changes of a few lines may be done directly.)

1. Read the plan fully and decide the delegation scope: the whole plan, or one phase at a time for multi-phase plans. For multiple issues, first create one dependency and ownership ledger: issue, prerequisite, owned paths, assigned Sol wave, verification, and integration order. Run only waves whose owned paths do not overlap; integrate once before review.

2. **Start** the implementation session (state dir is handled by the script):

   ```bash
   python3 .agents/trip/bin/launch_runtime.py run \
       --module implementation --phase start --role sol-implement --target <plan-path> \
       --round 1 --owns <owned/path> --prompt "Implement Phase 1 only"
   ```

   Follow-up phases resume the same thread (context retained):

   ```bash
   python3 .agents/trip/bin/launch_runtime.py run \
       --module implementation --phase continue --role sol-implement --target <plan-path> \
       --round <2-5> --owns <same/owned/path> --resume --prompt "Now implement Phase 2"
   ```

3. **Parse the trailing tag** of the report:
   - `IMPLEMENTATION_COMPLETE` → proceed to Self-Review below.
   - `IMPLEMENTATION_PARTIAL` → read the report; resume with instructions for the remainder, or finish small leftovers yourself during Self-Review.

For phased delegation, run the Delegate → Self-Review cycle per phase; the testing gate and Sol code review run once, after the last phase.

---

## Self-Review & Fix

After Sol reports, review the implementation yourself before anything else:

- Read the full diff (`git status -s`, `git diff HEAD`) against the plan, ARCHI.md patterns, and project conventions (DRY, KISS, comment discipline, error-handling and naming conventions from ARCHI.md).
- Fix any problem **directly yourself** — no back-and-forth with Sol over fixes. Resume the private implementation thread only for genuinely new scope (e.g., the next phase).
- Verify the plan checkboxes Sol ticked match what the diff actually contains; cross any it completed but missed.

Proceed to the testing gate once you consider the implementation good for review.

---

## Testing Gate

After implementation, before the Sol review loop. Any failure here blocks the loop from starting.

### 1. Lint, type-check & build

```bash
[LINT_COMMAND]
[TYPECHECK_COMMAND]
```

If the project has no separate lint or typecheck step, trip-init removes the corresponding line.

### 2. Run affected unit tests

Pick the suite(s) owning the touched code and filter to affected classes/files:

```bash
[TEST_COMMAND]
```

Only the files/areas the change touched — never the full suite by default.

### 3. Integration impact check

[ADAPT_TO_PROJECT: Integration Impact]

<!-- trip-init replaces the marker above with the project's integration/E2E
verification guidance: which changes require the full verification gate,
which require manual/device/emulator/staging checks, which external contracts
need exercising against a real service. Keep the final bullet: -->

- Docs-only changes skip this.

### 4. Author missing tests

If the change adds new logic, write its tests **now**, guided by the plan's **Test Impact** section and the project's testing guide (see `trip-test`). If no new logic was added, skip this step.

**Hard-to-cover code policy:**

- Test **observable behavior** (inputs → outputs/persisted effects), never internal wiring.
- **Mock-pain tripwire**: if the mock setup grows longer than the test's assertions, stop fighting it — check the project's testing guide for a seam recipe; if none applies, skip the *deep unit* test and add one line to `docs/4-unit-tests/COVERAGE-DEBT.md` (`path | why hard | escape plan`).
- **Critical-path floor**: behavior touching auth, deletion, persistence, cost, or external request shape must keep at least one behavioral test or manual integration check — coverage debt may defer internal-path depth, never safety-critical behavior.
- Never hide untested code (no coverage-ignore comments, no config exclusions, no lowering coverage gates). Legacy modules outside the change scope are not a feature blocker — but record newly encountered risky gaps in the ledger.

### 5. Build the summary

Format: `lint: clean | typecheck: clean | tests: N passed (M new)`

Fix failures before starting the loop.

---

## Sol Code Review

Always run the Sol xhigh code review after the testing gate passes — no confirmation needed.

### Loop

1. **Start**:
   ```bash
   python3 .agents/trip/bin/launch_runtime.py run \
       --module code-review --phase start --role sol-review --target <plan-path> \
       --round 1 --prompt "$GATE_SUMMARY"
   ```
   `$GATE_SUMMARY` is the testing-gate summary (`lint | typecheck | tests`). For unplanned work (no `F_*.plan.md`), pass a free-form label instead of a plan path.

2. **Parse trailing tag**: `APPROVED` -> synthesize. `NEEDS_REWORK` -> surface to user. `REQUEST_CHANGES` -> continue.

3. **Address findings** — quote each with `file:line`, read the actual code, fix legitimate ones, push back on incorrect ones. Critical/Major block approval; Minor/Suggestion are case-by-case.

4. **Write implementer notes** (1-3 sentences): which findings you fixed, which you pushed back on and why, any user decisions or environment limitations Sol should stop re-flagging.

5. **Resume** (re-run the testing gate first — lint, typecheck, affected tests — and build a fresh summary):
   ```bash
   python3 .agents/trip/bin/launch_runtime.py run \
       --module code-review --phase resume --role sol-review --target <plan-path> \
       --round <2-5> --resume --prompt "Fixed X. Pushed back on Y because Z. $GATE_SUMMARY"
   ```
   Loop to step 2.

6. **Cap at 5 rounds** (or user-specified). Surface remaining findings.

### Synthesize

Skip if loop converged on Turn 1 (state file already holds full review).

Turn-N invocation records hold only that turn's delta. After multi-round convergence, produce a consolidated review:

```bash
python3 .agents/trip/bin/launch_runtime.py run \
    --module code-review --phase synthesize --role sol-review --target <plan-path> \
    --round 1 --resume --prompt "Today's date is YYYY-MM-DD"
```

Outputs `PROMOTION_READY` sentinel. `<x.y.z>` Version placeholder left unfilled (resolved during `trip-3-release`).

Edge cases:
- **Capped without APPROVED**: still synthesize; Sol notes open findings.
- **User skipped Sol review**: no synthesis. The CR is written manually during `trip-3-release`: "Code review skipped — trivial change."

### Operating Notes

Surface reviews verbatim. Keep edits scoped. If Sol repeats a finding, re-read carefully — you likely addressed an adjacent concern. Reset thread only if context is confused. The testing gate (lint, typecheck, affected tests) must pass before APPROVED.

### Consolidated Response, Fixes, and Sol Gate

For every accepted finding, first run Sol's private response phase without editing:

```bash
python3 .agents/trip/bin/launch_runtime.py run \
    --module code-review --phase review-response --role sol-review --target <plan-path> \
    --round 1 --resume --prompt "Respond to every consolidated finding with current file/line evidence."
```

Then apply all accepted fixes together through one write-capable Sol phase. Pass every owned path from the dependency/ownership ledger:

```bash
python3 .agents/trip/bin/launch_runtime.py run \
    --module code-review --phase fix --role sol-review --target <plan-path> \
    --round 1 --owns <owned/path> \
    --prompt "Read the newest review-response final.txt for this target. Apply all accepted findings in one wave and add their focused tests."
```

The fix phase deliberately starts a fresh `workspace-write` session; do not add `--resume`, because the preceding review thread is read-only.

Run one risk-based verification matrix after the complete fix wave—never the broad suite after each issue—and save the results in the target plan or a handoff file. Finally, start a fresh Sol final gate; do not resume the Sol review thread with a different role:

```bash
python3 .agents/trip/bin/launch_runtime.py run \
    --module code-review --phase final-gate --role sol-final --target <plan-path> \
    --round 1 --prompt "Adjudicate the consolidated review, response, fixes, and verification matrix."
```

The persisted session must verify `gpt-5.6-sol/high`. Sol is the only role that may declare the implementation ready for release; `REQUEST_CHANGES` or `NEEDS_REWORK` returns to the consolidated response/fix/verification sequence.

---

## Handoff to Release

After Sol review converges (or is skipped):

- Cross the corresponding checkboxes in the plan todo list (if any)
- Then **use the `request_user_input` tool** to ask:
  - **Question**: "Is the implementation complete?"
  - **Options**: "Yes, everything is complete" (proceed to release), "No, there are remaining items" (continue working)

**If "Yes"**: proceed directly into the release — read `.agents/skills/trip-3-release/SKILL.md` and follow it in this session, passing the same plan path (or feature label). The release skill owns everything from version bump to the fast-forward merge and push.

**If "No"**: continue working, then repeat the sequence: testing gate → Sol review → this question.
