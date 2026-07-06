---
audience: dev, SRE
project: side-project
date: 2026-07-06
how-to-use: per-metric reference. Use when defining new metrics or debugging. Pair with OBSERVABILITY-RUNBOOK.md.
---

# Metrics Dictionary — side-project

> **Convention (per ADR-16):** All custom metrics prefixed by `<service>.<entity>.<action>.<unit>`. Labels use snake_case.
> **Per architecture §"Implementation Patterns":** Auto-instrumented via OTel Spring Boot starter. Custom metrics via Micrometer.

---

## 1. Naming convention

```
<service>.<entity>.<action>_<unit>[.<scope>]
```

- `<service>` — service name (catalog, inventory, payment, etc.)
- `<entity>` — the thing being measured (product, order, payment, etc.)
- `<action>` — the verb (created, processed, failed, etc.)
- `<unit>` — the unit (total, seconds, bytes, etc.)
- `<scope>` — optional (e.g., `latency`, `p99`)

Examples:
- `catalog.product.created_total`
- `payment.captured.duration_seconds`
- `order_state.transition.p99`

---

## 2. HTTP metrics (auto-instrumented)

### `http.server.requests`

**Type:** Histogram
**Labels:** `method`, `uri`, `status`, `outcome`
**Unit:** seconds

| Aspect | Value |
|---|---|
| Bucket boundaries | 0.005s, 0.01s, 0.025s, 0.05s, 0.1s, 0.25s, 0.5s, 1s, 2.5s, 5s, 10s |
| Default | enabled (Spring Boot OTel) |
| Where | every HTTP server endpoint (per architecture §"Implementation Patterns") |

### Alerts based on this

```promql
# p99 latency for catalog read > 100ms for 10 min
histogram_quantile(0.99,
  rate(http_server_requests_seconds_bucket{uri="/api/catalog/products"}[10m])
) > 0.1

# Error rate > 5% for 5 min
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
  / sum(rate(http_server_requests_seconds_count[5m])) > 0.05
```

---

## 3. Kafka metrics (auto-instrumented)

### `kafka.consumer.records.lag`

**Type:** Gauge
**Labels:** `client.id`, `topic`, `partition`
**Unit:** records (count)

| Aspect | Value |
|---|---|
| Default | enabled (Spring Kafka consumer) |
| Where | every Kafka consumer (per service) |

### `kafka.producer.record.send`

**Type:** Counter
**Labels:** `client.id`, `topic`, `outcome`
**Unit:** records (total)

### `kafka.consumer.commit`

**Type:** Counter
**Labels:** `client.id`, `topic`, `outcome`
**Unit:** commits (total)

### Alerts

```promql
# Consumer lag > 30s for 5 min
kafka_consumer_records_lag > 30
```

---

## 4. JVM metrics (auto-instrumented)

### `jvm.memory.used`

**Type:** Gauge
**Labels:** `area` (`heap` | `nonheap`)
**Unit:** bytes

### `jvm.memory.committed`

**Type:** Gauge
**Labels:** `area`
**Unit:** bytes

### `jvm.memory.max`

**Type:** Gauge
**Labels:** `area`
**Unit:** bytes

### `jvm.gc.memory.allocated`

**Type:** Counter
**Labels:** none
**Unit:** bytes (total allocated)

### `jvm.gc.pause`

**Type:** Histogram
**Labels:** `action` (`minor` | `major`)
**Unit:** seconds

### `jvm.threads.live`

**Type:** Gauge
**Labels:** none
**Unit:** threads (count)

### Alerts

```promql
# JVM memory > 80% for 5 min
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} > 0.8

# GC pause > 500ms (long pause)
histogram_quantile(0.99, rate(jvm_gc_pause_seconds_bucket[5m])) > 0.5
```

---

## 5. Database metrics (HikariCP)

### `hikaricp.connections.active`

**Type:** Gauge
**Labels:** `pool` (e.g., `HikariPool-1`)
**Unit:** connections (count)

### `hikaricp.connections.idle`

**Type:** Gauge
**Labels:** `pool`
**Unit:** connections (count)

### `hikaricp.connections.pending`

**Type:** Gauge
**Labels:** `pool`
**Unit:** threads (count waiting for connection)

### `hikaricp.connections.acquire`

**Type:** Histogram
**Labels:** `pool`
**Unit:** seconds

### `hikaricp.connections.usage`

**Type:** Histogram
**Labels:** `pool`
**Unit:** seconds

### Alerts

```promql
# Connection pool saturation (active / max > 90%)
hikaricp_connections_active / hikaricp_connections_max > 0.9

# Pending connections (pool exhausted)
hikaricp_connections_pending > 10 for 5 min

# Slow acquisition (> 1s)
histogram_quantile(0.99, rate(hikaricp_connections_acquire_seconds_bucket[5m])) > 1
```

---

## 6. Custom business metrics (per service)

### Catalog

#### `catalog.product.created_total`

**Type:** Counter
**Labels:** `locale` (`vi` | `en`)
**Unit:** products (total)
**When:** Increments on `CatalogProductCreated` event
**SLO:** N/A (info metric)

#### `catalog.product.price_changed_total`

**Type:** Counter
**Labels:** `locale`
**Unit:** products (total)
**When:** Increments on `CatalogProductPriceChanged` event

#### `catalog.search.queries_total`

**Type:** Counter
**Labels:** `locale`, `result_count_bucket` (`0`, `1-10`, `11-100`, `100+`)
**Unit:** queries (total)
**When:** Search query completes
**SLO:** N/A (info metric)

### Inventory

#### `inventory.reservation.duration_seconds`

**Type:** Histogram
**Labels:** `outcome` (`success` | `insufficient_stock` | `timeout`)
**Unit:** seconds
**When:** Each reservation attempt completes
**Buckets:** 0.01s, 0.05s, 0.1s, 0.5s, 1s, 5s
**SLO:** p99 < 200ms

#### `inventory.reservation.success_total`

**Type:** Counter
**Labels:** `warehouse`
**Unit:** reservations (total)

#### `inventory.reservation.insufficient_stock_total`

**Type:** Counter
**Labels:** `warehouse`, `variant_uuid`
**Unit:** rejections (total)

### Cart

#### `cart.abandoned_total`

**Type:** Counter
**Labels:** `locale`
**Unit:** carts (total)
**When:** Cart expires (per FR-18)

### Checkout (Saga)

#### `checkout.duration_seconds`

**Type:** Histogram
**Labels:** `outcome` (`success` | `failure` | `compensation`)
**Unit:** seconds
**Buckets:** 0.5s, 1s, 2s, 5s, 10s, 30s
**When:** Checkout completes (success or compensation)
**SLO:** p99 < 800ms (per NFR-PERF)

#### `checkout.state_transitions_total`

**Type:** Counter
**Labels:** `from`, `to`
**Unit:** transitions (total)
**When:** Each saga state change
**Per architecture §"Detail: ADR-12":** Shows the 10-state machine in action

#### `checkout.saga.recovery_total`

**Type:** Counter
**Labels:** `state` (the recovered state)
**Unit:** recoveries (total)
**When:** Saga recovery routine replays stuck saga on startup
**Alert:** > 5 per hour = something's wrong

### Payment

#### `payment.captured.total`

**Type:** Counter
**Labels:** `outcome` (`success` | `failure`)
**Unit:** payments (total)
**SLO:** failure rate < 0.5%

#### `payment.capture.duration_seconds`

**Type:** Histogram
**Labels:** `outcome`
**Unit:** seconds
**Buckets:** 0.5s, 1s, 2s, 5s, 10s
**SLO:** p99 < 3s (per PERFORMANCE-BUDGET.md)

#### `payment.webhook.dedup.hits_total`

**Type:** Counter
**Labels:** `event_type`
**Unit:** dedup events (total)
**When:** Webhook arrives with `event.id` already in `webhook_dedup` table
**SLO:** dedup rate should be low (most webhooks are new)

#### `payment.webhook.lag_seconds`

**Type:** Histogram
**Labels:** `event_type`
**Unit:** seconds
**When:** Webhook processed; measures time from Stripe timestamp to our processing
**SLO:** p99 < 30s

### Order

#### `order.state.transitions_total`

**Type:** Counter
**Labels:** `from`, `to`
**Unit:** transitions (total)
**SLO:** N/A (info metric)

#### `order.compensations_total`

**Type:** Counter
**Labels:** `reason` (`payment_failed` | `user_cancel` | `carrier_failed`)
**Unit:** compensations (total)
**Alert:** > 5% of orders

### Fulfillment

#### `shipment.dispatched.total`

**Type:** Counter
**Labels:** `carrier`
**Unit:** shipments (total)

#### `shipment.delivery.duration_seconds`

**Type:** Histogram
**Labels:** `carrier`, `origin_warehouse`
**Unit:** seconds
**SLO:** depends on carrier (per FR-37)

#### `carrier.api.failures_total`

**Type:** Counter
**Labels:** `carrier`, `endpoint`, `error_code`
**Unit:** failures (total)
**Alert:** > 5% over 5 min (per `ALERTING-RUNBOOK.md`)

### Customer (Auth)

#### `auth.login.success_total`

**Type:** Counter
**Labels:** `method` (`email` | `magic_link`)
**Unit:** logins (total)

#### `auth.login.failure_total`

**Type:** Counter
**Labels:** `method`, `reason` (`bad_password` | `mfa_required` | `locked`)
**Unit:** failures (total)
**Alert:** failure rate > 10% (per `ALERTING-RUNBOOK.md`)

#### `auth.account.locked_total`

**Type:** Counter
**Labels:** `reason`
**Unit:** locks (total)
**SLO:** N/A (info metric)

#### `customer.mfa.challenges_total`

**Type:** Counter
**Labels:** `outcome` (`success` | `failure`)
**Unit:** challenges (total)

### Notification

#### `notification.sent.total`

**Type:** Counter
**Labels:** `channel` (`email` | `push` | `sms`), `event_type`, `outcome`
**Unit:** notifications (total)

### Invoice (VN Tax)

#### `invoice.issued.total`

**Type:** Counter
**Labels:** `outcome` (`success` | `failure`)
**Unit:** invoices (total)

#### `invoice.daily.batch.upload_duration_seconds`

**Type:** Histogram
**Labels:** `outcome` (`success` | `failure`)
**Unit:** seconds
**SLO:** < 60s (per addendum A3)

---

## 7. Outbox + bridge metrics (per ADR-14)

### `outbox.pending.rows`

**Type:** Gauge
**Labels:** `service`
**Unit:** rows (count)
**When:** Polled every 30 sec
**SLO:** < 1000

### `outbox.published.rows`

**Type:** Counter
**Labels:** `service`, `topic`
**Unit:** rows (total)

### `outbox.publisher.failures_total`

**Type:** Counter
**Labels:** `service`, `error_type`
**Unit:** failures (total)
**SLO:** < 0.1/sec (per `ALERTING-RUNBOOK.md`)

### `outbox.publish.duration_seconds`

**Type:** Histogram
**Labels:** `service`
**Unit:** seconds
**Buckets:** 0.01s, 0.05s, 0.1s, 0.5s, 1s, 5s
**SLO:** p99 < 100ms

---

## 8. Saga + Idempotency metrics (per NFR-IDEM)

### `saga.idempotency.hits_total`

**Type:** Counter
**Labels:** `service`, `operation`
**Unit:** hits (total)
**When:** Same idempotency key + retry → same response (no double-charge)
**SLO:** dedup rate > 5% (means retries happening)

### `processed_event.cache.size`

**Type:** Gauge
**Labels:** `service`, `consumer_name`
**Unit:** rows (count)
**When:** Updated every 1 min
**SLO:** N/A (info metric)

### `processed_event.cache.growth_rate`

**Type:** Gauge
**Labels:** `service`, `consumer_name`
**Unit:** rows / minute

---

## 9. Security metrics (per R-15)

### `security.pan.detected.total`

**Type:** Counter
**Labels:** `service`
**Unit:** PAN-shaped fields (total)
**When:** OTel log redaction detects a field matching `\d{13,19}` but not redacted
**SLO:** MUST be 0 (per R-15)
**Alert:** > 0 = PCI scope violation (page on-call immediately)

### `security.auth.kill_switch.activated_total`

**Type:** Counter
**Labels:** `feature` (e.g., `payment`, `checkout`, `invoice`)
**Unit:** activations (total)
**When:** A kill-switch feature flag is set to `true` (per `FEATURE-FLAGS.md`)

### `security.failed_login_attempts.total`

**Type:** Counter
**Labels:** `email_hash` (anonymized), `ip_hash`
**Unit:** attempts (total)
**When:** A login attempt fails (per FR-76 / AT-02)
**Alert:** > 5 per email per 15 min = credential stuffing

### `security.account.locked.total`

**Type:** Counter
**Labels:** `reason`
**Unit:** locks (total)
**When:** Account locked (per FR-76)

---

## 10. Rate-limit metrics (per ADR-13 + ADR-24)

### `rate_limit.allowed.total`

**Type:** Counter
**Labels:** `key_type` (`ip` | `card_fingerprint` | `bin`)
**Unit:** allowed (total)

### `rate_limit.denied.total`

**Type:** Counter
**Labels:** `key_type`, `reason` (`limit_exceeded` | `redis_unavailable`)
**Unit:** denied (total)
**SLO:** denied / allowed < 5% (mostly allowed)

### `rate_limit.fail_open.total`

**Type:** Counter
**Labels:** `key_type` (per `CACHING-STRATEGY.md` fail-open policy)
**Unit:** fail-opens (total)
**Alert:** > 10 per 5 min = Redis is down

---

## 11. OTel infrastructure metrics

### `otelcol_processed_spans`

**Type:** Counter
**Unit:** spans (total)

### `otelcol_processed_log_records`

**Type:** Counter
**Labels:** `format` (`json`)
**Unit:** records (total)

### `otelcol_processing_latency`

**Type:** Histogram
**Unit:** seconds
**SLO:** p99 < 1s (collector should be fast)

---

## 12. Redis metrics (auto-instrumented)

### `redis.connections.active`

**Type:** Gauge
**Unit:** connections (count)

### `redis.commands.processed`

**Type:** Counter
**Labels:** `command` (`get` | `set` | `del` | `zadd` | `eval` | etc.)
**Unit:** commands (total)

### `redis.commands.failed`

**Type:** Counter
**Labels:** `command`, `error_type`
**Unit:** errors (total)

### `redis.memory.used`

**Type:** Gauge
**Unit:** bytes
**SLO:** < 80% of max (per `ALERTING-RUNBOOK.md`)

### `redis.hit_rate`

**Type:** Gauge
**Unit:** ratio (0..1)
**SLO:** > 70% (per `CACHING-STRATEGY.md`)

---

## 13. Elasticsearch metrics (auto-instrumented)

### `elasticsearch.search.duration_seconds`

**Type:** Histogram
**Labels:** `index`, `query_type`
**Unit:** seconds
**SLO:** p99 < 300ms (per NFR-PERF-2)

### `elasticsearch.index.size_bytes`

**Type:** Gauge
**Labels:** `index`
**Unit:** bytes

### `elasticsearch.index.docs.count`

**Type:** Gauge
**Labels:** `index`
**Unit:** documents (count)

---

## 14. Adding a new custom metric

### Workflow

1. **Decide** if the metric is needed (per the SLO it informs)
2. **Name** it per the convention
3. **Add** to `platform/observability/prometheus-rules/*.yaml` if alerting
4. **Add** to `platform/observability/grafana-dashboards/*.json` if visualizing
5. **Test** the metric in dev (use `curl localhost:8080/actuator/metrics/<metric>`)
6. **Document** it in this file
7. **PR** with: metric name, semantic, type, labels, SLO, alert conditions

### Code template

```java
@Service
public class CheckoutService {
    private final MeterRegistry meterRegistry;

    public CheckoutService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public CheckoutResult startCheckout(Cart cart) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var checkout = doStartCheckout(cart);
            sample.stop(Timer.builder("checkout.duration")
                .tag("outcome", "success")
                .register(meterRegistry));
            meterRegistry.counter("checkout.started_total", "outcome", "success").increment();
            return checkout;
        } catch (InsufficientStockException e) {
            sample.stop(Timer.builder("checkout.duration")
                .tag("outcome", "insufficient_stock")
                .register(meterRegistry));
            throw e;
        }
    }
}
```

### Anti-patterns

```java
// ❌ Don't: high-cardinality labels
meterRegistry.counter("events.processed",
    "user_id", user.getId(),  // thousands of unique values!
    "type", event.getType()).increment();
// Cardinality explodes; Prometheus breaks.

// ✅ Do: low-cardinality labels
meterRegistry.counter("events.processed",
    "type", event.getType(),  // 5-10 unique values
    "outcome", outcome).increment();

// ❌ Don't: sync metric updates in a hot path
public void doWork() {
    doActualWork();
    meterRegistry.counter("work.done").increment();  // sync overhead
}

// ✅ Do: async / batched
// (OTel Spring Boot starter does this automatically)
```

---

## 15. Cross-references

- **Observability setup:** `OBSERVABILITY-RUNBOOK.md`
- **Alerting runbook:** `ALERTING-RUNBOOK.md`
- **Capacity planning:** `CAPACITY-PLANNING.md`
- **Operational runbook:** `DEVOPS-RUNBOOK.md`
- **Architecture (custom metrics):** `architecture.md` §"Implementation Patterns"
- **Addendum (version matrix):** `addendum.md` §A4
- **Onboarding:** `AGENT-ONBOARDING.md`
- **Glossary:** `GLOSSARY.md`
