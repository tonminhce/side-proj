---
audience: on-call engineer, SRE
project: side-project
date: 2026-07-06
how-to-use: per-alert playbook. When an alert fires, find it here. Pair with BUG-TRIAGE.md (process) and OBSERVABILITY-RUNBOOK.md (dashboard setup).
---

# Alerting Runbook — side-project

> **Setup:** Alerts are defined in `platform/observability/prometheus-rules/critical.yaml` (per ADR-19).
> **Routing:** Critical alerts page on-call via PagerDuty; high alerts Slack; medium Slack only.
> **Each alert below:** What it means, how to investigate, how to mitigate, how to fix.

---

## How to use this runbook

When an alert fires:

1. **Page acknowledges** the alert (via PagerDuty)
2. **Open Grafana** → dashboards → relevant service dashboard
3. **Find the alert** in this runbook (alphabetical or by service)
4. **Follow the playbook:** Investigate → Mitigate → Fix
5. **Update story** in `sprint-status.yaml` if it's a real bug
6. **Write post-mortem** if Sev-0/1 (per `BUG-TRIAGE.md`)

---

## 1. Critical alerts (page on-call)

### Alert: `OutboxPublishFailuresHigh`

**Severity:** Critical
**Source:** `outbox_publish_failures_total` rate > 0.1/sec for 2 min
**Risk bound:** R-04 (Debezium outbox duplicates → mitigated via Modulith outbox bridge)
**Service:** All

#### What it means

The Modulith outbox bridge is failing to publish events to Kafka at > 0.1 failures/sec. This could mean:
- Kafka is unreachable
- The bridge itself is stuck
- Network connectivity issue between bridge and Kafka

#### Investigate

```bash
# 1. Check Kafka health
docker exec -it kafka kafka-broker-api-versions --bootstrap-server localhost:9092
# Expected: list of API versions

# 2. Check bridge logs
kubectl logs -n side-project -l app=modulith-bridge --tail=200 | grep -E "(error|outbox|kafka)"

# 3. Check outbox backlog
psql -h localhost -U catalog -d catalog -c "SELECT COUNT(*) FROM outbox WHERE published_at IS NULL;"
# If count > 10k, page DBA

# 4. Check network
kubectl exec -n side-project -l app=modulith-bridge -- nslookup kafka
kubectl exec -n side-project -l app=modulith-bridge -- nc -zv kafka 9092
```

#### Mitigate

```bash
# 1. If Kafka is down, restart it
kubectl rollout restart statefulset/kafka -n side-project

# 2. If bridge is stuck, restart it
kubectl rollout restart deployment/modulith-bridge -n side-project

# 3. If network issue, contact SRE
```

#### Fix

Long-term fix depends on root cause. Common:
- Add connection retry in bridge
- Increase Kafka replicas for HA
- Add network policy to ensure bridge can reach Kafka

---

### Alert: `SnowflakeWorkerIdSourceInsecure`

**Severity:** Critical
**Source:** `snowflake_worker_id_source` max == 2 (SecureRandom) for 1 min
**Risk bound:** R-22 (Snowflake worker-id collision → mitigated by strict mode)
**Service:** All

#### What it means

A service is generating Snowflake IDs using `SecureRandom` (source=2) instead of from `POD_NAME` (source=1). This means worker-ID collision risk — if multiple pods use the same random ID, Snowflake IDs will collide.

#### Investigate

```bash
# 1. Identify the service
kubectl get pods -n side-project -l app=catalog-prod -o yaml | grep POD_NAME
# Should be set to e.g. 'catalog-prod-0'

# 2. If POD_NAME is not set, deployment manifest is wrong
kubectl get deployment catalog-prod -n side-project -o yaml | grep -A 5 env
# Should have POD_NAME configured (per ADR-22)
```

#### Mitigate

```bash
# 1. Set POD_NAME in deployment
kubectl set env deployment/catalog-prod -n side-project POD_NAME=catalog-prod-0
kubectl rollout restart deployment/catalog-prod -n side-project

# 2. Verify after restart
kubectl logs -n side-project -l app=catalog-prod | grep "snowflake.worker.id.source"
# Should show: source=podname (1)
```

#### Fix

Update Helm chart values:
```yaml
env:
  - name: POD_NAME
    valueFrom:
      fieldRef:
        fieldPath: metadata.name  # K8s pod name = "service-XXX"
```

---

### Alert: `PossiblePanInLogs`

**Severity:** Critical
**Source:** `log_entries_with_pan_pattern_total` rate > 0
**Risk bound:** R-15 (PCI scope → mitigated by Stripe Elements + OTel log redaction)
**Service:** All

#### What it means

The OTel log redaction processor detected a field matching PAN pattern (`\d{13,19}`) that wasn't redacted. This is a PCI scope violation.

#### Investigate

```bash
# 1. Find the log line
# Grafana → Loki → query: {service="<service>"} |~ "\\d{13,19}"

# 2. Identify the source field
# Check which field is unmasked
# Example: log line with "card_number: 4242424242424242" without redaction

# 3. Identify the file / commit
# Check recent deploys (ArgoCD history)
argocd app history side-project-prod
```

#### Mitigate

```bash
# 1. Roll back the offending deploy IMMEDIATELY
argocd app rollback side-project-prod --revision <previous-good>

# 2. If rollback is too slow, kill the bad pods
kubectl delete pod -n side-project -l app=<service>
```

#### Fix

- Add the field to OTel redaction config
- Add a CI lint that rejects log statements that include `card_number`, `cvv`, or any field named like card data
- Add a test that verifies the redaction works

---

### Alert: `KafkaConsumerLagHigh`

**Severity:** Critical
**Source:** `kafka_consumer_records_lag` > 10000 for 5 min
**Risk bound:** R-04, general event-driven health
**Service:** All (consumer side)

#### What it means

A Kafka consumer is falling behind — events are accumulating without being processed. This could be:
- Consumer is slow (DB slow query, external API timeout)
- Consumer is crashed
- Consumer is on an event with a deserialization failure
- Producer is producing faster than consumer can process

#### Investigate

```bash
# 1. Check which consumer
docker exec -it kafka kafka-consumer-groups --bootstrap-server localhost:9092 --describe --all-groups | awk '$5 > 1000 {print}'

# 2. Check consumer health
kubectl logs -n side-project -l app=<consumer-service> --tail=200

# 3. Check consumer pod status
kubectl get pods -n side-project -l app=<consumer-service>

# 4. Check if there's a poison message
# Look for repeated stack traces in logs
```

#### Mitigate

```bash
# 1. If consumer crashed, restart
kubectl rollout restart deployment/<consumer-service> -n side-project

# 2. If poison message, skip it
# (use kafka-consumer-groups --reset-offsets --to-offset <last-good> --execute)
# Note: requires careful review to avoid data loss
```

#### Fix

- Add slow-query alerts
- Add consumer concurrency
- Implement dead-letter queue for poison messages

---

### Alert: `CatalogReadP99High`

**Severity:** Critical (NFR breach)
**Source:** `http_server_requests_seconds_bucket{uri="/api/catalog/products"}` p99 > 100ms for 10 min
**NFR bound:** NFR-PERF-1 (catalog p99 < 100ms)
**Service:** Catalog

#### What it means

Catalog read latency is exceeding the SLO. Users are experiencing slow page loads.

#### Investigate

```bash
# 1. Check current p99 (Grafana)
# 2. Check ES read latency
curl -s 'http://es:9200/_nodes/stats/indices/search?pretty' | jq '.nodes | to_entries[].value.indices.search'

# 3. Check if CDC is lagging
psql -h localhost -U catalog -d catalog -c "SELECT MAX(created_at), MAX(published_at) FROM outbox;"

# 4. Check DB performance
psql -h localhost -U catalog -d catalog -c "SELECT * FROM pg_stat_statements ORDER BY total_time DESC LIMIT 5;"

# 5. Get a slow trace
# Grafana → Tempo → service.name=catalog-service AND duration>100ms
```

#### Mitigate

```bash
# 1. If ES is the bottleneck, scale catalog-service
kubectl scale deployment/catalog-prod -n side-project --replicas=10

# 2. If CDC is lagging, restart the bridge
kubectl rollout restart deployment/modulith-bridge -n side-project
```

#### Fix

- Add caching for popular products (Redis)
- Optimize ES index (per architecture "Elasticsearch read-side")
- Add CDN for product images

---

### Alert: `PaymentSuccessRateLow`

**Severity:** Critical (NFR breach)
**Source:** `sum(rate(payment_capture_total{outcome="success"}[5m])) / sum(rate(payment_capture_total[5m]))` < 0.95
**NFR bound:** NFR-AVAIL-1 (99.9% availability, 99.5% payment success target)
**Service:** Payment

#### What it means

Payment success rate is below 95%. This is a critical issue — every failed payment is lost revenue.

#### Investigate

```bash
# 1. Check Stripe API status
# (Stripe status page: https://status.stripe.com)

# 2. Check Stripe error types
# Grafana → Loki → query: {service="payment-service"} |= "stripe"

# 3. Check our rate-limiter blocks
redis-cli HGETALL "rate_limit:*" | head

# 4. Check if specific cards are failing
psql -h localhost -U payment -d payment -c "SELECT stripe_payment_intent_id, last_error, last_attempt_at FROM payments WHERE status = 'failed' ORDER BY last_attempt_at DESC LIMIT 10;"
```

#### Mitigate

```bash
# 1. If Stripe API is having issues, wait for them to resolve
# 2. If our rate-limiter is too aggressive, adjust limits
# 3. If specific BINs are failing, add them to BIN-block list (R-05 mitigation)
```

#### Fix

- Implement Stripe webhook retry with exponential backoff
- Add BIN velocity check (already per ADR-24)
- Add automatic Stripe error categorization

---

### Alert: `SAGAStateMachineStuck`

**Severity:** Critical
**Source:** Order stuck in non-terminal state for > 5 min
**Risk bound:** Saga recoverability (architecture ADR-12)
**Service:** Order

#### What it means

An order has been in `STOCK_RESERVED` or `PAYMENT_PENDING` state for > 5 min. The Modulith saga-recovery routine should pick this up automatically — if it doesn't, saga is broken.

#### Investigate

```bash
# 1. Find stuck orders
psql -h localhost -U order -d order -c "SELECT uuid, state, updated_at FROM orders WHERE state IN ('STOCK_RESERVED', 'PAYMENT_PENDING', 'PAID') AND updated_at < now() - interval '5 minutes';"

# 2. Check saga-recovery logs
kubectl logs -n side-project -l app=order-prod | grep -i "saga.recovery"

# 3. Check if recovery routine ran
psql -h localhost -U order -d order -c "SELECT * FROM order_state_transition WHERE to_state = 'RECOVERED' ORDER BY created_at DESC LIMIT 5;"
```

#### Mitigate

```bash
# 1. Manually trigger recovery for stuck orders
# (use a recovery-script or run the saga-recovery routine manually)

# 2. If recovery is broken, escalate to Architect
```

#### Fix

- Saga-recovery routine should run on a cron schedule (every 1 min)
- Add dead-letter queue for orders that can't be recovered
- Manual recovery runbook for stuck orders

---

## 2. High-severity alerts (Slack only)

### Alert: `CarrierApisDegraded`

**Severity:** High
**Source:** `rate(carrier_http_failures_total[5m])` > 0.5 for 10 min
**Risk bound:** R-13 (carrier downtime)
**Service:** Fulfillment

#### What it means

Carrier API (GHN/GHTK/Viettel Post) is having issues. Shipments can't be created or tracked.

#### Investigate

```bash
# 1. Check carrier status
# - GHN: https://ghn.vn
# - GHTK: https://giaohangtietkiem.vn
# - Viettel Post: https://viettelpost.com.vn

# 2. Check our logs
kubectl logs -n side-project -l app=fulfillment-prod --tail=200 | grep -i "carrier"
```

#### Mitigate

```bash
# 1. Enable carrier-degraded UI state (per FR-38)
# Users see "delivery may be delayed" message
# 2. Retry with jitter (per FR-39) — should auto-recover
# 3. If 1+ carriers down, route to working ones
```

#### Fix

- Add automatic failover to other carriers
- Add circuit breaker (per NFR-AVAIL-3, Resilience4j)

---

### Alert: `CheckoutCompensationsHigh`

**Severity:** High
**Source:** `rate(checkout_compensated_total[5m])` > 1/sec
**Risk bound:** Saga health
**Service:** Checkout

#### What it means

Many checkouts are failing → compensating (releasing inventory, cancelling payment). Indicates upstream issue (Stripe, inventory, etc.).

#### Investigate

```bash
# 1. Check the failure reason in the most recent compensations
# Grafana → Loki → query: {service="checkout-service"} |~ "compensat"

# 2. Check Stripe API status
# (per PaymentSuccessRateLow alert)

# 3. Check inventory exhaustion
psql -h localhost -U inventory -d inventory -c "SELECT variant_uuid, SUM(delta) AS on_hand FROM inventory_ledger GROUP BY variant_uuid ORDER BY 2 LIMIT 10;"
```

#### Mitigate

- Same as PaymentSuccessRateLow if Stripe-related
- If inventory-related, investigate hot variant

#### Fix

- Add pre-checkout stock validation (don't start checkout if no stock)
- Improve error messages to user

---

### Alert: `StripeWebhookLag`

**Severity:** High
**Source:** `histogram_quantile(0.99, rate(stripe_webhook_received_to_processed_seconds_bucket[5m]))` > 30s
**Risk bound:** R-03 (webhook dedup)
**Service:** Payment

#### What it means

Stripe webhooks are taking > 30s to process. This could lead to:
- Idempotency key conflict (if saga retry kicks in)
- Order state stuck

#### Investigate

```bash
# 1. Check webhook handler performance
kubectl logs -n side-project -l app=payment-prod --tail=200 | grep -i "webhook"

# 2. Check DB performance for the dedup table
psql -h localhost -U payment -d payment -c "EXPLAIN ANALYZE SELECT * FROM webhook_dedup WHERE stripe_event_id = 'evt_xxx';"
```

#### Mitigate

- Scale payment service
- Add webhook handler concurrency

#### Fix

- Optimize webhook handler (async processing)
- Add DB index on webhook_dedup.stripe_event_id

---

## 3. Medium-severity alerts (Slack only)

### Alert: `AuthFailureRateElevated`

**Severity:** Medium
**Source:** `sum(rate(auth_login_failure_total[5m])) / sum(rate(auth_login_attempt_total[5m]))` > 0.1
**Risk bound:** AT-02 (credential stuffing)
**Service:** Customer

#### What it means

> 10% of login attempts are failing. Either legitimate users can't log in (UI bug, password change), OR an attack is happening (R-05 / AT-02).

#### Investigate

```bash
# 1. Check if it's a specific user vs widespread
psql -h localhost -U customer -d customer -c "SELECT email, COUNT(*) FROM auth_login_attempt WHERE success = false AND created_at > now() - interval '1 hour' GROUP BY email ORDER BY 2 DESC LIMIT 10;"

# 2. Check for credential stuffing
psql -h localhost -U customer -d customer -c "SELECT client_ip, COUNT(DISTINCT email) AS unique_users FROM auth_login_attempt WHERE success = false AND created_at > now() - interval '1 hour' GROUP BY client_ip ORDER BY 2 DESC LIMIT 10;"
```

#### Mitigate

- If attack: rate-limit the offending IP
- If widespread legitimate failures: check deploys (regression?)

#### Fix

- Improve rate-limiter thresholds (per ADR-24)
- Add IP-reputation feed

---

### Alert: `OutboxBacklogGrowing`

**Severity:** Medium
**Source:** `outbox_pending_rows` > 1000
**Risk bound:** R-04
**Service:** All

#### What it means

> 1000+ events in the outbox waiting to be published. Bridge is lagging.

#### Investigate

```bash
# 1. Check the count
psql -h localhost -U catalog -d catalog -c "SELECT COUNT(*) FROM outbox WHERE published_at IS NULL;"

# 2. Check bridge logs
kubectl logs -n side-project -l app=modulith-bridge --tail=200 | grep -E "(error|kafka)"
```

#### Mitigate

- Same as OutboxPublishFailuresHigh

---

### Alert: `RedisOOMM`

**Severity:** Medium
**Source:** `redis_memory_used_bytes / redis_memory_max_bytes` > 0.8
**Risk bound:** R-11 (Redis OOM)
**Service:** All (cache + rate-limiter)

#### What it means

Redis is using > 80% of its memory. OOM imminent.

#### Investigate

```bash
# 1. Check Redis memory
redis-cli INFO memory | grep used_memory_human
redis-cli Info keyspace

# 2. Find largest keys
redis-cli --bigkeys

# 3. Check rate-limiter state
redis-cli HGETALL "rate_limit:*" | head
```

#### Mitigate

- Flush stale rate-limiter keys
- Increase Redis memory limit (if budget allows)

#### Fix

- Add TTL on rate-limiter keys (already per architecture)
- Use Redis cluster for memory scaling

---

## 4. Recovery procedures

### R1: Force flush outbox (last resort)

```bash
# 1. Pause the bridge
kubectl scale deployment/modulith-bridge -n side-project --replicas=0

# 2. Mark all outbox rows as published (effectively dropping them)
psql -h localhost -U catalog -d catalog -c "UPDATE outbox SET published_at = now() WHERE published_at IS NULL;"

# 3. Restart the bridge
kubectl scale deployment/modulith-bridge -n side-project --replicas=1

# 4. Verify
psql -h localhost -U catalog -d catalog -c "SELECT COUNT(*) FROM outbox WHERE published_at IS NULL;"
# Expected: 0

# ⚠️ WARNING: This loses events. Use only as last resort.
```

### R2: Force-consume stuck event

```bash
# 1. Find the stuck event
psql -h localhost -U catalog -d catalog -c "SELECT * FROM processed_event ORDER BY processed_at DESC LIMIT 5;"

# 2. Find the unprocessed offset
docker exec -it kafka kafka-consumer-groups --bootstrap-server localhost:9092 --describe --group catalog-service

# 3. Reset offset to a known good point
docker exec -it kafka kafka-consumer-groups --bootstrap-server localhost:9092 --reset-offsets --to-offset <offset> --group catalog-service --topic catalog.product.created --execute
```

### R3: Reindex ES (per NFR-MIG-3)

Per `DEVOPS-RUNBOOK.md` §7 — full alias-swap pattern.

### R4: Rollback

Per `RELEASE-PROCESS.md` §6.

---

## 5. Alert tuning

### When to adjust an alert

- **Alert fires but is not actionable** → it's noise, fix the alert
- **Alert fires but action doesn't help** → the runbook needs improvement
- **Alert never fires but incident happens** → coverage gap
- **Multiple alerts fire for same root cause** → consolidate to one upstream alert

### Quarterly review

Every quarter, SRE reviews:
- Alert noise ratio (false positives / total)
- MTTR trends (mean time to resolve)
- Coverage gaps
- New runbooks needed

Update this file with the outcomes.

---

## 6. Cross-references

- **Alert definitions:** `platform/observability/prometheus-rules/critical.yaml` (per architecture ADR-19)
- **Dashboards:** `OBSERVABILITY-RUNBOOK.md` §4
- **Incident response process:** `BUG-TRIAGE.md`
- **Release + rollback:** `RELEASE-PROCESS.md`
- **Operational runbook:** `DEVOPS-RUNBOOK.md`
- **Risk register (what alerts map to):** `RISK-REGISTER.md`
- **SLO error budget:** `OBSERVABILITY-RUNBOOK.md` §9
- **Chaos experiments (what alerts are validated by):** `OBSERVABILITY-RUNBOOK.md` §8
- **Metrics catalog:** `OBSERVABILITY-RUNBOOK.md` §3
- **Onboarding:** `AGENT-ONBOARDING.md`
- **Glossary:** `GLOSSARY.md`
