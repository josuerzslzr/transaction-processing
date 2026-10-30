# Transaction Processing

A production-oriented **practice system** for exploring the architectural concerns of financial
transaction platforms: idempotent APIs, tenant isolation, authoritative state in DynamoDB,
atomic writes, lifecycle concurrency, and, in later iterations, event-driven processing,
search projections, observability, and cloud delivery.

> Practice project for professional growth: no real money movement, payment providers, card
> data, or authentication.

## What it does today

API clients (PSPs, wallet providers, large merchants) create and retrieve transactions over REST:

| Operation | Purpose |
|---|---|
| `POST /v1/transactions` | Create a transaction idempotently (`Idempotency-Key` header, UUIDv7) |
| `GET /v1/transactions/{transactionId}` | Retrieve a transaction owned by the calling client |

Every request carries `X-Client-Id`, a development tenant header (not authentication). The
contract is defined first in [`api/openapi.yaml`](api/openapi.yaml); controllers are handwritten
and tested for conformance.

![Transaction system context](docs/diagrams/transaction-system-context.svg)

## Key design ideas

- **Idempotent creation.** Retrying the same request with the same key replays the original
  response. Reusing a key with a different payload is rejected as a conflict.
- **Atomic writes.** The idempotency record, the transaction state, and a `TRANSACTION_CREATED`
  event are written in one conditional DynamoDB `TransactWriteItems` call.
- **DynamoDB is authoritative.** A single table keyed by access pattern, with every key scoped by
  `clientId`. Events are immutable audit history; this is not event sourcing.
- **Money as integers.** Amounts use minor units (`amountMinor: 4250` = EUR 42.50), never floats.
- **Extensible transaction model.** A `transactionType` discriminator; only `PAYMENT` with a
  simulated `BANK_TRANSFER` is supported now.
- **Explicit lifecycle.** `CREATED → PROCESSING → SUCCEEDED | FAILED`, with transitions guarded by
  expected status and an optimistic version (implemented in Iteration 4).
- **Standard errors.** RFC 9457 `application/problem+json` responses.

![Initial transaction lifecycle](docs/diagrams/transaction-lifecycle.svg)

## Architecture

A Gradle Kotlin monorepo (Java 21, Kotlin, Spring Boot) with strict module boundaries:

| Module | Responsibility |
|---|---|
| [`transactions-core`](modules/transactions-core) | Pure Kotlin domain, use cases, ports, idempotency fingerprinting. No Spring or AWS. |
| [`transactions-dynamodb`](modules/transactions-dynamodb) | DynamoDB records, strong reads, conditional transactions. |
| [`transactions-api`](modules/transactions-api) | Spring MVC application, DTOs, validation, RFC 9457 handling, wiring. |

![Transaction components and dependencies](docs/diagrams/transaction-components.svg)

## Documentation

| Document | What's in it |
|---|---|
| [`project.md`](project.md) | Project charter, domain language, scope, and non-goals |
| [`ROADMAP.md`](ROADMAP.md) | Iteration plan, statuses, and working agreement |
| [Architecture baseline](docs/architecture/architecture-baseline.md) | Components, HTTP behavior, lifecycle, invariants, testing strategy |
| [DynamoDB data model](docs/architecture/dynamodb-data-model.md) | Access patterns, key layout, item schemas, [idempotent create](docs/diagrams/idempotent-create.svg) and [conditional transition](docs/diagrams/lifecycle-transition.svg) flows |
| [OpenAPI contract](api/openapi.yaml) | Authoritative REST contract |
| [Diagrams](docs/diagrams/README.md) | PlantUML sources and how to regenerate the SVGs |
| [`AGENTS.md`](AGENTS.md) | Rules for AI coding agents working in this repo |

## Roadmap

The project grows in small, separately approved iterations: an architecture baseline and core
transaction slice first, then minikube, lifecycle concurrency, CI, Kinesis-based event
processing, OpenSearch projections, Datadog observability, k6 performance tests, resilience
drills, and Terraform/EKS delivery.

**Current status and iteration details: [`ROADMAP.md`](ROADMAP.md).**

## Getting started

### Prerequisites

- Java 21
- Docker with Compose

No global Gradle install is needed; use the checksum-verified wrapper.

### Run locally

Start the pinned DynamoDB Local service:

```bash
docker compose up -d dynamodb
```

Run the API with local table initialization enabled:

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :transactions-api:bootRun
```

Create and retrieve a simulated transaction:

```bash
curl -i -X POST http://localhost:8080/v1/transactions \
  -H 'Content-Type: application/json' \
  -H 'X-Client-Id: 01a067a6-5e3a-7a3b-8c4d-123456789abc' \
  -H 'Idempotency-Key: 01a067a6-5e3d-7a3b-8c4d-123456789abc' \
  -d '{"externalReference":"ORDER-12345","amountMinor":4250,"currency":"EUR","transactionType":"PAYMENT","paymentMethod":"BANK_TRANSFER"}'

curl -i http://localhost:8080/v1/transactions/REPLACE_WITH_TRANSACTION_ID \
  -H 'X-Client-Id: 01a067a6-5e3a-7a3b-8c4d-123456789abc'
```

Stop DynamoDB Local when finished:

```bash
docker compose down
```

### Verify

`./gradlew check` runs core unit tests, MVC and OpenAPI conformance tests, and Docker-backed
DynamoDB Local integration tests.

Without Docker, the DynamoDB-backed test classes are skipped, so a passing run gives fast unit
feedback but does not cover persistence.

Build one module with e.g. `./gradlew :transactions-api:build`. Gradle builds required project
dependencies only.
