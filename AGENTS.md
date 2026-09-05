# Repository Instructions

## Start here

Before planning or changing this repository:

1. Read `project.md` for the project charter, domain language, and scope boundaries.
2. Read `ROADMAP.md` for the approved iteration order and current status.
3. Work only on the iteration marked `IN_PROGRESS`.

If no iteration is `IN_PROGRESS`, do not begin implementation. Discuss the next iteration
with the user and wait for explicit approval before changing its status.

## Iteration workflow

- Treat each iteration as a separate approval gate.
- Agree with the user on design decisions, interfaces, risks, and acceptance criteria before
  implementing that iteration.
- Use test-driven development: add a failing behavioral test, implement the smallest change
  that passes it, and refactor while keeping the suite green.
- Keep changes limited to the current iteration. Do not introduce functionality assigned to
  later iterations or speculative extension points.
- Preserve clear domain boundaries and keep authoritative payment state in DynamoDB unless
  an approved iteration explicitly changes that decision.
- Record material architectural decisions and verification evidence in the repository.

## Status updates

- At most one roadmap iteration may be `IN_PROGRESS`.
- Change an iteration from `NOT_STARTED` to `IN_PROGRESS` only after explicit user approval.
- Mark an iteration `COMPLETE` only after its acceptance evidence passes and the user
  explicitly approves completion.
- Use `BLOCKED` only when work cannot continue without user input or an external change, and
  document the blocking condition.
- Never start the next iteration automatically.

## Repository structure

- Keep application components, shared code, tests, Kubernetes configuration, Terraform, and
  delivery automation in this monorepo.
- Add a nested `AGENTS.md` only when a component has materially different local build, test,
  or architectural instructions. Do not duplicate root instructions by default.

## Safety and scope

- This is a practice system, not a real payment processor.
- Do not add real payment providers, money movement, settlement, reconciliation, ledger
  behavior, PCI/card data, authentication infrastructure, frontend work, event sourcing, or
  multi-region behavior unless the roadmap is explicitly revised and approved.
- Do not create, apply, or deploy AWS infrastructure without separate explicit user approval.
- Treat local emulator results as development evidence, not proof of AWS parity or production
  capacity.
