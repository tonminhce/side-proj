// 07-implementation-roadmap.md
# 7. Implementation Roadmap

Building the platform phase-by-phase, paying the infrastructure tax early to prevent every service from reinventing it.

## Phase 0 — Platform Foundations
1. Multi-module Maven project skeleton; BOMs; checkstyle/SpotBugs/Spotless.
2. `platform-starter-core`, `observability`, `security`, `kafka`, `test-support`. Establish patterns before business code.
3. Docker Compose: Postgres, Kafka (KRaft), Apicurio, Redis, ES, MinIO, OTel collector, Prometheus, Loki, Tempo, Grafana.
4. ADRs for major decisions (DB-per-service, outbox, RL design, saga strategy).

## Phase 1 — Edge & Identity
5. Skip Eureka on K8s; use K8s DNS + Istio. For local dev, docker-compose DNS.
6. API Gateway with auth, routing, circuit breaker (no RL yet).
7. Customer Service (users, JWT issuance, refresh-token rotation, roles, addresses). Outbox wired.
8. Cart Service (Redis + Postgres).

## Phase 2 — Catalog & CDC
9. Product Service + Inventory Service (split). Outbox on both.
10. Debezium connectors with Outbox SMT. Schema Registry.
11. Search Service consuming CDC → Elasticsearch. Reindex from compacted topic on demand.

## Phase 3 — Rate Limiter
12. `platform-starter-rate-limiter` (Lua + Redis + gateway filter + Micrometer).
13. Gateway integration; policies per route; load test with k6 (1k RPS, IP spray, role mix).
14. Failover tests (kill Redis; assert fail-open on reads, fail-closed on writes).

## Phase 4 — Commerce
15. Promotion, Tax, Location Services (with cached address from CDC).
16. Payment Service (Stripe mock, idempotency keys, outbox).
17. Order Orchestrator with Spring Statemachine saga; parallel branches via structured concurrency; DLQ + retry.
18. Delivery Service (carrier mock, tracking events).

## Phase 5 — Engagement
19. Rating Service → Search updates average rating via Kafka.
20. Recommendation Service (pgvector kNN on Product, behavior events in, top-N out).
21. Notification Service (Email/SMS/Push providers abstracted; templating; outbox).
22. Audit Service consuming `audit.events` → S3 Object Lock.

## Phase 6 — Frontend & BFF
23. BFF Web for Next.js 15 (aggregation, SSR-friendly caching).
24. Next.js app: catalog, search, cart, checkout, account. Playwright e2e.

## Phase 7 — Hardening
25. SLO dashboards + Alertmanager rules + runbooks.
26. Chaos tests (Chaos Mesh: kill Payment pod mid-saga; assert compensation).
27. Load tests; HPA on Kafka-lag and p99-latency metrics.
28. Security review: mTLS verification, JWT rotation, dependency CVEs, secrets scan.