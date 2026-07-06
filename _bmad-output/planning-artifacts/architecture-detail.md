---
title: 'Architecture — Companion Detail'
status: final
created: 2026-07-06
updated: 2026-07-06
companion_to: architecture.md
---

# Architecture — Companion Detail

> **Purpose:** Per BMad convention (mirroring `prd.md` + `addendum.md`), this file holds the **deep-dive detail** that supports each ADR in `architecture.md` without padding the main narrative.  
> **Main file:** `_bmad-output/planning-artifacts/architecture.md`  
> **Companion to:** Architecture decisions (ADR-01..26).

---

### Detail: ADR-01 (Q1 — was the phase-blocker; now resolved)

**Decision:** Spring Modulith outbox is the v1 saga architecture.

**Why this resolves Q1:**

- Q1 in the PRD offered two candidates: Spring Modulith outbox (default Green-Hat) vs Spring Statemachine + Debezium outbox + Kafka transactions (alternate).
- Per PRD §"Addendum" A2 — Option A is the default unless explicit user signal otherwise.
- The brainstorming `[CHK-C]` favors a single CheckoutService owning the Stripe PaymentIntent lifecycle directly, with no separate PaymentService hop; Modulith's intra-process saga is a natural fit.
- Modulith outbox eliminates the Debezium+Kafka-transactions operational complexity (R-04) for v1. Kafka transactions can be added later if/when services split.
- The reference-impl audience benefits from a working monolith more than from a fragile microservice.

**Implementation guidance for epics phase:**

- All 13 services start as **logical modules** in a single Modulith deployment unit.
- Inter-module communication: direct method calls (intra-JVM) for synchronous flows; outbox + Kafka for cross-domain events.
- A saga is implemented as a state machine on the order aggregate (Spring Modulith's `@ApplicationModule`-scoped state machine, NOT Spring Statemachine the library).
- Each service has its own outbox table; the Modulith outbox bridge publishes outbox rows to Kafka topics.

#### Saga state storage + transition table (ADR-12 binding detail)

**State column:** The `order` aggregate has a `state` column (enum: `CREATED`, `STOCK_RESERVED`, `PAYMENT_PENDING`, `PAID`, `PACKED`, `SHIPPED`, `DELIVERED`, `CANCELLED`, `FAILED`, `COMPENSATED`). The order's `version` column is used for optimistic concurrency (incremented on every transition).

**State transition log:** Every state change appends a row to the `order_state_transition` table (columns: `id`, `order_uuid`, `from_state`, `to_state`, `saga_step`, `event_id`, `created_at`). The current state is the last row's `to_state`; the log is the audit trail and the saga-recovery source.

**Saga recovery after crash:** If the Modulith restarts mid-saga, on startup:
1. Find orders with `state IN (STOCK_RESERVED, PAYMENT_PENDING, PAID)` and `updated_at < now() - 5min` (stuck-orders query).
2. For each stuck order, re-derive the saga step from the last `order_state_transition` row.
3. If a side-effect is missing (e.g., Stripe charge not confirmed), re-trigger the side-effect using the existing saga's idempotency key.
4. If a compensator is needed (e.g., payment captured but order not advanced), run the compensator.

This guarantees **at-least-once execution with idempotent handlers** — the same guarantee that distributed-saga patterns provide, but without network round-trips.

**Saga transition table (state → events emitted / consumed):**

| From state | To state | Trigger | Event emitted | Side effect |
|---|---|---|---|---|
| (none) | `CREATED` | Cart submit | `order.created` | None |
| `CREATED` | `STOCK_RESERVED` | InventoryService confirms stock | `order.stock_reserved` | Inventory ledger entry |
| `STOCK_RESERVED` | `PAYMENT_PENDING` | Stripe PaymentIntent created | `order.payment_pending` | None |
| `PAYMENT_PENDING` | `PAID` | Stripe `payment_intent.succeeded` webhook | `order.paid` | Stripe capture |
| `PAYMENT_PENDING` | `FAILED` | Stripe `payment_intent.payment_failed` | `order.failed` | Inventory release |
| `PAID` | `PACKED` | Warehouse marks packed | `order.packed` | Packing slip |
| `PACKED` | `SHIPPED` | Carrier accepts | `order.shipped` | Carrier API call |
| `SHIPPED` | `DELIVERED` | Carrier confirms delivery | `order.delivered` | None |
| Any non-terminal | `CANCELLED` | User cancel before SHIPPED | `order.cancelled` | Stripe refund + inventory release |
| `FAILED` | `COMPENSATED` | Compensators complete | `order.compensated` | None |

**Compensation paths:** `STOCK_RESERVED → CANCELLED` releases inventory; `PAID → CANCELLED` calls Stripe refund + releases inventory; `PAYMENT_PENDING → CANCELLED` cancels Stripe PaymentIntent. All compensations emit their own events; all are idempotent on `(order_id, step_name)`.

**Reversibility path:**

- When a service needs to scale independently, extract it: create a separate Spring Boot deployment, copy the module's code + its outbox table, point the new service at the same Kafka topic. The other services' Modulith `EventListener` annotations get rewritten to consume from Kafka.
- This split-path is a planned v2 evolution; not a v1 deliverable.

#### Multi-tenant disposition (util/ reuse clarification)

The `util/` library ships multi-tenant primitives: `TenantInterceptor`, `TenantRoutingDataSource`, `TenantStorage`, `TenantNotFoundException`, `TenantResolvingException`. The PRD and ADR-07 both default to **B2C, single-tenant for v1**. Disposition:

- **For v1 (B2C):** the multi-tenant code is **kept but not activated**. Specifically:
  - `TenantInterceptor` is NOT registered in v1. `WebConfiguration` in `util/config/tenant/` is excluded from the v1 component scan via `@SpringBootApplication(exclude=...)` or by not adding the tenant module to the auto-config imports.
  - The schema includes a `tenant_id` column on every per-service table, but it is always `'default'`. This makes v1→v2 (multi-tenant) a configuration change, not a migration.
  - `TenantRoutingDataSource` is replaced by a single `DataSource` bean in v1 (no routing).
- **For v2 (multi-tenant):** when the marketplace model is added (per Q3 resolution — B2C v1, marketplace v2), the tenant code activates by:
  - Re-registering `TenantInterceptor` in the v1 config.
  - Switching the `DataSource` to `TenantRoutingDataSource` with a `Map<String, DataSource>` keyed by tenant.
  - Setting up the `x-tenant` HTTP header convention.
  - The `tenant_id` column on each table is no longer `'default'` but the actual tenant.
- **Why keep the code:** removing the multi-tenant code from util/ would be a breaking change to anyone using util/ outside this project (it's a shared library). Keeping it dormant in v1 is a non-breaking choice.
- **Documentation:** add a `tenant-disabled-v1.md` note in `util/config/tenant/` explaining how to activate for v2.

#### Implementation notes (Sprint 0 — R-01 fix, Story 0.1)

Version pinning decided during Sprint 0 R-01 fix; recorded here per Story 0.1 AC #5.

- **Spring Boot BOM:** `org.springframework.boot:spring-boot-dependencies:4.0.0` — imported in `util/pom.xml` `<dependencyManagement>` with `<type>pom</type><scope>import</scope>`. Pinned exactly; do not float to `4.x` or to a `-SNAPSHOT`.
- **Spring Cloud BOM:** `org.springframework.cloud:spring-cloud-dependencies:2025.1.0` — imported alongside the Boot BOM in `util/pom.xml`. Coordinated train release; do not mix with mismatched Boot/Cloud versions.
- **Java baseline:** `<release>25</release>` on `maven-compiler-plugin`; `<java.version>25</java.version>` in `util/pom.xml` properties. Java 25 LTS.
- **Maven baseline:** 3.9+ required (system used during verification: 3.9.16).
- **Why inlined in `util/pom.xml`:** util was originally a child of `vn.vnpt:be:0.0.1-SNAPSHOT</parent>` (relativePath `../pom.xml`); that parent does not exist in this repo, so `mvn install` failed with `ParentNotFoundException`. Story 0.2 will bootstrap a multi-module root `pom.xml` and may move these imports up; until then they live in `util/pom.xml`.
- **Scope for downstream services:** every `services/<name>/` module that imports `util` will transitively inherit both BOMs through `util`'s `dependencyManagement`. Service-specific poms should NOT re-import these BOMs — duplication risks version skew.

### Detail: ADR-04 (Event-driven foundation)

- **Event catalog** follows `domain-research.md` `aggregate.action` naming: `orders.lifecycle`, `payment.lifecycle`, `inventory.lifecycle`, `shipment.lifecycle`, `returns.lifecycle`, `catalog.lifecycle`, `customer.lifecycle`, `cart.lifecycle`, `pricing.lifecycle`, `review.lifecycle`, `notification.lifecycle`.
- **Schema:** Avro. Schemas registered in Apicurio 2.6 with strict backward + forward compatibility.
- **CI gate:** every Avro PR runs `apicurio-registry compatibility check` against the prior version. Incompatible = PR rejected.
- **Outbox:** every service has an `outbox` table. Writes to outbox + business state are in the same transaction. Modulith outbox bridge polls outbox table, publishes to Kafka.
- **Consumer idempotency:** every event consumer is idempotent on `event.id` (NFR-IDEM-1). Per-service `processed_event` table tracks consumed `event.id`.

#### Elasticsearch read-side — per-locale index strategy (FR-51, FR-52 binding)

The PRD requires Vietnamese diacritic-tolerant search with per-locale index naming. Architecture:

- **Index naming:** `catalog_<locale>_<env>`, e.g., `catalog_vi_prod`, `catalog_en_prod`, `catalog_vi_staging`. The locale is part of the name (not a filter), so analyzers and language-specific stopwords are configured per index.
- **Per-locale index analyzer:**
  - `vi` (default): custom analyzer with `lowercase` + `asciifolding` token filter + Vietnamese diacritic folding token filter (built-in ES `analysis-vn` plugin OR custom token filter if plugin unavailable) + phonetic fallback (`phonetic` filter with `metaphone` encoder).
  - `en`: standard analyzer (lowercase + asciifolding + stop).
- **Alias pattern:** `catalog_search` is the read alias; concrete index behind the alias is `catalog_vi_<env>` for current locale. Reindex workflow (NFR-MIG-3): write to new index with new mapping → dual-write to both indices → switch alias → drop old.
- **Vietnamese analyzer config:**

```yaml
# services/search/src/main/resources/elasticsearch/vi-analyzer.json
{
  "analysis": {
    "analyzer": {
      "vi_text": {
        "tokenizer": "standard",
        "filter": ["lowercase", "asciifolding", "vi_diacritic_fold", "vi_stop"]
      }
    },
    "filter": {
      "vi_diacritic_fold": {
        "type": "asciifolding",
        "preserve_original": true
      },
      "vi_stop": {
        "type": "stop",
        "stopwords": ["_vietnamese_"]
      }
    }
  }
}
```

- **Bootstrapping:** SearchService creates the per-locale indices at startup if absent (using `indices.exists` check, idempotent). Apicurio schema-registration policy (ADR-19) requires index templates to declare the analyzer config.

#### Outbox bridge operational details (ADR-14)

- **Poll interval:** 500ms default; configurable per service via `outbox.poll-interval-ms`.
- **Batch size:** 100 events per poll; default.
- **Failure mode when Kafka is down:**
  - Outbox rows are NOT deleted until the publish is acked.
  - Bridge retries with exponential backoff (1s, 2s, 4s, ..., max 30s).
  - After 10 consecutive failures, the bridge emits a metric `outbox.publish.failures` and a Sentry/Telegram alert (per `util/telegram/TelegramBotAPIUtil`).
  - Outbox table grows during outage; bounded by a sweeper that drops rows older than `outbox.retention-days` (default 7 days) **only** when `outbox.published_at IS NOT NULL`.
- **Backpressure:** if outbox row count > 10k, poll rate decreases to 5s and a warning fires. Above 100k, page on-call.
- **Ordering guarantee:** events are published in `outbox.id` order per aggregate, so `orders.placed` and `orders.placed_v2` from the same order are emitted in causal order.
- **Sweeper:** a separate `@Scheduled` job (5 min interval) deletes published rows older than retention.

### Detail: ADR-09 (API style)

- **Service-to-service:** synchronous via HTTP/REST for the few cases where a service genuinely needs a query (e.g., CheckoutService querying InventoryService for live stock). Resilience4j circuit breakers; mTLS; OTel trace propagation.
- **Service-to-frontend (BFF pattern):** each frontend surface (storefront, admin) has a thin BFF (Backend-for-Frontend) layer that:
  - Aggregates responses from multiple services
  - Handles auth/session translation
  - Implements storefront-specific rate limiting and CDN caching
  - Returns DTOs shaped for the UI (not 1:1 service contracts)
- **Public API:** none in v1. The reference is a deployed product, not a public API platform.

### Detail: ADR-10 (Frontend)

- **Framework:** Next.js 15 with App Router + React Server Components + Server Actions for forms.
- **Language:** TypeScript (strict mode).
- **Styling:** Tailwind CSS for the storefront; component library to be selected (suggest: shadcn/ui as a starting point).
- **State management:** TanStack Query for server state; React useState / useReducer for local UI state; avoid Redux unless complexity demands.
- **Forms:** React Hook Form + Zod for validation (Zod schema shared with backend where possible).
- **i18n:** next-intl with `vi` as default; `en` as fallback.
- **Observability:** OTel browser SDK, sent to the same LGTM stack.

### Detail: ADR-20 (HMAC event signing scheme)

Per AT-03 root cause (CDC event injection), every event must carry a per-service HMAC signature that consumers verify. Concrete scheme:

- **Algorithm:** HMAC-SHA-256 (HS256). NIST-approved, fast, supported by all standard crypto libraries.
- **Key derivation:** per-service shared secret stored in HashiCorp Vault at path `secret/events/hmac/<service-name>`. Key is 32 bytes (256 bits); generated once at service bootstrap.
- **Key rotation:** quarterly (90 days). Old key remains valid for 7 days after rotation to allow in-flight events to verify; both keys are checked during the overlap window. New key fetched from Vault on `SIGHUP` or service restart.
- **Signed payload:** the event envelope (everything except the `signatures` field) is serialized as canonical JSON (JCS — RFC 8785) and HMAC-SHA-256'd with the service's secret.
- **Signature format:** in the envelope, the `signatures.service` field carries the signing service name; `signatures.hmac_sha256` is a base64url-encoded HMAC. Optional `signatures.key_id` field carries the Vault key version (so consumers can look up the right key for verification).
- **Consumer verification:** on every consumed event, the consumer:
  1. Looks up the producer's secret in Vault (cached for 5 min).
  2. Recomputes the HMAC over the canonical JSON.
  3. Constant-time compares with the received signature.
  4. Rejects the event (and emits a security alert) if signatures don't match.
- **Implementation:** provided as a small Java utility in `util/events/HmacEventSigner.java` (new addition) — signature generation in producer code, signature verification in consumer-side outbox bridge.
- **Failure mode:** if Vault is unreachable, the producer FAILS LOUD (refuses to publish events) rather than signing with a stale or default key. This is a security primitive; failure is a security event.

### Detail: ADR-26 (Vietnamese tax-invoice)

The tax-invoice is **not just a feature** — it's a compliance requirement with specific format mandates. Architecture specifies:

- **Number allocator:** per-merchant sequence (tenant-prefixed). Acquired from a dedicated `tax_invoice_sequence` table with a Postgres `SELECT ... FOR UPDATE` lock.
- **Template:** Jasper template (util's `JasperUtils`), with Vietnamese fonts already in `util/src/main/resources/fonts/`.
- **QR code:** util's `QRCodeUtil` generates the verification QR per Circular 78/2021/TT-BTC mandate.
- **Daily batch:** cron job (Quartz via util) publishes daily invoice register to the Vietnam tax authority's portal.
- **Idempotency:** `tax_invoice_id` is the natural key; idempotency table prevents double-issuance under retries.

#### Merchant-credentials schema (closes Q5)

The implementation pattern above is generic. To actually issue invoices, the InvoiceService needs merchant-specific credentials configured by the Vietnamese accountant. The schema is **engineered as code, not as ad-hoc config**, so the accountant's input is structured and reviewable:

```sql
-- Schema: public.vietnam_tax_authority_credential
CREATE TABLE vietnam_tax_authority_credential (
    id                  BIGINT PRIMARY KEY,
    merchant_tax_code   VARCHAR(14) NOT NULL UNIQUE,    -- MST (Mã Số Thuế) per Circular 78/2021
    merchant_name       VARCHAR(255) NOT NULL,
    merchant_address    VARCHAR(512) NOT NULL,
    serial_prefix       VARCHAR(2) NOT NULL,           -- Ký hiệu (e.g., "AA" / "AB")
    serial_number_start BIGINT NOT NULL,                -- Số bắt đầu (allocated by tax authority)
    serial_number_end   BIGINT NOT NULL,                -- Số kết thúc
    tax_authority_api   VARCHAR(255) NOT NULL,         -- Endpoint for daily batch upload
    tax_authority_token TEXT NOT NULL,                  -- Encrypted at rest (Vault path: secret/tax/<merchant_tax_code>)
    invoice_template_id VARCHAR(64),                    -- Reference to Jasper template
    active_from         DATE NOT NULL,
    active_until        DATE,
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    created_by          VARCHAR(36) NOT NULL,           -- Reference to operator
    reviewed_at         TIMESTAMP,                      -- Sign-off timestamp
    reviewed_by         VARCHAR(36),                     -- Accountant/operator who signed off
    notes               TEXT
);

-- Sample data (filled by Vietnamese accountant during Sprint 9):
INSERT INTO vietnam_tax_authority_credential
  (merchant_tax_code, merchant_name, merchant_address, serial_prefix,
   serial_number_start, serial_number_end, tax_authority_api, tax_authority_token,
   invoice_template_id, active_from, reviewed_at, reviewed_by)
VALUES
  ('0123456789', 'Công ty TNHH ABC', '123 Nguyễn Văn A, Q1, TP.HCM', 'AA',
   1, 999999, 'https://hoadondientu.gdt.gov.vn/api/v1/invoices',
   '<encrypted>', 'invoice-vi-v1', '2026-09-01', now(), 'accountant-uuid-here');
```

**Accountant input template** (to be completed by Vietnamese accountant before Sprint 9 begins):

```
MST (Mã Số Thuế):        ____________________ (14 digits)
Tên doanh nghiệp:        ____________________
Địa chỉ:                 ____________________
Ký hiệu hóa đơn:         ____/____ (alpha-numeric, allocated by cơ quan thuế)
Số bắt đầu:              ____
Số kết thúc:              ____
API endpoint:             https://hoadondientu.gdt.gov.vn/api/v1/invoices (or custom)
Token xác thực:           (stored encrypted in Vault at secret/tax/<MST>)
Ngày bắt đầu phát hành:   ____/____/____
Mẫu Jasper ưu tiên:       (default: invoice-vi-v1 per util's bundled template)
```

#### Q5 closure procedure (binding for Sprint 9)

1. **Before Sprint 9 begins**, the Sprint Lead sends the **accountant input template** above to the Vietnamese tax accountant + the configured merchant.
2. **Accountant returns** the filled template + their signing credentials.
3. **Sprint 9 Story 9.2 acceptance** requires a populated `vietnam_tax_authority_credential` row with `reviewed_by` set to the accountant's ID and `reviewed_at` in the past 30 days.
4. **CI gate** (per architecture ADR-19): the InvoiceService refuses to start (or fails at first invoice) if no active `vietnam_tax_authority_credential` row exists for the current tenant — the dev environment ships a stub that fails-fast with a clear error message ("Missing tax credentials — see Story 9.2 acceptance criteria").
5. **Yearly re-review** is gated via a Quarkus/Quartz cron that alerts when the next review is due (configurable, default 365 days).

This closes Q5: the implementation pattern is bound (ADR-26), the credentials schema is engineered (above), the input template is documented, and the procedure for collecting + verifying accountant input is built into Sprint 9. Nothing left as ad-hoc.
