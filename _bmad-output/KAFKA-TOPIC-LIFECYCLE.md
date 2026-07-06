---
audience: dev, SRE, architect
project: side-project
date: 2026-07-06
how-to-use: how to add, evolve, and retire Kafka topics. Pair with architecture-detail.md §"Detail: ADR-04" and INTEGRATION-TEST-CHEATSHEET.md.
---

# Kafka Topic Lifecycle — side-project

> **Convention (per ADR-15):** Avro strict backward + forward compat enforced in CI. Every topic change has a schema-versioned evolution.
> **Per architecture:** ~30 topics in v1, mostly 12-partitioned with 3 replicas.

---

## 1. Topic catalog (per PROBLEM-DOMAINS-MAP.md §5)

### 14 services × ~30 topics

| Service | Topics emitted | Topics consumed |
|---|---|---|
| catalog | catalog.product.{created,updated,price_changed,deleted} | (none) |
| inventory | inventory.{reserved,released,allocated,shipped,adjusted} | catalog.product.{created,updated} (for sync) |
| cart | cart.{line.added,expired,merged} | catalog.product.{created,updated} (for sync) |
| checkout | checkout.{started,completed,compensated} | inventory.reserved, payment.captured, payment.failed |
| payment | payment.{captured,refunded,failed,disputed} | (none) |
| order | orders.{placed,amended} | payment.captured, checkout.completed |
| fulfillment | shipment.{dispatched,delivered,exception} | orders.placed (for sync) |
| returns | returns.{created,exchanged}, refund.issued | orders.delivered (for sync) |
| customer | account.{locked}, mfa.challenge, auth.{login.success,login.failure} | (none) |
| search | (none) | catalog.product.*, inventory.* (projection) |
| notification | (none) | all *.lifecycle (subscribe) |
| pricing | pricing.* | (none) |
| invoice | tax.invoice.issued | orders.placed (for sync) |
| admin | (none) | (reads from outbox + admin UI) |

**Total: ~30 topics** at v1.

---

## 2. Adding a new topic

### Workflow

1. **Decide** if you really need a new topic (vs. extending existing)
2. **Design** Avro schema with backward + forward compat
3. **Register** in Apicurio
4. **CI check:** `./scripts/check-avro-compat.sh` must pass
5. **Create** topic in Kafka
6. **Producer** code in service
7. **Consumer** code (if needed) in subscribing service
8. **Update** this catalog
9. **Update** architecture.md §"Event catalog"
10. **Document** in DATA-MODEL.md §5 (event catalog section)

### Avro schema (backward + forward compat)

```json
// my-service/avro/MyEvent.avsc
{
  "namespace": "vn.vnpt.myservice.events",
  "type": "record",
  "name": "MyEvent",
  "fields": [
    {"name": "event_id", "type": "long", "logicalType": "long"},
    {"name": "my_field", "type": "string"},
    {"name": "version", "type": "int", "default": 1}
  ]
}
```

### Backward compat rules

- ✅ Add new field with default value (compat)
- ✅ Remove field (compat — old consumers ignore unknown; new consumers tolerate missing)
- ❌ Change field type (BREAKING)
- ❌ Rename field (BREAKING)
- ❌ Change default value (BREAKING — old consumers expect old default)

### Forward compat rules

- ✅ Add new field (compat — old producers don't write it; new consumers tolerate missing)
- ❌ Remove field (BREAKING — old producers still write it)
- ❌ Change field type (BREAKING)

### CI check (must pass)

```bash
./scripts/check-avro-compat.sh
# For each *.avsc file:
# - Compare with previous version
# - Reject breaking changes
# - Accept backward-compat changes
# - Accept forward-compat changes
```

### Topic creation (per `architecture-detail.md` §"Detail: ADR-04")

```bash
# Create topic with 12 partitions, 3 replicas, 7-day retention
kafka-topics --bootstrap-server localhost:9092 --create \
  --topic my-service.my-event \
  --partitions 12 \
  --replication-factor 3 \
  --config retention.ms=604800000 \
  --config min.insync.replicas=2
```

### OPA admission policy (per ADR-19)

Every topic must be registered in OPA before Kafka cluster accepts creation:

```rego
# platform/policies/opa/kafka-topic-creation.rego
deny[msg] {
    input.request.kind.kind == "KafkaTopic"
    not input.request.object.spec.config.retentionMs
    msg := "Kafka topic must have retention.ms configured"
}
deny[msg] {
    input.request.kind.kind == "KafkaTopic"
    not input.request.object.spec.config.minInsyncReplicas
    msg := "Kafka topic must have min.insync.replicas configured"
}
```

---

## 3. Evolving an existing topic (schema evolution)

### Workflow

1. **Create** new schema file `MyEventV2.avsc`
2. **Update** `MyEvent.avsc` schema to refer to V2
3. **Test** backward + forward compat (in CI)
4. **Deploy** producers first (with V2 code, can write V1 or V2 events)
5. **Wait** for all consumers to be on V2
6. **Deploy** consumers to V2
7. **Retire** V1 (after all consumers migrated)

### Example: catalog.product.created (add new field)

```json
// V1 (current)
{
  "fields": [
    {"name": "event_id", "type": "long"},
    {"name": "name", "type": "string"},
    {"name": "slug", "type": "string"}
  ]
}

// V2 (proposed — add "tags" field with default)
{
  "fields": [
    {"name": "event_id", "type": "long"},
    {"name": "name", "type": "string"},
    {"name": "slug", "type": "string"},
    {"name": "tags", "type": ["null", {"type": "array", "items": "string"}], "default": null}
  ]
}
```

- ✅ Backward compat: old producers (V1) don't write "tags"; new consumers (V2) tolerate null
- ✅ Forward compat: new producers (V2) write "tags" as null; old consumers (V1) ignore unknown
- ✅ Both can coexist during migration

### Migration timeline (example: 2-week migration)

```
Day 0: Deploy V2 producers (still write V1 schema internally, V2 in outbox)
Day 1-3: Monitor producer + consumer error rates (no change expected)
Day 4-5: Update consumers to V2 (in code, with V1 fallback)
Day 6-10: Monitor V2 consumers; if stable, V1 consumers can be retired
Day 11-14: Update V1 consumers to V2 (or deprecate them)
Day 15+: Remove V1 schema (or keep archived in Apicurio)
```

---

## 4. Retiring a topic

### Workflow (when a topic is no longer needed)

1. **Confirm** no consumer is reading from the topic
2. **Mark** topic as "deprecated" in code
3. **Reduce** retention to 1 day (cleanup)
4. **Wait** 1 week (consumers might still read for late events)
5. **Delete** topic
6. **Update** this catalog
7. **Update** architecture.md
8. **Update** service configs (don't subscribe anymore)

### How to confirm no consumer

```bash
# Check consumer groups
kafka-consumer-groups --bootstrap-server localhost:9092 --describe --all-groups
# Look for the topic name in the TOPIC column
# If a group has lag 0 and the lag isn't moving, the consumer is reading

# If a group has lag > 0, the consumer is still processing
# Wait for lag to reach 0 before deleting
```

### Topic deletion

```bash
kafka-topics --bootstrap-server localhost:9092 --delete --topic my-service.my-event
```

---

## 5. Topic configuration standards

### Default config (per `architecture-detail.md` §"Detail: ADR-04")

| Setting | Value | Rationale |
|---|---|---|
| `partitions` | 12 (high-volume) or 6 (medium) or 3 (low) | Tuned per topic throughput |
| `replication.factor` | 3 | HA (3 brokers) |
| `retention.ms` | 7 days (most) / 1 day (cart) / 3 days (operational) / 30 days (audit) | Tuned per topic purpose |
| `cleanup.policy` | delete (default) | Compact only for audit topics |
| `min.insync.replicas` | 2 | Durability vs availability tradeoff |
| `compression.type` | snappy (for JSON-like payloads) | Performance |
| `max.message.bytes` | 1 MB (default) | Most events are small |

### High-volume topics (cart.line.added, etc.)

```bash
kafka-topics --bootstrap-server localhost:9092 --create \
  --topic cart.line.added \
  --partitions 12 \
  --replication-factor 3 \
  --config retention.ms=86400000 \
  --config min.insync.replicas=2 \
  --config compression.type=snappy
```

### Low-volume topics (tax.invoice.issued, etc.)

```bash
kafka-topics --bootstrap-server localhost:9092 --create \
  --topic tax.invoice.issued \
  --partitions 3 \
  --replication-factor 3 \
  --config retention.ms=220752000000  # 7 years (Vietnam tax law)
  --config min.insync.replicas=2
```

---

## 6. Schema registry (Apicurio)

### Per service: register artifacts in group

```bash
# Register a new artifact
curl -X POST 'http://localhost:8080/apis/registry/v2/groups/catalog/artifacts' \
  -H 'Content-Type: application/json' \
  -d '{
    "artifactId": "CatalogProductCreated",
    "artifactType": "AVRO",
    "content": "{...Avro schema...}"
  }'

# List all artifacts in a group
curl -s 'http://localhost:8080/apis/registry/v2/groups/catalog/artifacts' | jq

# Get a specific artifact
curl -s 'http://localhost:8080/apis/registry/v2/groups/catalog/artifacts/CatalogProductCreated' | jq
```

### Global ID strategy

Apicurio content-hash IDs (default): each schema version is identified by its content hash. Pro: idempotent. Con: hard to find.

Alternative: use explicit `artifactId` (e.g., `CatalogProductCreated:v1`). Pro: discoverable. Con: must manage versioning manually.

We use **content-hash + explicit artifactId** for v1: easier debugging + zero manual version management.

### Compatibility check

```bash
# Check if new version is backward / forward compat with latest
curl -X POST 'http://localhost:8080/apis/registry/v2/groups/catalog/artifacts/CatalogProductCreated/test' \
  -H 'Content-Type: application/json' \
  -d '{"content": "{...new schema...}"}'
# Returns: BACKWARD, FORWARD, FULL, NONE
```

---

## 7. Consumer patterns

### Auto-commit + processed_event (per ADR-20 + NFR-IDEM-1)

```java
@KafkaListener(topics = "orders.placed", groupId = "search-service")
public void onOrderPlaced(OrderPlaced event) {
    if (processedEventRepo.existsByEventId(event.eventId())) {
        return; // duplicate, skip
    }
    // process business logic
    // ... index into ES, etc.
    processedEventRepo.save(new ProcessedEvent(event.eventId(), "search-service"));
}
```

### Manual commit (less common; for exactly-once semantics)

```java
@KafkaListener(topics = "orders.placed", groupId = "critical-service")
public void onOrderPlaced(OrderPlaced event) {
    // process business logic
    // ... write to DB
    // Commit offset only after success
    kafkaTemplate.sendOffsetsToTransaction(offsets);
}
```

### Consumer group naming

- Per-service: `service-name` (e.g., `search-service`, `notification-service`)
- Per-feature: `service-name.feature` (e.g., `payment-service.webhook-dedup`)

### Consumer concurrency

```java
// 1 KafkaListener method = 1 consumer thread
// For higher concurrency, use multiple @KafkaListener with different groupId
// or partition-based assignment (default: 1 consumer per partition)
```

---

## 8. Producer patterns

### Outbox + Bridge (per ADR-14, canonical)

```java
// In service code
@Transactional
public void createProduct(CreateProductCommand cmd) {
    Product product = Product.create(cmd);
    productRepo.save(product);
    outbox.append(new CatalogProductCreated(
        snowflakeIdGenerator.generateId(),
        product.getName(),
        product.getSlug(),
        Instant.now()
    ));
}

// Modulith outbox bridge polls + publishes
@Scheduled(fixedDelay = 500)
public void publishOutboxRows() {
    var rows = outboxRepo.findUnpublished(100);
    for (var row : rows) {
        kafkaTemplate.send(row.getEventType(), row.getPayload());
        outboxRepo.markPublished(row.getId());
    }
}
```

### Direct Kafka publish (avoid; only for non-business events)

```java
// ❌ WRONG: Direct publish (bypasses outbox atomicity)
// kafkaTemplate.send("my-topic", event);
// ↑ If Kafka fails, business state is committed but event is lost

// ✅ RIGHT: Outbox + bridge
// outbox.append(event); // published by bridge later
```

### HMAC signing (per ADR-20)

```java
public void sendToKafka(String topic, MyEvent event) {
    var hmacKey = vault.read("secret/events/hmac/" + serviceName);
    var hmac = HmacSha256.sign(JCS.canonicalize(event), hmacKey);
    event.setSignatures(new Signatures(serviceName, hmacKeyId, hmac));
    kafkaTemplate.send(topic, event);
}
```

---

## 9. Monitoring topics

| Topic | Expected rate (launch) | Alert at |
|---|---|---|
| `cart.line.added` | 5.7 events/sec | 0 (no production traffic should be 0!) |
| `orders.placed` | 0.6 events/sec | 0 |
| `payment.captured` | 0.6 events/sec | 0 |
| `inventory.reserved` | 0.6 events/sec | 0 |
| `catalog.product.created` | 0.1 events/sec | 0 |
| `tax.invoice.issued` | 0.6 events/sec (one per order) | 0 |

Alert: if any of these drops to 0 for > 5 min during business hours, page on-call (production system should have continuous traffic).

---

## 10. Disaster scenarios

### Scenario 1: Kafka broker loss (1 of 3)

**Impact:** Brief unavailability (~30s) while controller re-elects. No data loss.

**Mitigation:** Replicated factor 3. KRaft auto-elects new controller.

**RTO:** 30 sec
**RPO:** 0

### Scenario 2: Kafka cluster total loss

**Impact:** All events lost.

**Mitigation:** Outbox tables still have unpublished events. Replay after cluster restart.

**RTO:** 1 hour (replay)
**RPO:** 0 (events in outbox)

### Scenario 3: Schema registry (Apcurio) loss

**Impact:** Cannot fetch schemas; consumers cannot deserialize new events.

**Mitigation:** Consumer-side schema cache (24h TTL); auto-recovery when Apcurio restarts.

**RTO:** 30 min
**RPO:** 0 (consumer cache)

---

## 11. Common pitfalls

### ❌ Don't use per-request topic

```java
// ❌ WRONG: One topic per request type
kafkaTemplate.send("user.signup.requested", event);
kafkaTemplate.send("user.signup.completed", event);
kafkaTemplate.send("user.login.attempted", event);
kafkaTemplate.send("user.login.succeeded", event);

// ✅ RIGHT: One topic per aggregate + lifecycle
kafkaTemplate.send("user.lifecycle", event);
// Event payload includes action: "signup" | "login" | "logout"
```

### ❌ Don't put state in events

```java
// ❌ WRONG: Event contains state
event.setProductState(product.getFullState());

// ✅ RIGHT: Event contains reference
event.setProductUuid(product.getUuid());
// Consumer fetches state if needed
```

### ❌ Don't subscribe to internal topics from public APIs

```java
// ❌ WRONG: Public API subscribes to internal topic
@KafkaListener(topics = "internal.payment.captured", groupId = "public-api")

// ✅ RIGHT: Public API subscribes to public topic (or a sanitized version)
// Or: public API queries DB via internal auth
```

---

## 12. Cross-references

- **Architecture (event-driven):** `architecture-detail.md` §"Detail: ADR-04"
- **Risk-binding (R-XX):** `RISK-REGISTER.md` (R-04, R-05, R-15)
- **Data model (event catalog):** `PROBLEM-DOMAINS-MAP.md` §5
- **Addendum (version matrix):** `addendum.md` §A4
- **Operational runbook:** `DEVOPS-RUNBOOK.md` §5 (Kafka ops)
- **Disaster recovery:** `DISASTER-RECOVERY.md`
- **Observability:** `OBSERVABILITY-RUNBOOK.md`
- **PR conventions:** `CONTRIBUTING.md`
- **Per-sprint work:** `SPRINT-1-DEV-HANDBOOK.md`
- **Onboarding:** `AGENT-ONBOARDING.md`
- **Glossary:** `GLOSSARY.md`
