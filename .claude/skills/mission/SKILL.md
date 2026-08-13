---
name: mission
description: Fire-and-forget feature implementation. Plans, builds (via subagent), evaluates (via separate subagent), retries on failure. User provides goal + done-when criteria.
argument-hint: "<goal description>"
---

# Mission

Plan → Build → Evaluate loop. Max 3 cycles.

## Input

Parse from user: **Goal** (1-4 sentences) + **Done when** (testable criteria). If no done-when provided, ask before proceeding.

## Phase 1: Plan (orchestrator)

Do this yourself. Do NOT delegate.

1. Verify build works: `./gradlew build` (or equivalent). Fix environment issues first — they're prerequisites, not plan steps.
2. Grep existing code + read `{service}/docs/index.md` + `docs/guides/backend-conventions.md`.
3. Find reference code (similar existing implementation).
4. Produce plan with testable steps. Each step has a verify command.
5. Show plan to user. Proceed when approved.

## Phase 2: Build (subagent in worktree)

Spawn builder with `isolation: "worktree"`. Prompt must include:

- The plan.
- `docs/guides/backend-conventions.md` path.
- Reference code path.
- Builder rules:

```
1. Copy config files from main repo (gradle.properties etc). Verify build works first.
2. Read docs/guides/backend-conventions.md before writing code.
3. Execute plan step by step. Run verify command after each step. Max 3 retries per step.
4. After all steps, run full test suite + `git diff --stat` to confirm changes are saved.
5. Report: git diff --stat output, test results, any issues. Do NOT commit.
```

Record the worktree path from the result.

## Phase 3: Evaluate (separate subagent, fresh context)

Spawn evaluator in the SAME worktree (not isolation — point it to the builder's worktree path). Must not share context with builder.

Prompt must include:

- Worktree path.
- Goal + done-when criteria.
- `docs/guides/backend-conventions.md` path.
- The plan (so evaluator knows the intended scope).

Evaluator checks:
1. `git diff --stat` first — this is the source of truth, not builder's report.
2. Run all verify commands from the plan.
3. Convention compliance on changed files.
4. **Live verification** (when applicable): start the service (`./gradlew bootRun`), send actual requests (HTTP/gRPC), verify responses. Stop the service after.
5. Report pass/fail with specific issues.

## Phase 4: Retry or Complete

- **Pass**: Report to user — summary, files changed, test results, worktree path for merge.
- **Fail (fixable)**: Spawn NEW builder in the SAME worktree with evaluator's exact feedback. Max 2 retry cycles.
- **Hard fail** (wrong architecture): Report to user immediately.

## Rules

- You orchestrate. You don't write code.
- Max 3 build cycles total. Then escalate.
- Track the worktree path — every subagent needs it.
- Don't skip the evaluator. Builder self-reports are unreliable (proven in practice).
- Don't trust builder's "I changed X" — evaluator verifies via git diff.
