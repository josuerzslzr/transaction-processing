# Transaction Processing Practice Roadmap

This is the living execution plan and status tracker for the project described in
`project.md`.

## Current status

- Current iteration: 1 — Architecture baseline
- Overall status: Iteration 1 documentation is validated and ready for user review
- Allowed iteration statuses: `NOT_STARTED`, `IN_PROGRESS`, `BLOCKED`, `COMPLETE`
- At most one iteration may be `IN_PROGRESS` at a time.

## Iteration roadmap

| # | Status | Iteration | Objective and completion gate |
|---|---|---|---|
| 1 | `COMPLETE` | Architecture baseline | Agree on access patterns, REST operations, DynamoDB keys, idempotency, lifecycle commands, concurrency, errors, component boundaries, and testing strategy. Complete when documented and approved; no production implementation yet. |
| 2 | `NOT_STARTED` | Core payment slice | Build the Spring Boot foundation and implement idempotent creation and retrieval using standalone DynamoDB Local. Atomically store idempotency, state, and creation event records. Complete when unit and integration tests prove replay, conflicts, validation, and money rules. |
| 3 | `NOT_STARTED` | Minikube foundation | Containerize the working slice and establish minikube as the standard full-system local runtime. Complete with a documented clean bootstrap and end-to-end payment test. |
| 4 | `NOT_STARTED` | Lifecycle and concurrency | Implement valid generic transitions, optimistic concurrency, and atomic state-plus-event writes independently of messaging. Complete when competing updates and invalid transitions cannot corrupt state or history. |
| 5 | `NOT_STARTED` | Continuous integration | Add GitHub Actions for tests, builds, and container validation. Complete when hosted workflows pass in the GitHub repository the user will initialize and connect manually. |
| 6 | `NOT_STARTED` | Kinesis design and local evaluation | Decide event contracts, publication reliability, DynamoDB Streams usage, partitioning, ordering, retries, checkpoints, poison-record handling, and LocalStack versus standalone emulation. Complete with an approved ADR and local feasibility proof. |
| 7 | `NOT_STARTED` | Event-driven processing | Introduce Kinesis and an independently runnable payment processor. Complete when minikube tests prove asynchronous processing, duplicate safety, restart recovery, and authoritative DynamoDB state. |
| 8 | `NOT_STARTED` | OpenSearch design and local evaluation | Define search access patterns, document shape, ownership, consistency, replay/rebuild behavior, local runtime, and whether a dedicated search projector is appropriate. Complete with an approved ADR and connectivity proof. |
| 9 | `NOT_STARTED` | Search implementation | Implement the approved projection flow and search API without making OpenSearch authoritative. Complete when tests prove projection, client isolation, eventual consistency, duplicate handling, and rebuilding. |
| 10 | `NOT_STARTED` | Datadog observability | Decide whether to use the free tier, trial, or configuration-only validation. Implement the agreed metrics, logs, traces, correlation, dashboards, and monitors without coupling domain logic to Datadog. |
| 11 | `NOT_STARTED` | k6 performance suite | Create manually invoked smoke, load, stress, spike, and soak scripts for primary synchronous and asynchronous flows. Complete when scripts are repeatable and document observed local behavior; no CI execution or hard performance thresholds. |
| 12 | `NOT_STARTED` | Resilience proof | Exercise dependency outages, duplicate and out-of-order records, worker restarts, concurrent commands, backlogs, and projection recovery. Complete with documented drills proving authoritative state remains consistent and recoverable. |
| 13 | `NOT_STARTED` | AWS/EKS infrastructure | Model the AWS environment in Terraform, including EKS and required networking, IAM, image storage, DynamoDB, Kinesis, OpenSearch, and observability integration. Complete when definitions and runbooks validate without applying infrastructure. |
| 14 | `NOT_STARTED` | Delivery readiness | Extend GitHub Actions with release and EKS deployment workflows that remain disabled or dry-run until separately authorized. Complete when artifacts, configuration, rendered deployments, rollback procedures, and operating documentation validate without AWS spend. |

## Working agreement

- Each iteration starts only after explicit user approval and by changing its status to
  `IN_PROGRESS`.
- Agree on the iteration's design decisions and acceptance criteria before implementation.
- Write domain tests first, followed by adapter integration tests and minikube acceptance
  tests where applicable.
- Record decisions, commands, tests, limitations, and acceptance evidence for every
  iteration.
- Mark an iteration `COMPLETE` only after its evidence has passed and the user explicitly
  approves completion.
- Do not start the next iteration automatically.
- Treat emulator results as local correctness evidence, not proof of exact AWS behavior or
  performance.

## Deferred decisions and boundaries

- Use standalone DynamoDB Local initially; evaluate LocalStack during iteration 6.
- Cancellation is not part of the initial lifecycle or API. A future roadmap revision may add
  cancellation commands and dedicated cancellation states; do not introduce them implicitly.
- Decide the search-projector boundary during iteration 8.
- Decide Datadog account and SaaS usage during iteration 10.
- Do not create or apply AWS resources without separate explicit approval.
- Authentication, real payment providers, money movement, settlement, reconciliation,
  ledgers, PCI data, multi-region architecture, frontend work, and event sourcing remain
  out of scope.
