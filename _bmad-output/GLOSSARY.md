---
audience: all agents
project: side-project
date: 2026-07-06
how-to-use: lookup any acronym / domain term. Maintained alongside AGENT-ONBOARDING.md.
---

# Glossary — side-project

> **Convention:** Acronyms defined once, used everywhere. If a term is in this glossary, use the definition here; don't redefine.
> **Add new terms** when first used in any artifact; this is the single source for vocabulary.

---

## 1. Architecture & Pattern

| Term | Full form | Definition |
|---|---|---|
| ADR | Architecture Decision Record | A binding technical decision (e.g., "we use Spring Modulith outbox"). 26 ADRs in `architecture.md`. |
| DDD | Domain-Driven Design | Software design approach that models code around business domains. |
| BFF | Backend for Frontend | A thin server-side layer between frontend and services, custom per frontend surface. |
| Saga | — | Long-running transaction broken into local steps with compensating actions on failure. Used for checkout. |
| Outbox | Transactional Outbox | Same-transaction insert to an outbox table; a separate bridge publishes to Kafka. Avoids dual-write problem. |
| CDC | Change Data Capture | Streaming DB changes (insert/update/delete) to downstream consumers. Used for catalog → search index. |
| CDC Bridge | — | A poller that reads the outbox table and publishes to Kafka. (Per architecture ADR-14: Modulith outbox bridge.) |
| Event Sourcing | — | Storing all state changes as an append-only event log. Used in Order aggregate. |
| CQRS | Command Query Responsibility Segregation | Separating writes (commands) from reads (queries). Applied via ES for read-side. |
| KISS | Keep It Simple, Stupid | The architecture's principle: simple monolith over clever microservice. |

## 2. Sprint + BMad workflow

| Term | Full form | Definition |
|---|---|---|
| BMad | Business Model Agile Driven | The planning methodology this project follows. |
| PRFAQ | Press Release FAQ | Document used to validate product/feature before building. |
| PRD | Product Requirements Document | The "what to build" doc. 82 FRs, 24 NFRs, 5 Qs. |
| ADR | (see above) | — |
| NFR | Non-Functional Requirement | Performance, security, etc. constraints. 24 unique IDs. |
| FR | Functional Requirement | Testable behavior. 82 total. |
| AC | Acceptance Criteria | Given/When/Then testable conditions. |
| JTBD | Jobs to Be Done | User need framing. Used in PRD §2. |
| UJ | User Journey | Named-persona narrative flow. Used in PRD §3. |
| Sprint | — | Time-boxed iteration. 11 sprints in this project (0..10). |
| Epic | — | A user-value-focused cluster of stories. 11 in this project. |
| Story | — | Dev-sized unit of work with Given/When/Then AC. 57 in this project. |
| Frontmatter | — | YAML metadata at top of markdown artifacts. |
| `addendum.md` | — | Companion file to `prd.md`; rejected alternatives + Q&A. |
| `architecture-detail.md` | — | Companion file to `architecture.md`; per-ADR deep dives. |
| `sprint-status.yaml` | — | Tracking file; per-story status: backlog / ready-for-dev / in-progress / review / done. |
| `(b)` `(c)` | — | User-choice menu options presented in workflow steps. |

## 3. Technical — Java + Spring

| Term | Full form | Definition |
|---|---|---|
| JPA | Java Persistence API | ORM standard, used via Hibernate 6+ in this project. |
| JDBC | Java Database Connectivity | Low-level DB access. Use HikariCP for pooling. |
| JNDI | Java Naming and Directory Interface | (Rarely used in Spring Boot; for legacy app server integration.) |
| AOT | Ahead-of-Time | Native compilation (GraalVM). Optional in v1. |
| JVM | Java Virtual Machine | Runs the compiled Java bytecode. Java 25 LTS in v1. |
| LTS | Long-Term Support | Java versions with multi-year support (Java 21, 25). |
| K8s | Kubernetes | Container orchestration. Used in production (per ADR-17). |
| Helm | — | K8s package manager. Charts per service in `helm/`. |
| ArgoCD | — | GitOps continuous delivery for K8s. Per ADR-17. |
| OPA | Open Policy Agent | Policy engine; admission control for K8s/Kafka. Per ADR-19. |
| Rego | — | OPA's policy language. |
| mTLS | Mutual TLS | Both client and server present certificates. Service-to-service. |
| JWT | JSON Web Token | Stateless auth token. RS256 signed. |
| RS256 | RSA-SHA-256 | Asymmetric signature algorithm. |
| HMAC | Hash-based Message Authentication Code | Per ADR-20: HS256, event signing. |
| HS256 | HMAC-SHA-256 | The HMAC algorithm used for event signatures. |
| JCS | JSON Canonicalization Scheme | RFC 8785; for deterministic event signing. |
| RFC | Request for Comments | IETF standard. 8785 = JCS. |
| LOB | Line of Business | (General term; e.g., "the e-commerce LOB") |
| SLO | Service Level Objective | Target metric (e.g., 99.9% availability). |
| SLA | Service Level Agreement | Promise to customer (not used in v1 — reference impl). |
| SRE | Site Reliability Engineering | Operational discipline. Per NFR-AVAIL/OBS. |
| API | Application Programming Interface | Service-to-service contract. |
| HTTP | HyperText Transfer Protocol | Application protocol. HTTPS in production. |
| TLS | Transport Layer Security | HTTPS = HTTP + TLS. TLS 1.3 minimum. |
| CORS | Cross-Origin Resource Sharing | Browser security policy. Per NFR-SEC-1. |
| CSRF | Cross-Site Request Forgery | Browser-based attack vector. Mitigated by SameSite cookies. |
| XSS | Cross-Site Scripting | Injecting JS via user input. Mitigated by React auto-escape + CSP. |
| SSR | Server-Side Rendering | Next.js default mode. |
| RSC | React Server Components | Next.js 15 App Router feature. |
| CSR | Client-Side Rendering | Browser renders. |
| SPA | Single Page Application | — |
| SSG | Static Site Generation | (Not used in v1 — dynamic data.) |

## 4. Data & Persistence

| Term | Full form | Definition |
|---|---|---|
| RDBMS | Relational Database Management System | PostgreSQL in v1. |
| SQL | Structured Query Language | — |
| DDL | Data Definition Language | `CREATE TABLE`, `ALTER TABLE`, etc. |
| DML | Data Manipulation Language | `INSERT`, `UPDATE`, `DELETE`. |
| ORM | Object-Relational Mapping | JPA / Hibernate. |
| SQLi | SQL Injection | Vulnerability; prevented by parameterized queries. |
| JSONB | JSON Binary | PostgreSQL's binary JSON type. Used for `attributes` column. |
| JSON | JavaScript Object Notation | Text format; here used in Avro / wire format. |
| Avro | — | Apache Avro; binary schema-based serialization. Per ADR-04. |
| Schema | — | (1) Avro schema; (2) Postgres schema; (3) Generic: structure. |
| Schema Evolution | — | Adding/removing fields in Avro without breaking consumers. |
| Backward Compat | — | New schema can read old data. |
| Forward Compat | — | Old schema can read new data. (Both required per ADR-15.) |
| LSN | Log Sequence Number | Postgres replication cursor. |
| CDC | (see above) | — |
| WAL | Write-Ahead Log | Postgres transaction log. |
| Soft Delete | — | Mark row as deleted (isDeleted=true) but keep in DB. |
| Hard Delete | — | Permanently remove row. |
| `@SoftUk` | Soft Unique Key | util annotation; ensures unique constraint excludes soft-deleted rows. |
| CDC Bridge | (see above) | — |
| Event Sourcing | (see above) | — |

## 5. Messaging (Kafka)

| Term | Full form | Definition |
|---|---|---|
| KRaft | Kafka Raft | Consensus protocol; no Zookeeper. Kafka 4 default. |
| ZK / ZooKeeper | — | Old Kafka consensus. **NOT used in v1.** |
| Topic | — | Kafka message category (e.g., `orders.placed`). |
| Partition | — | A topic is split into partitions for parallelism. |
| Offset | — | Kafka consumer's position in a partition. |
| Producer | — | Code that publishes to Kafka. |
| Consumer | — | Code that subscribes to Kafka. |
| Consumer Group | — | Multiple consumers sharing work for a topic. |
| Retention | — | How long Kafka keeps messages (e.g., 7 days). |
| Exactly-Once | — | Strong delivery guarantee. Complex; v1 uses at-least-once + consumer dedup. |
| At-Least-Once | — | Default Kafka semantics. May deliver duplicates. |
| At-Most-Once | — | May lose messages. |
| Idempotency Key | — | Stable key for retry-safe external calls. Per ADR-11. |
| `webhook_dedup` | — | Per-service table; dedupes Stripe webhooks on `event.id`. |
| `processed_event` | — | Per-service table; dedupes consumed events. |
| Schema Registry | — | Service for Avro schemas. Apicurio 2.6 in v1. |
| KRaft | (see above) | — |
| Outbox Bridge | — | Polls outbox table → publishes to Kafka. (Per architecture ADR-14.) |
| Backpressure | — | When consumer lag grows, slow down producer. Per outbox bridge ops. |

## 6. Search (Elasticsearch)

| Term | Full form | Definition |
|---|---|---|
| ES | Elasticsearch | Search index. v1 = ES 8.x. |
| Index | — | Searchable collection (e.g., `catalog_vi_prod`). |
| Analyzer | — | Tokenizer + filter pipeline. `vi_text` per FR-52. |
| Tokenizer | — | Splits text into tokens (e.g., `standard`). |
| Token Filter | — | Transforms tokens (e.g., `lowercase`, `asciifolding`, `metaphone`). |
| BM25 | Best Match 25 | Default ES ranking algorithm. |
| Asciifolding | — | Converts non-ASCII to ASCII (e.g., `á` → `a`). |
| Phonetic | — | Matches by sound (e.g., `metaphone`). |
| Diacritic | — | Accent marks (`á`, `đ`, `ư` in Vietnamese). |
| Index Alias | — | Logical name (`catalog_search`) → physical index (`catalog_vi_prod`). |
| Alias Swap | — | Atomic index migration: switch alias from old to new index. |
| Reindex | — | Copy docs from one index to another. |

## 7. Security

| Term | Full form | Definition |
|---|---|---|
| PCI-DSS | Payment Card Industry Data Security Standard | Card-handling security standard. v1 scope = minimal (Stripe Elements iframe). |
| PDPD | Personal Data Protection Decree | Vietnam's data protection law (Decree 13/2023/NĐ-CP). |
| GDPR | General Data Protection Regulation | EU data protection. (Future expansion, not v1.) |
| PSD2 | Payment Services Directive 2 | EU directive. SCA = Strong Customer Authentication. |
| SCA | Strong Customer Authentication | 3DS step-up. |
| 3DS | 3-Domain Secure | Card authentication protocol. Per FR-27. |
| PCI Scope | — | Systems that touch card data. We minimize via Stripe Elements iframe. |
| PAN | Primary Account Number | The 13–19-digit card number. **NEVER log this.** |
| CVV | Card Verification Value | The 3–4 digit security code. **NEVER log this.** |
| BIN | Bank Identification Number | First 6 digits of a card. Used for fraud detection. |
| Bcrypt / Argon2id | — | Password hashing algorithms. We use Argon2id. |
| SAST | Static Application Security Testing | Code-level security scan. |
| DAST | Dynamic Application Security Testing | Runtime security scan. |
| Pen Test | Penetration Test | External security assessment. |
| RBAC | Role-Based Access Control | Per FR-74. customer / staff / admin / service-account. |
| SSRF | Server-Side Request Forgery | Vulnerability; relevant for any outbound HTTP from services. |
| XSS | (see Java section) | — |
| CSRF | (see Java section) | — |
| OPA | (see above) | — |
| Vault | HashiCorp Vault | Secret management. Per ADR-18. |

## 8. Observability

| Term | Full form | Definition |
|---|---|---|
| OTel | OpenTelemetry | Vendor-neutral instrumentation framework. |
| OTel Collector | — | Receives traces/metrics/logs from services; exports to backends. |
| LGTM | Loki + Grafana + Tempo | Logging, viz, tracing stack. (Plus Mimir for metrics.) |
| Tempo | — | Distributed tracing backend (used with Grafana). |
| Loki | — | Log aggregation backend. |
| Grafana | — | Visualization platform. |
| Prometheus | — | Metrics backend with PromQL query language. |
| Mimir | — | Long-term Prometheus-compatible storage. |
| RED | Rate / Errors / Duration | Three core metrics for any service. |
| USE | Utilization / Saturation / Errors | Resource-focused metrics. |
| SLI | Service Level Indicator | Measured metric (e.g., p99 latency). |
| SLO | Service Level Objective | Target for SLI (e.g., p99 < 100ms). |
| Error Budget | — | Allowed failure rate; 1 - SLO. |
| Burn Rate | — | How fast error budget is consumed. |
| Trace | — | One request's journey across services. |
| Span | — | One unit of work in a trace. |
| Span Context | — | traceId + spanId propagated across services. |
| W3C Trace Context | — | Standard for trace propagation (W3C spec). |
| OTLP | OpenTelemetry Protocol | Wire format for OTel. |
| Span Drop | — | When OTel can't keep up, spans are dropped. Per NFR-OBS-4. |
| Cardinality | — | Number of unique label combinations in metrics. Bounded per NFR-OBS-3. |
| Card. Bound | Cardinality Bound | — |

## 9. Resilience / Chaos

| Term | Full form | Definition |
|---|---|---|
| SRE | (see above) | — |
| Chaos Engineering | — | Deliberately injecting failure to test system resilience. |
| Chaos Mesh | — | K8s-native chaos engineering tool. Per ADR-16. |
| Game Day | — | Scheduled time to run all chaos experiments. Per OBSERVABILITY-RUNBOOK §8. |
| Circuit Breaker | — | Pattern that stops calling a failing downstream. Resilience4j in v1. |
| Fallback | — | What to do when downstream fails. E.g., fail-open for rate-limiter. |
| Backpressure | — | (see Kafka section) — for consumer lag. |
| Retry | — | Re-attempting a failed operation. With exponential backoff. |
| Jitter | — | Random delay added to retries to avoid thundering herd. |
| Bulkhead | — | Isolating resources so one failing component doesn't take down others. |
| Idempotency | (see above) | — |

## 10. Domain (E-commerce)

| Term | Full form | Definition |
|---|---|---|
| B2C | Business-to-Consumer | Direct sales to end users. v1 = B2C only. |
| B2B | Business-to-Business | Wholesale / inter-company. Deferred to v2. |
| Marketplace | — | Multi-seller platform. Deferred to v2. |
| PDP | Product Detail Page | E.g., `/products/iphone-15-pro`. |
| PLP | Product Listing Page | E.g., `/products?category=phones`. |
| SKU | Stock Keeping Unit | Per-variant identifier. Hash of attributes. |
| UoM | Unit of Measure | kg, cm, etc. (Future.) |
| COGS | Cost of Goods Sold | (Not relevant for v1 — ref impl.) |
| MRR | Monthly Recurring Revenue | (Not relevant — not SaaS.) |
| GMV | Gross Merchandise Value | (Reference for capacity planning.) |
| VAT | Value Added Tax | Vietnam: 10% standard. |
| PII | Personally Identifiable Information | Anything that identifies a user. Per PDPD. |
| PCI Scope | (see Security section) | — |
| RMA | Return Merchandise Authorization | Return workflow. |
| 3PL | Third-Party Logistics | Carrier (e.g., GHN, GHTK). |
| SLA | (see above) | — |

## 11. VN-specific

| Term | Full form | Definition |
|---|---|---|
| VN | Vietnam | Country. v1 is VN-first. |
| VNPDPD | Vietnam PDPD | Per FR-46 / LC-01. |
| VN tax | Vietnamese Tax Authority | Per FR-78 / ADR-26. |
| MST | Mã Số Thuế | Vietnam Tax Code (14 digits). |
| `vietnam_tax_authority_credential` | — | Schema for Q5 closure (per architecture §"Detail: ADR-26"). |
| KH | Kế hoạch (plan) | (Vietnamese; not commonly used in code.) |
| NS | Ngân sách (budget) | — |
| HCM | Ho Chi Minh City | Major Vietnam city; default warehouse. |
| HN | Ha Noi | Another major city; secondary warehouse. |
| VNĐ / VND | Vietnam Dong | Currency. Stored in cents (e.g., 100000 = 1,000 VND). |
| Q5 | — | PRD Open Question #5: tax-invoice specifics. Now closed via ADR-26. |
| LC-03 | — | Risk: Vietnamese tax-invoice compliance. Mitigated by ADR-26. |
| AT-01 | — | Risk: card testing. Mitigated by ADR-13 + ADR-24. |
| AT-02 | — | Risk: credential stuffing. Mitigated by Story 5.4. |
| AT-03 | — | Risk: CDC event injection. Mitigated by ADR-20. |
| DI-01 | — | Root cause: oversell race. Mitigated by Story 1.6. |
| DI-02 | — | Root cause: payment double-capture. Mitigated by Story 3.1. |
| DI-03 | — | Root cause: Debezium outbox duplicates. Mitigated architecturally (no Debezium in v1). |
| DI-07 | — | Root cause: cumulative refund > payment. Mitigated by Story 7.3. |
| DI-09 | — | Root cause: soft-delete uniqueness regression. Mitigated by Story 1.8. |
| LC-01 | — | Risk: PDPD data export. Mitigated by Story 5.2. |
| OP-04 | — | Operational: Redis OOM during sale. Mitigated by NFR-AVAIL-4. |
| OP-05 | — | Operational: Snowflake worker-id collision. Mitigated by ADR-22. |

---

## 12. Other

| Term | Full form | Definition |
|---|---|---|
| WIP | Work In Progress | — |
| POC | Proof of Concept | — |
| MVP | Minimum Viable Product | (We're explicitly NOT building an MVP — full product per Q3.) |
| RFC | Request for Comments | IETF standards. 8785 = JCS. |
| RTO | Recovery Time Objective | (See DEVOPS-RUNBOOK §15 — DR.) |
| RPO | Recovery Point Objective | — |
| SLA | (see above) | — |
| R&D | Research and Development | — |
| QA | Quality Assurance | (See QA-AGENT-HANDBOOK.md.) |
| TBD | To Be Determined | (Generally resolved; this doc is a knowledge base.) |
| TBR | To Be Reviewed | — |
| PR | Pull Request | — |
| CI | Continuous Integration | Per `DEVOPS-RUNBOOK.md` §13. |
| CD | Continuous Delivery / Deployment | — |
| GitOps | — | Git as single source of truth for ops. Per ADR-17. |

---

## 13. How to add a new term

1. Add it to the right category above (alphabetical within category)
2. Format: `| Term | Full form | Definition |` (3 columns, pipes)
3. If the term is a project-specific code (R-XX, DI-XX, ADR-NN, Q-X), put it in the VN-specific section (#11)
4. If it's a Spring/Java term, put in #3
5. If it's security, put in #7
6. Keep definitions to 1 sentence (longer only for technical terms that need context)

---

## 14. Cross-references

- **For full architecture decisions:** `architecture.md`
- **For ADRs:** `ADR-INDEX.md`
- **For risk register:** `RISK-REGISTER.md`
- **For new dev onboarding:** `SPRINT-0-ONBOARDING.md`
- **For QA workflows:** `QA-AGENT-HANDBOOK.md`
- **For all quickref index:** `AGENT-ONBOARDING.md` (entry point)
