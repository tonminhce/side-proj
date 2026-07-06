---
audience: SRE, ops, dev
project: side-project
date: 2026-07-06
how-to-use: OTel + LGTM + chaos testing reference. Pair with DEVOPS-RUNBOOK.md (deployment) and RISK-REGISTER.md (risk mitigations).
---

# Observability Runbook — side-project

> **Stack (binding, per ADR-16):**
> - **OpenTelemetry** (W3C trace context; Kafka header propagation)
> - **LGTM** = Loki (logs) + Grafana (viz) + Tempo (traces) + Mimir (metrics, often)
> - **Prometheus** for metrics
> - **Chaos Mesh** for chaos engineering, one experiment per P0 risk

---

## 1. OTel setup (auto-instrumentation)

### Java service config (per ADR-16)

```xml
<!-- services/<x>/pom.xml -->
<dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-spring-boot-starter</artifactId>
    <version>2.10.0</version>
</dependency>
```

```yaml
# application.yml
otel:
  service:
    name: catalog-service
  exporter:
    otlp:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://otel-collector:4317}
  propagators: tracecontext, baggage
  traces:
    exporter: otlp
  metrics:
    exporter: otlp
  logs:
    exporter: otlp
  resource:
    attributes:
      deployment.environment: ${ENV:dev}
      service.version: ${app.version:0.0.0-SNAPSHOT}
```

### Auto-instrumented libraries

The OTel Spring Boot starter auto-instruments:
- Spring Web (controllers, RestTemplate, WebClient)
- Spring Data JPA / Hibernate
- Apache Kafka (producers + consumers)
- Lettuce (Redis)
- OkHttp / Apache HttpClient
- R2DBC (if used)
- Logback (MDC correlation)

No code changes needed; just add the dependency.

### Manual instrumentation (when needed)

```java
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.instrumentation.annotations.WithSpan;

@Service
public class CheckoutService {
    private static final Tracer tracer = GlobalOpenTelemetry.getTracer("checkout-service");

    // Option 1: Annotation
    @WithSpan("reserve-stock")
    public Reservation reserveStock(long variantId) {
        // ...
    }

    // Option 2: Manual span
    public void checkoutFlow(Cart cart) {
        Span span = tracer.spanBuilder("checkout-flow")
            .setAttribute("cart.id", cart.getId())
            .setAttribute("cart.total_cents", cart.getTotalCents())
            .startSpan();
        try (Scope scope = span.makeCurrent()) {
            // ... business logic
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            throw e;
        } finally {
            span.end();
        }
    }
}
```

### Custom attributes (business-relevant)

```java
// Add business context to spans
span.setAttribute("order.id", order.getUuid());
span.setAttribute("saga.step", "payment.authorize");
span.setAttribute("idempotency.key", idempKey);
span.setAttribute("merchant.tax_code", "0123456789");
```

---

## 2. Structured logging (per NFR-OBS-5)

### Pattern: JSON logging with trace context

```yaml
# application.yml
logging:
  pattern:
    # Spring Boot 3 + Logback with Logstash encoder
    level:
      vn.vnpt: DEBUG
  config: classpath:logback-spring.xml
```

```xml
<!-- src/main/resources/logback-spring.xml -->
<configuration>
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <includeMdcKeyName>traceId</includeMdcKeyName>
            <includeMdcKeyName>spanId</includeMdcKeyName>
            <customFields>{"service":"catalog-service"}</customFields>
        </encoder>
    </appender>

    <!-- NEVER log PAN-shaped fields (R-15 / ADR-23) -->
    <conversionRule conversionWord="maskPan"
                    converterClass="vn.vnpt.util.logging.PanMaskingConverter"/>

    <root level="INFO">
        <appender-ref ref="JSON"/>
    </root>
</configuration>
```

### Log format (each line is JSON)

```json
{
  "@timestamp": "2026-07-06T11:30:00.123Z",
  "level": "INFO",
  "service": "catalog-service",
  "traceId": "abc123def456...",
  "spanId": "789ghi...",
  "thread": "http-nio-8080-exec-1",
  "logger": "vn.vnpt.catalog.CheckoutService",
  "message": "checkout completed",
  "cart.id": "12345",
  "order.id": "67890",
  "duration_ms": 234
}
```

### Hard rules

- ❌ **NEVER** log PAN (`\d{13,19}`), CVV, or any card-shaped data
- ❌ **NEVER** use `System.out.println` — use a logger
- ❌ **NEVER** log request bodies by default (PCI scope)
- ✅ Always use structured fields (`cart.id=...`), not concatenated strings
- ✅ Always propagate `traceId` via MDC

---

## 3. Metrics catalog

### RED metrics per service

Every service should expose:

| Metric | Type | Labels | Purpose |
|---|---|---|---|
| `http_server_requests_seconds` | Histogram | `uri`, `method`, `status` | Request rate, errors, duration |
| `jvm_memory_used_bytes` | Gauge | `area` | Memory pressure |
| `process_cpu_usage` | Gauge | — | CPU pressure |
| `kafka_consumer_records_lag` | Gauge | `topic`, `group` | Consumer lag |
| `kafka_producer_record_send_total` | Counter | `topic` | Producer throughput |
| `outbox_publish_failures_total` | Counter | `service` | R-04 mitigation observability |
| `outbox_pending_rows` | Gauge | `service` | Outbox backlog |
| `webhook_dedup_size` | Gauge | — | R-03 webhook dedup table size |
| `snowflake_worker_id_source` | Gauge | `source` | R-22 — 1=podname, 2=securerandom |

### Domain-specific metrics (per service)

| Service | Metric | What it tells you |
|---|---|---|
| catalog | `catalog_products_total{locale}` | Catalog size per locale |
| inventory | `inventory_on_hand_count{variant_uuid}` | Stock distribution |
| inventory | `inventory_reservations_expired_total` | Sweeper health (R-XX monitoring) |
| checkout | `checkout_started_total{outcome}` | Funnel analysis |
| payment | `payment_capture_total{outcome}` | Payment success rate (NFR-AVAIL-1 SLO) |
| order | `order_state_transitions_total{from,to}` | Saga state machine health |
| search | `search_queries_total{analyzer}` | VN vs EN usage |
| admin | `admin_audit_trail_writes_total` | R-XX monitoring |

### Histogram buckets (tune for your latency SLOs)

```yaml
# application.yml
management:
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
      slo:
        http.server.requests: 100ms, 300ms, 1s
      percentiles:
        http.server.requests: 0.5, 0.95, 0.99
```

---

## 4. LGTM dashboards (per ADR-16)

### Provisioned from Git (Story 10.1)

```
platform/observability/grafana-dashboards/
├── 01-system-overview.json         # SLO compliance + error rates
├── 02-catalog-service.json         # product reads, search hits, ES lag
├── 03-inventory-service.json       # reservations, FOR UPDATE contention
├── 04-checkout-saga.json           # state machine transitions
├── 05-payment-service.json         # Stripe success rate, idempotency
├── 06-risk-mitigations.json        # R-XX specific panels
└── 07-on-call-essentials.json      # top 5 panels for on-call
```

### Example: System Overview dashboard

```json
{
  "title": "01 System Overview",
  "panels": [
    {
      "title": "Checkout p99 (NFR-PERF target: <800ms)",
      "targets": [{
        "expr": "histogram_quantile(0.99, rate(checkout_duration_seconds_bucket[5m]))"
      }],
      "thresholds": [{"value": 0.8, "color": "red"}]
    },
    {
      "title": "Order state transitions per minute (Saga health)",
      "targets": [{
        "expr": "sum by(to) (rate(order_state_transitions_total[5m]))"
      }]
    },
    {
      "title": "Outbox publish failures (R-04 alarm)",
      "targets": [{
        "expr": "rate(outbox_publish_failures_total[5m])"
      }],
      "thresholds": [{"value": 0.1, "color": "red"}]
    },
    {
      "title": "Payment success rate (NFR-AVAIL-1: 99.9% target)",
      "targets": [{
        "expr": "sum(rate(payment_capture_total{outcome=\"success\"}[5m])) / sum(rate(payment_capture_total[5m]))"
      }]
    },
    {
      "title": "Active Snowflake worker-id source (R-22: should always be 1=podname)",
      "targets": [{
        "expr": "max(snowflake_worker_id_source) by (service)"
      }]
    }
  ]
}
```

### Reading the dashboards

1. **Open http://localhost:3000** (or your Grafana URL)
2. Navigate to **Dashboards → Browse**
3. Pick a dashboard (e.g., "01 System Overview")
4. Time range: last 6 hours (default) or adjust
5. **Read top-down:**
   - Top: business KPIs (conversion, SLO compliance)
   - Middle: service-level metrics
   - Bottom: infrastructure (CPU, memory, network)

---

## 5. Tracing workflow

### Find a slow trace

1. Grafana → Explore → Tempo
2. Search: `service.name = checkout-service AND duration > 1s`
3. Click a trace to see waterfall
4. **Waterfall reading:** spans are nested children of the parent span. Wider = slower. Look for the widest span to find the bottleneck.
5. Click a span to see its attributes (e.g., `order.id`, `saga.step`)

### Common span patterns

```
checkout-flow (200ms)
├── reserve-stock (50ms)  # FOR UPDATE on inventory_ledger
├── payment-authorize (100ms)  # Stripe API call
│   └── stripe-http-call (80ms)
└── order-create (50ms)
    └── order-state-transition-write (20ms)
```

If `stripe-http-call` is the widest → Stripe is the bottleneck.
If `reserve-stock` is wide → FOR UPDATE contention; check inventory.

### Trace context propagation

```java
// Saga step: produce event with W3C trace context
Span.current().setAttribute("saga.step", "payment.authorize");
kafkaTemplate.send("payment.lifecycle", event);
// OTel Kafka instrumentation auto-propagates trace context via headers

// Consumer: extract trace context, continue span
@KafkaListener(topics = "payment.lifecycle")
public void on(PaymentCaptured event) {
    // Span context auto-restored from Kafka header
    // No code changes needed
}
```

### Find cross-service trace

1. Get a `traceId` from any service's logs (e.g., `traceId: abc123`)
2. Grafana → Tempo → search by traceId
3. **See the full saga** across all services that participated in the trace

---

## 6. Logging workflow (Loki)

### Find errors in last hour

```logql
{service="payment-service"} |= "error" | json | line_format "{{.message}}" | limit 100
```

### Find specific log line

```logql
{service="checkout-service"} | json | order_uuid="abc-123"
```

### Logs from a specific trace

```logql
{traceId="abc123def456"}
```

This is the killer feature — jump from a slow trace (Tempo) to all its logs (Loki) by traceId.

### Save common queries

In Grafana → Explore → Loki → save a query as a dashboard panel.

---

## 7. Alerting (Prometheus rules)

### Critical alerts (per R-XX)

```yaml
# platform/observability/prometheus-rules/critical.yaml
groups:
  - name: critical-risks
    rules:
      # R-04: Outbox publish failures
      - alert: OutboxPublishFailuresHigh
        expr: rate(outbox_publish_failures_total[5m]) > 0.1
        for: 2m
        labels: { severity: critical, risk: r-04 }
        annotations:
          summary: "Outbox publish failing for {{ $labels.service }}"
          runbook: "https://runbooks/side-project/r-04-outbox-failures"

      # R-08: Snowflake worker-id is from SecureRandom (not podname)
      - alert: SnowflakeWorkerIdSourceInsecure
        expr: max(snowflake_worker_id_source) by (service) == 2
        for: 1m
        labels: { severity: critical, risk: r-08 }
        annotations:
          summary: "{{ $labels.service }} is using SecureRandom (worker-id collision risk)"

      # R-15: PAN-shaped field in logs
      - alert: PossiblePanInLogs
        expr: rate(log_entries_with_pan_pattern_total[5m]) > 0
        for: 1m
        labels: { severity: critical, risk: r-15 }
        annotations:
          summary: "PAN-shaped field detected in logs at {{ $labels.service }}"

      # R-13: GHN/GHTK carrier down
      - alert: CarrierApisDegraded
        expr: rate(carrier_http_failures_total[5m]) > 0.5
        for: 10m
        labels: { severity: high, risk: r-13 }

      # NFR-PERF-1: Catalog p99 > 100ms
      - alert: CatalogReadP99High
        expr: histogram_quantile(0.99, rate(http_server_requests_seconds_bucket{uri="/api/catalog/products"}[5m])) > 0.1
        for: 10m
        labels: { severity: high, nfr: perf-1 }
```

### Alert routing

- **Critical:** page on-call via PagerDuty / Opsgenie
- **High:** Slack channel + ticket
- **Medium:** Slack channel only
- **Low:** log for review

---

## 8. Chaos engineering (per ADR-16)

### Per-risk chaos experiments (one per P0 risk)

```
platform/chaos/chaos-mesh/
├── r-02-inventory-oversell.yaml
├── r-03-payment-double-capture.yaml
├── r-04-kafka-broker-kill.yaml
├── r-05-card-testing-burst.yaml
├── r-06-tax-authority-timeout.yaml
├── r-15-pci-scope-leak.yaml
└── saga-state-machine-full-disruption.yaml
```

### Example: r-04-kafka-broker-kill.yaml

```yaml
# Validates R-04 mitigation: outbox + Modulith bridge should survive Kafka broker kill
apiVersion: chaos-mesh.org/v1alpha1
kind: Schedule
metadata:
  name: kafka-broker-kill
  namespace: chaos-mesh
spec:
  schedule: "0 14 * * 1"  # every Monday 2pm
  type: PodChaos
  historyLimit: 5
  concurrencyPolicy: Forbid
  podChaos:
    action: pod-kill
    mode: one
    selector:
      namespaces: [kafka]
      labelSelectors:
        app: kafka
    duration: "30s"
```

### Example: r-02-inventory-oversell.yaml

```yaml
# Validates R-02 / DI-01 mitigation: concurrent reservations of last unit
apiVersion: chaos-mesh.org/v1alpha1
kind: Schedule
metadata:
  name: inventory-pause-during-reservation-burst
  namespace: chaos-mesh
spec:
  schedule: "0 15 * * 2"  # every Tuesday 3pm
  type: PodChaos
  historyLimit: 5
  concurrencyPolicy: Forbid
  podChaos:
    action: pause
    duration: "60s"
    selector:
      namespaces: [side-project]
      labelSelectors:
        app: inventory-service
```

### Run a chaos experiment (manual)

```bash
# 1. Apply the experiment
kubectl apply -f platform/chaos/chaos-mesh/r-04-kafka-broker-kill.yaml

# 2. Watch the dashboard
open "http://localhost:3000/d/01-system-overview"

# 3. Verify alerts fire (if R-04 mitigation is broken, OutboxPublishFailuresHigh should trigger)
# Check Slack / PagerDuty

# 4. Verify recovery (after 30s, Kafka comes back, outbox drains)
# Check: outbox_pending_rows drops to 0 within 5 min

# 5. Delete the experiment
kubectl delete -f platform/chaos/chaos-mesh/r-04-kafka-broker-kill.yaml
```

### Game day (quarterly)

Every quarter, run all chaos experiments in sequence:

```bash
# 1. Notify team
./scripts/notify-game-day.sh "Q3 2026 chaos game day"

# 2. Run each experiment, observe + record
for exp in platform/chaos/chaos-mesh/*.yaml; do
  echo "=== Running $(basename $exp) ==="
  kubectl apply -f "$exp"
  sleep 600  # observe for 10 min
  kubectl delete -f "$exp"
  echo "=== Done $(basename $exp) ==="
done

# 3. Write post-mortem
# 4. Update runbooks based on what you learned
```

---

## 9. SLO error budget

### Define SLOs (per NFR-AVAIL-1, NFR-PERF)

| SLO | Target | Window | Error budget (30 days) |
|---|---|---|---|
| Checkout availability | 99.9% | 30 days | 43.2 min downtime |
| Catalog p99 latency | < 100ms | 30 days | ≤ 30% requests over budget |
| Payment success rate | > 99.5% | 30 days | ≤ 0.5% failures |
| Search p99 latency | < 300ms | 30 days | ≤ 30% over budget |

### Burn-rate alerting (Google SRE model)

| Burn rate | Window | Alert |
|---|---|---|
| 14.4× | 1 hour | Page on-call immediately |
| 6× | 6 hours | Slack + ticket |
| 1× | 24 hours | Slack (slow burn) |
| 0.5× | 3 days | Log only |

**Example:** Checkout 99.9% SLO = 43.2 min budget over 30 days. If we burn 14.4× rate, we've exhausted 30 days of budget in 50 hours → page on-call.

---

## 10. Quick reference

```bash
# Open Grafana (dev)
open http://localhost:3000

# Find a slow trace
# Grafana → Explore → Tempo → service.name=catalog-service AND duration>1s

# Find an error log
# Grafana → Explore → Loki → {service="payment-service"} |= "error"

# Manually trigger a chaos experiment
kubectl apply -f platform/chaos/chaos-mesh/r-04-kafka-broker-kill.yaml

# Check active alerts
curl -s http://prometheus:9090/api/v1/alerts | jq

# Tail OTel collector
docker logs otel-collector -f

# Run game day
./scripts/run-game-day.sh
```

---

## 11. Cross-references

- **Risks being monitored:** `RISK-REGISTER.md` (15 risks + mitigations)
- **Service architecture:** `architecture.md` §"Project Structure & Boundaries"
- **Sprint 10 observability work:** `epics.md` Stories 10.1 (dashboards), 10.2 (chaos), 10.3 (OPA), 10.4 (runbooks), 10.5 (e2e saga test)
- **OTel SDK docs:** https://opentelemetry.io/docs/languages/java/
- **Grafana Tempo:** https://grafana.com/oss/tempo/
- **Chaos Mesh:** https://chaos-mesh.io/
