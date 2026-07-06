// 02-architecture-decisions-and-fixes.md
# 2. Architecture Decisions & Fixes

As part of hardening the initial design to production-grade standards, several architectural corrections and additions were made.

## A1. Splitting Product Catalog and Inventory
**Original Design:** Product Service handled both catalog and inventory.
**Problem:** Catalog is read-heavy and eventually consistent. Inventory is write-heavy, hot, and requires strong consistency (reservations race during checkout). Coupling them means inventory contention throttles catalog reads.
**Fix:** Split into **Product Service** (catalog, categories) and **Inventory Service** (stock, reservations). Debezium topics become `cdc.products.product` and `cdc.inventory.stock-item`. The Order Orchestrator talks only to Inventory Service.

## A2. Transactional Outbox Pattern
**Original Design:** Services publish events directly to Kafka.
**Problem:** The dual-write problem. A DB commit might succeed while the Kafka publish fails, breaking atomicity.
**Fix:** Adopt the **Outbox Pattern**. Services write to an `outbox_events` table in the *same transaction* as their business write. Debezium's **Outbox Event Router SMT** transforms outbox rows into properly-routed Kafka messages and drops the original row topic. This guarantees exactly-once-out-of-the-DB semantics.

## A3. Schema Registry Enforcement
**Problem:** No schema governance leads to runtime deserialization errors when producers change message formats.
**Fix:** Added **Apicurio Registry** with **Avro**. Enforced `BACKWARD_TRANSITIVE` compatibility. Producers serialize via the registry; consumers deserialize with auto-evolved readers.

## A4. Missing Services Identified
- **Cart Service:** Modeled explicitly. Redis for ephemeral/guest carts, PostgreSQL for authenticated-user carts, merge-on-login.
- **BFF (Backend-for-Frontend):** Next.js 15 should not fan out to 8+ services. The BFF aggregates; the gateway stays a thin cross-cutting layer.
- **Audit Service:** Consumes `audit.events` topic, sinks to immutable storage (S3 Object Lock). Required for commerce platforms with admin actions.
- **Idempotency Starter:** Payment, Order, and Delivery need idempotency keys. Shipped as `platform-starter-idempotency` backed by Redis + Postgres fallback to avoid per-service reinvention.

## A5. Saga Strategy Clarification
**Decision:** 
- **Orchestration for Checkout:** Explicit state machine, parallel branches (Tax + Promotion), centralized timeout/retry/compensation. Implemented via Spring Statemachine.
- **Choreography for Reactions:** Post-commit side effects (Notification on `OrderPlaced`, Recommendation learning from `OrderPlaced`) use pure event choreography.
**Implementation Detail:** Java 25's **Structured Concurrency** is used inside the orchestrator for parallel branch fan-out, with **Scoped Values** to propagate trace/saga context instead of `ThreadLocal`.

## A6. Java 25 Features Adopted
- **Virtual threads** (`spring.threads.virtual.enabled=true`): Eliminates reactive complexity for I/O-bound services. Customer, Search, and Product services stay imperative while hitting reactive-class throughput.
- **Structured Concurrency:** Used in Order Orchestrator for parallel saga branches.
- **Scoped Values:** For request/saga/trace context (replaces `ThreadLocal`).
- **Record patterns & pattern matching for switch:** Clean sealed-type dispatch on events.

## A7. Configuration & Secrets
**Decision:** Skip Spring Cloud Config Server on Kubernetes. Prefer **ConfigMaps + Secrets** + **External Secrets Operator** backed by **HashiCorp Vault**. Spring Boot's `configtree` import mounts K8s secrets as config, removing an unnecessary SPOF.

## A8. ADRs (Architecture Decision Records)
Every major decision is documented in `/docs/adr/`. This establishes a history of *why* the system is built the way it is, which is critical for long-term maintenance.