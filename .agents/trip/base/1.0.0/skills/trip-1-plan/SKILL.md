---
name: trip-1-plan
description: Plan a feature using the adapted project workflow
---

# Planning Mode

## Initialization Gate

Before any discovery or planning action, require `.agents/trip/initialized.json`. If it is absent, stop and invoke `trip-init`; do not continue this workflow.

If the host Codex session is in Plan Mode, keep the repository read-only: prepare and present the plan in chat, but do not create or edit the plan file. Persist the approved plan only after the user switches to Default mode.

You are now in **planning mode** for **[PROJECT_NAME]**.

## Prerequisites - Read First

Before creating any plan, you MUST read ALL THE LINES of:

1. @docs/ARCHI.md - Understand current system architecture

## Your Task

Plan the following feature: $ARGUMENTS

---

## Step 1: Discovery & Clarification (Interactive)

**Do NOT start writing a plan immediately.** First, engage in a discovery conversation to fully understand the user's intent.

### 1.1 Initial Understanding

After reading the feature request, summarize your understanding in 2-3 sentences, then **use the `request_user_input` tool** to present clarifying questions with structured options.

Frame questions around:

- **Scope**: What's included vs excluded?
- **Behavior**: How should it work from the user's perspective?
- **Constraints**: Any technical limitations, deadlines, or dependencies?
- **Priority**: What's most important if trade-offs are needed?

For each question, provide 2-4 concrete options based on your analysis of the codebase and the feature request. Always let the user provide custom input via the built-in "Other" option.

After the user answers, proceed **directly to writing the plan** (Step 2) — no approach-confirmation question. Ask a follow-up round with `request_user_input` only if a blocking ambiguity remains (**maximum 3 rounds total**; if still unclear, summarize what you know and proceed with noted assumptions).

---

## Step 2: Plan Document Creation

Once understanding is confirmed, create the plan document.

### File Naming

Depending on the feature (major, minor, patch), propose a new version using SemVer (x.y.z) and create:
`docs/1-plans/F_[version]_[feature-name].plan.md`

### Required Sections

```markdown
# [Feature Name] Implementation Plan

## Overview

[2-4 sentences describing the feature and its purpose]

## Problem Statement (if applicable)

[Current limitations/issues this feature addresses]

## Solution Architecture

[High-level design approach]

## Implementation Details

### 1. [Component/Module/File Name]

**File**: `path/to/file`

[Detailed description of changes needed]

**Current state** (if modifying existing):
[Describe what currently exists]

**Modifications**:

- Specific change 1 (around line X)
- Specific change 2 (around line Y)

### 2. [Next Component/Module/File]

[Continue with same pattern]

## Technical Considerations

[ADAPT_TO_PROJECT: Replace the items below with concerns relevant to this
specific codebase — patterns from ARCHI.md, layering rules, platform impact,
state management, security, performance. trip-init fills this in.]

- **Pattern Usage**: Which existing patterns to follow (from ARCHI.md)
- **Edge Cases**: Empty states, loading states, error states
- **Performance**: Impact on hot paths, resource usage
- **Security**: Input validation, sensitive data handling

## Edge Cases Considered

[Required for any change to shared or multi-surface behavior. Enumerate how the design handles: empty/single-item/first/last/boundary states; extremes, clamping, mixed sizes, unavailable data; rapid input, cancellation, async/callback ordering, stale responses; dismissal, back/undo behavior, and platform differences where applicable. For strictly local patch work, note the relevant subset.]

## Files to Modify/Create

[Comprehensive numbered list with purposes]

1. `path/to/file1` (modify) - Purpose description
2. `path/to/file2` (new) - Purpose description

## Type Definitions (if applicable)

[New types, interfaces, structs, or modifications to existing ones]

## Performance & Cost Impact (if applicable)

[Expected performance implications]

## Backward Compatibility (if applicable)

[Migration strategy if needed]

## Test Impact

[2-5 bullets: which existing tests the change affects, what new logic will need tests, whether an integration/E2E check applies. No test code — the trip-2 testing gate consumes this section.]

## To-dos

### Phase 1: [Phase Name] (if multiple phases are needed) or simply skip title if only one phase is needed

- [ ] Task description
- [ ] Another task

### Phase 2: [Phase Name] (if applicable)

- [ ] Task description
- [ ] Another task

**Note**: For simple plans, a single phase is sufficient. Split into multiple phases only for complex features requiring sequential implementation.

**Note**: Do NOT write test code during planning — the Test Impact section above only names what the trip-2 testing gate will run and author.
```

## Quality Standards

- **Zero Ambiguity**: Every step must be clear and actionable
- **File-Level Specificity**: List exact files and functions to modify
- **Architecture Alignment**: Must conform to existing patterns in ARCHI.md
- **Risk Assessment**: Highlight potential failure points

---

## Step 3: Sol Second-Opinion Review

Before the user sees the plan, run the Sol xhigh plan review loop.

### Confirm

`request_user_input`: "I'll run Sol xhigh as a second-opinion reviewer and iterate until clean. Proceed?"
Options: "Yes, run Sol review" (recommended) / "Skip Sol, go to user review" / "Cap iterations at N"

Skip for trivial plans (single-file, low-risk). Run for non-trivial (new module, schema/algorithm change).

### Loop

1. **Start** the private Sol review phase (the launcher records the exact selected model/effort and fails closed if the persisted Codex session does not verify them):
   ```bash
   python3 .agents/trip/bin/launch_runtime.py run \
       --module plan-review --phase start --role sol-review --target <plan-path> \
       --round 1 --prompt "Review this plan before user approval."
   ```
2. **Parse trailing tag**: `APPROVED` -> Step 4. `NEEDS_REWORK` -> surface to user. `REQUEST_CHANGES` -> continue.
3. **Address findings critically** — quote each P1/P2, push back on incorrect ones, fix legitimate ones by editing the plan in place.
4. **Write implementer notes** (1-3 sentences): which findings you fixed, which you pushed back on and why, any user decisions that override existing docs or environment limitations that can't be resolved in the plan.
5. **Resume** with notes:
   ```bash
   python3 .agents/trip/bin/launch_runtime.py run \
       --module plan-review --phase resume --role sol-review --target <plan-path> \
       --round <2-5> --resume \
       --prompt "Fixed X. Pushed back on Y because Z. User decided W."
   ```
   -> back to step 2.
6. **Cap at 5 rounds** (or user-specified). Surface remaining findings and let user decide.

Surface Sol reviews verbatim. Keep edits scoped to findings. Runtime state is durable under `.local/trip/runtime/`; do not reset a verified thread merely to bypass a finding.

---

## Step 4: User Review & Validation

After Sol review converges (or is skipped), present a summary to the user including:

- **Feature**: [name]
- **Approach**: [1-2 sentences]
- **Files affected**: [count] files ([list key ones])
- **Estimated complexity**: [simple/moderate/complex]
- **Sol review status**: [APPROVED / skipped / capped at N rounds with open findings]

Then **use the `request_user_input` tool** to collect feedback:

- **Question**: "Please review the plan at `docs/1-plans/F_x.y.z_feature-name.plan.md`. How would you like to proceed?"
- **Options**: "Approved" (ready for implementation), "Request changes" (I have modifications), "Needs rework" (significant issues to address)

Handle feedback:

- **If "Request changes"**: Update the plan and re-present. Run another Sol review pass if changes are substantive.
- **If "Needs rework"**: Discuss issues, rework the plan, and re-present.
- **If "Other" (custom input)**: Handle accordingly.
- **If "Approved"**: **Use the `request_user_input` tool** to ask:
  - **Question**: "Plan approved. Would you like to start implementation now?"
  - **Options**: "Yes, implement now" (proceed with `trip-2-implement` using this plan), "Not yet" (I'll implement later)

---

## IMPORTANT: No Code Implementation

**DO NOT write code snippets or implement anything during planning.**

This is a high-level planning phase only. Your plan should describe:

- WHAT needs to be done (features, changes, structures)
- WHERE changes will happen (files, modules, functions)
- WHY certain approaches are chosen (trade-offs, rationale)

But NOT:

- Actual code implementations
- Detailed algorithm code

Keep it architectural and descriptive. Code comes in the `trip-2-implement` phase.

[ADAPT_TO_PROJECT: Guidance Sections]

<!-- trip-init replaces the marker above with project-specific per-component
guidance sections derived from ARCHI.md — one "## For New <ComponentType>"
section per major pattern/layer in the project (e.g. "For New Endpoints",
"For New Peripheral Drivers", "For New Screens"), each listing the required
analysis a plan must include for that component type. -->
