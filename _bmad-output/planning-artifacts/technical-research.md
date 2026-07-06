# Technical Research — Event-Driven Ecommerce Reference Platform

**Date:** 2026-07-06  ·  **Author:** BMad Technical Research

> Caveat: Apache Kafka, Spring Statemachine, and Lettuce comparison pages returned 403/404 during automated fetch. Boot 4, Spring Cloud 2025.1, Apicurio, Confluent SR, ES, Next.js, and Redis pages retrieved in full. §5 flags the rest as "re-verify."

---

## 1. Version matrix (mid-2026)

| Component | Recommended | Notes |
|---|---|---|
| **Java** | 25 (LTS, Sep 2025) | Min for Boot 4 GA; required for structured concurrency / scoped values (JEP 453/481). |
| **Spring Boot** | 4.0.x / 4.1.x (Boot 4 GA before **10 Jun 2026**; v4.1.0 + v4.0.7 then) | Boot 4 = Java 17+, Jakarta EE 11, Spring 7. |
| **Spring Cloud** | **2025.1.x "Oakwood" → 2025.1.2** (tracks Boot 4.0.x) | Don't mix trains. Gateway: 5.0.2 / 4.3.5 / 4.2.7 / 4.1.9. |
| **Spring Kafka** | Boot 4 BOM; client 3.7+/4.0.x | `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`. |
| **Apache Kafka** | 4.0+ KRaft default (docs site shows 4.3) | Dev: `KAFKA_NODE_ID=1`, `process.roles=broker,controller`. Prod: ≥3 controllers. |
| **Debezium** | 3.x (`debezium-connector-postgres`, `…-mysql`) | Outbox SMT = `io.debezium.transforms.outbox.EventRouter`. |
| **Apicurio** | **2.6.x** | Confluent SR REST v6/v7 via `io.apicurio.registry.utils.converter.AvroConverter`. |
| **Elasticsearch** | 8.15+ (`elasticsearch-java:8.15+`) | Don't mix 7.x HLRC and 8.x client in same module. |
| **Redis client** | Lettuce 6.3.x (Boot default) | Redisson for distributed objects. |
| **Spring Statemachine** | 4.0.x (Jakarta baseline) | `StateMachineFactory` + JPA persister. |
| **OpenTelemetry** | OTel Java 1.40+ / Boot 4 autoconfig | **One** tracing bridge only. |
| **Next.js** | 15.x (GA Oct 2024) | Node 18.18+. Async `cookies()`/`headers()`/`params`. |

> Pairing rule: Boot 4 ↔ Cloud 2025.1 ↔ Spring 7 ↔ Jakarta 11 ↔ Java 17+ (25 target).

---

## 2. Known traps & gotchas

### Spring Boot 4 vs 3.x
Jakarta EE 11 + Spring 7 baseline; a few autoconfig packages moved. **Pin a known-good BOM; don't float.** Virtual threads still opt-in (`spring.threads.virtual.enabled=true`). `@ConfigurationProperties` records supported; `snake_case`↔`kebab-case` mapping tightened.

### Spring Cloud Gateway
Default **Server WebFlux** is reactive (Netty) — virtual threads don't help inside WebFlux. For a virtual-thread gateway use **Spring Cloud Gateway Server MVC** (Tomcat). Built-in `RequestRateLimiter` is single-bucket / single-key — your custom multi-bucket filter is justified.

### Kafka KRaft
**Set `KAFKA_NODE_ID` explicitly.** Controller quorum must be odd (≥3 in prod). `__transaction_state`/`__consumer_offsets` RF must match cluster or exactly-once refuses to start. Keep `auto.create.topics.enable=false` in prod. Default `num.network.threads=3` is too low for >2k partitions.

### Debezium Outbox SMT
SMT is a **transform after snapshot** — useless during initial snapshot. Plan a backfill (`snapshot.mode=initial_only` then `schema_only`). Configure `route.by.field=aggregatetype` + `topics=<dbserver>.outbox_event`; source table conventionally named `outbox`. **Never delete `debezium_offsets`** without snapshotting source DB first.

### Apicurio + Avro compatibility
Apicurio uses **content-hash IDs**, Confluent SR uses numeric IDs — verify consumers can resolve Apicurio IDs. `BACKWARD_TRANSITIVE`: adding a *required* field breaks BACKWARD/FULL; removing a *required* field breaks BACKWARD; new fields need defaults. **Roll out consumers first, then producers.** Configure compat at artifact level — CDC BACKWARD, business events FULL_TRANSITIVE.

### Elasticsearch 8.x
`text` is analyzed — not sortable, not aggregatable. **Always** map a multi-field (`name` + `name.keyword`). Sorting on `text` silently enables `fielddata` (JVM heap). Cap dynamic field growth with `index.mapping.total_fields.limit` + `dynamic: strict`. Reindex from compacted CDC via alias-swap after mapping changes. For pagination past 10k use `point_in_time` + `search_after`.

### Redis token-bucket
**Time-source bug:** your peek-then-commit script receives `now_ms` from the gateway. NTP drift desyncs bucket math. **Pass `redis.call('TIME')` inside the Lua** and compute elapsed there. Redis Cluster multi-bucket EVALSHA needs **hash tags** (`{userId}:ip`, `{userId}:role`) — otherwise `CROSSSLOT`. `SCRIPT LOAD` + `EVALSHA` with `NOSCRIPT` fallback; `DefaultRedisScript.setResultType(List.class)` handles this. Centralized (Redis) at gateway tier is correct. Make fail-open/fail-closed explicit in code + runbook.

### Spring Statemachine
Use **`StateMachineFactory`** + `persister.persist(machine, result)`; never share a singleton — saga instances collide. JPA entities **must have `@Version`** for optimistic locking. Avoid sub-states >3 levels. Default async executor is `SimpleAsyncTaskExecutor` (thread-per-task) — bind a virtual-thread executor. Listeners must be **idempotent** — a crashed orchestrator re-fires on restart. The project is in low-velocity maintenance; for a 5-year reference consider **Temporal** or hand-rolled.

### OTel / Prometheus / Grafana / Loki / Tempo
**One** tracing bridge per service. Micrometer Tracing Bridge + OTLP → Tempo is the clean path. Kafka propagation needs OTel Kafka interceptor OR Spring Kafka `RecordInterceptor` — not both. `management.metrics.distribution.percentiles-histogram.<metric>=true` for SLO burn-rate alerts. **No user/trace IDs as Loki labels** — use JSON log fields and link from Tempo.

### Next.js 15
**Breaking:** `cookies()`, `headers()`, `params`, `searchParams` are async — codemod `npx @next/codemod@canary next-async-request-api .`. `GET` Route Handlers and Client Router Cache are **not cached by default** — BFF must opt in with `export const dynamic = 'force-static'`. Turbopack dev-only. Server Actions are public endpoints — auth always required.

---

## 3. Alternative libraries — pros/cons

| Concern | Choice | Alternative |
|---|---|---|
| Service discovery on K8s | K8s DNS + Istio | Eureka/Consul (extra control plane) |
| Circuit breaker | Resilience4j (Spring-native) | Sentinel (heavy, dashboard); Failsafe (retry-only) |
| Redis client | Lettuce (thread-safe netty) | Redisson (heavier, has `RRateLimiter`); Jedis (blocking sync) |
| Schema registry | Apicurio 2.6 (OSS, Confluent-API compat) | Confluent SR (paid); Karapace (Python) |
| Outbox relay | Debezium Outbox SMT (CDC in stack) | Spring Modulith poller (no CDC tie-in) |
| Saga | Spring Statemachine (Java-only) | Temporal (durable, multi-lang); Camunda (BPMN) |
| OpenAPI | springdoc 2.8.x (active) | springfox (unmaintained) |
| DB per service | Postgres 17 logical replication | MySQL 8 binlog; CockroachDB |
| Logging | Loki + Promtail (cheap) | ELK (better full-text, more ops) |
| Idempotency | Redis + Postgres fallback | ShedLock (single-node); Hazelcast (extra cluster) |

---

## 4. Reference architectures & canonical sources

- Spring Boot 4.1 — <https://docs.spring.io/spring-boot/reference/4.1/index.html>; Spring Cloud 2025.1 "Oakwood" — <https://spring.io/projects/spring-cloud>; Spring Cloud Gateway — <https://docs.spring.io/spring-cloud-gateway/reference/>; Spring Kafka — <https://docs.spring.io/spring-kafka/reference/>
- Apache Kafka 4.0 KRaft — <https://kafka.apache.org/40/documentation.html#kraft>
- Debezium Outbox SMT — <https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html>; Postgres connector — <https://debezium.io/documentation/reference/stable/connectors/postgresql.html>
- Apicurio Registry 2.6 — <https://www.apicur.io/registry/docs/apicurio-registry/2.6.x/getting-started/assembly-intro-to-the-registry.html>; Confluent SR Avro compat — <https://docs.confluent.io/platform/current/schema-registry/fundamentals/avro.html>
- Elasticsearch mapping — <https://www.elastic.co/docs/manage-data/data-store/mapping>; Spring Statemachine — <https://docs.spring.io/statemachine/docs/current/reference/>
- Resilience4j + Boot — <https://resilience4j.readme.io/docs/getting-started-3>; OpenTelemetry Java — <https://opentelemetry.io/docs/languages/java/>; springdoc-openapi — <https://springdoc.org/>; Next.js 15 — <https://nextjs.org/blog/next-15>; Redis rate limiting — <https://redis.io/tutorials/howtos/ratelimiting/>
- Java 25 — <https://openjdk.org/projects/jdk/25/>; JEP 453 (Structured Concurrency) — <https://openjdk.org/jeps/453>; JEP 481 (Scoped Values) — <https://openjdk.org/jeps/481>

---

## 5. Feasibility flags

**High confidence — keep as designed:** DB-per-service + Outbox + Debezium SMT (canonical). KRaft (GA at 4.0). Apicurio 2.6 + AvroConverter. Virtual threads + structured concurrency in Java 25. Token-bucket + Lua + Redis (modulo §2.7 time-source fix).

**Medium confidence — verify before committing:** Spring Statemachine 4.0.x on Java 25 works but is low-velocity — prefer Temporal or hand-rolled for 5-year horizon. SCG Server MVC wins for virtual threads but has fewer community filters; audit. KRaft prod: ensure Helm creates topics with correct partitions and overrides compose `RF=1`. Debezium `snapshot.mode`: `initial_only` then `schema_only`; avoid `when_needed`.

**Low confidence / risky:** Boot 4 is recent; Spring Cloud sub-projects shipped breaking changes — **pin a BOM**. Java 25 + older annotation processors (Lombok, MapStruct) emit `--release 17` bytecode — verify in CI.

**Faster shortcuts for a reference impl (not prod):**
1. **Skip Debezium in v0** — Spring Modulith outbox poller + `KafkaTemplate` gives same outbox semantics without a Connect cluster.
2. **Skip Apicurio; use plain JSON + Jackson** — lose schema-evolution safety, gain 1-week speed.
3. **Skip Statemachine; hand-roll orchestrator** (sealed types + structured concurrency).
4. **Skip Tempo** — Grafana Cloud free tier or log spans; one less thing to operate.
5. **Keep SCG WebFlux default** + `spring.threads.virtual.enabled=true`; switch to Server MVC only if benchmarks justify.
6. **Use Resilience4j defaults everywhere** — tune per service once SLOs exist.

**Honest uncertainty:** Kafka 4.0 docs, Spring Statemachine reference, and Lettuce comparison pages didn't return content during fetch; KRaft gotchas draw on Apache release notes + prior ops knowledge — re-verify before publishing. Boot 4 migration guide content was unavailable; pairings above are confirmed but specific autoconfig-package moves need re-check against official "What's New" before migration. Lettuce vs Redisson reflects current consensus — benchmark against your virtual-thread workload before final choice.

---

## One-paragraph summary

The proposed stack — **Java 25 + Spring Boot 4 + Spring Cloud 2025.1 + Kafka 4 (KRaft) + Debezium 3 + Apicurio 2.6 + Elasticsearch 8 + Redis 7 + Spring Statemachine + OTel/LGTM + Next.js 15** — is internally coherent, uses only currently-supported LTS versions, and matches canonical reference patterns (transactional outbox via Debezium SMT, distributed rate limiting via Redis Lua, orchestrated saga for checkout). Biggest correctness risks: (1) gateway wall-clock instead of `redis.call('TIME')` in the rate-limiter Lua, (2) SCG WebFlux vs the virtual-thread-friendlier Server MVC, (3) Spring Statemachine's low-velocity maintenance, (4) Boot 4 recency. Highest-leverage shortcuts: defer Debezium to Phase 2 (Spring Modulith outbox + KafkaTemplate), defer Apicurio alongside Debezium, hand-roll the saga orchestrator instead of Spring Statemachine. Uncertainty around KRaft gotchas and Lettuce/Redisson — flagged for re-verification.