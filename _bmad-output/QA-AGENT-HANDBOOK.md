---
audience: QA agent
project: side-project
date: 2026-07-06
how-to-use: QA-specific work. Chaos testing, end-to-end saga test, regression suite, performance validation. Pair with INTEGRATION-TEST-CHEATSHEET.md for unit/IT patterns.
---

# QA Agent Handbook — side-project

> **QA scope:** Validate that the architecture's R-XX mitigations actually work under failure. Run end-to-end saga tests. Run chaos experiments. Validate NFRs.
> **Most important:** Per `RISK-REGISTER.md`, the 7 Critical risks (R-01, R-02, R-03, R-04, R-05, R-06, R-15) MUST be verified by chaos tests before launch.

---

## 1. QA scope by phase

| Phase | What QA tests | When |
|---|---|---|
| Per story AC | Test the story's Given/When/Then AC | Per-PR |
| Per sprint | Run chaos experiments + e2e saga test + NFR checks | Sprint-end |
| Pre-launch | Full regression + load test + security scan | Before v1.0 |
| Post-launch | SLO compliance monitoring + monthly chaos | Ongoing |

---

## 2. Per-story AC validation

For each story in `epics.md`, run the test that proves its Given/When/Then.

### Workflow

```bash
# 1. Read the story's AC
grep -A 10 "^### Story 1.6:" _bmad-output/planning-artifacts/epics.md

# 2. Find the existing test (if any) or write a new one
ls services/inventory/src/test/java/.../InventoryReservationServiceIT.java

# 3. Run it
mvn -pl services/inventory verify -Dit.test=InventoryReservationServiceIT#hundredConcurrentReservationsYieldsOneSuccess

# 4. Repeat 100x to verify (per architecture, critical-path tests)
for i in {1..100}; do
  mvn -pl services/inventory verify -Dit.test=InventoryReservationServiceIT#hundredConcurrentReservationsYieldsOneSuccess -q
  [ $? -ne 0 ] && echo "FAIL on run $i" && break
done
```

### Mark story verified in sprint-status

Once all tests pass, update `sprint-status.yaml`:

```yaml
1-6-reservation-with-ttl-fr-9-solves-di-01-root-cause: review  # QA-verified, ready for merge
```

---

## 3. End-to-end saga test (per architecture, Story 10.5)

### Goal

Validate the full checkout saga from cart → stock reservation → payment → order placed → confirmation, under failure injection.

### Test scenarios (per architecture §"Detail: ADR-12")

| Scenario | Failure injected | Expected behavior |
|---|---|---|
| Happy path | None | Order placed, payment captured, notification sent |
| Concurrent reservation race | 10 concurrent checkouts of 1 stock unit | 1 success + 9 `InsufficientStockException` |
| Stripe API timeout | Stripe API delayed 5s | Saga retries with idempotency key, eventually succeeds |
| Webhook replay | Same `event.id` arrives 3 times | Processed once, 2 no-ops via `webhook_dedup` |
| Outbox bridge failure | Kafka unavailable for 60s | Outbox rows accumulate; bridge recovers; events published |
| Saga compensation | Payment fails after stock reserved | Inventory released, no orphan stock |
| Modulith restart mid-saga | Kill Modulith between `payment.authorize` and `order.placed` | On startup, saga-recovery routine finds stuck order, replays from `order_state_transition` log |
| CDC lag | Slow CDC propagation | Search index shows stale-by-N-seconds, alerts at 30s |
| Snowflake worker-id collision | Two pods with same worker-id | One throws `WorkerIdMissingException` (R-22 mitigation) |
| VN tax-invoice missing credentials | No `vietnam_tax_authority_credential` row | InvoiceService fails-fast at startup |

### Implementation

```java
@SpringBootTest
class SagaE2EIT extends AbstractIT {

    @Autowired CheckoutService checkout;
    @Autowired OrderService order;
    @Autowired PaymentService payment;
    @Autowired KafkaConsumer<String, byte[]> consumer;

    @RepeatedTest(50)  // run 50x to detect race conditions
    void happyPathSaga() {
        // 1. Create cart
        var cart = cartService.create(customerId);
        cartService.addLine(cart.id, variantId, 1);

        // 2. Start checkout
        var checkout = checkoutService.start(cart.id, address);
        assertThat(checkout.state).isEqualTo("CREATED");

        // 3. Simulate payment
        paymentService.authorize(checkout.id);

        // 4. Wait for saga to advance
        await().atMost(10, SECONDS).untilAsserted(() -> {
            var o = orderService.getByCheckoutId(checkout.id);
            assertThat(o.state).isEqualTo("PAID");
        });

        // 5. Verify events were published
        var records = kafkaConsumer.poll(Duration.ofMillis(500));
        assertThat(records.count()).isGreaterThan(0);
    }
}
```

---

## 4. Chaos experiments (per architecture, Story 10.2)

### Per-P0-risk chaos tests (in `platform/chaos/chaos-mesh/`)

For each of the 7 P0 risks, write a Chaos Mesh experiment that exercises the mitigation. See `OBSERVABILITY-RUNBOOK.md` §8 for full details.

### Manual chaos test (local, no K8s required)

```bash
# R-04: Kafka broker kill simulation
docker compose -f dev/docker-compose.yml stop kafka
# Service should continue running; outbox should accumulate
sleep 30
psql -h localhost -U catalog -d catalog -c "SELECT COUNT(*) FROM outbox WHERE published_at IS NULL;"
# Expected: count > 0 (outbox is queueing)

docker compose -f dev/docker-compose.yml start kafka
# Service should resume publishing; outbox should drain
sleep 60
psql -h localhost -U catalog -d catalog -c "SELECT COUNT(*) FROM outbox WHERE published_at IS NULL;"
# Expected: count = 0 (drained)

# Verify no events lost
psql -h localhost -U catalog -d catalog -c "SELECT COUNT(*) FROM outbox WHERE published_at IS NOT NULL;"
# Expected: count = total events (none lost)
```

```bash
# R-05: Card-testing burst simulation
# (Run against staging, not local — Stripe test mode only)

# Use Stripe test cards to make 1000+ rapid failed charges
# Verify: requests over threshold return 429
# Verify: BIN velocity check fires after N cards from same BIN
# Verify: account not actually charged (Stripe test mode)

# Local proxy: use a tool like vegeta or wrk
echo "POST https://api.stripe.com/v1/charges" | vegeta attack -duration=60s -rate=1000 | vegeta report
```

```bash
# R-15: PAN-in-logs detection simulation
# (Unit test in INTEGRATION-TEST-CHEATSHEET pattern)

# Write a test that tries to log a PAN-shaped field
# Expected: field is redacted at OTel processor level
# Assert: log line does not contain raw PAN
```

---

## 5. Performance validation (NFRs)

### Latency targets (per NFR-PERF)

| Endpoint | p50 | p95 | p99 | SLO |
|---|---|---|---|---|
| `GET /api/catalog/products` | <30ms | <70ms | <100ms | NFR-PERF-1 |
| `GET /api/search/products` | <100ms | <250ms | <300ms | NFR-PERF-2 |
| `POST /api/cart/lines` | <50ms | <150ms | <300ms | — |
| `POST /bff/storefront/checkout` | <200ms | <500ms | <800ms | — |
| Stripe API call (in payment flow) | <1s | <2s | <3s | — |

### Run load test (using k6)

```bash
# 1. Install k6
brew install k6

# 2. Run a 5-minute test
k6 run --vus 50 --duration 5m - <<'EOF'
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    thresholds: {
        'http_req_duration{name:catalog}': ['p(99)<100'],  // NFR-PERF-1
        'http_req_duration{name:search}':  ['p(99)<300'],  // NFR-PERF-2
    },
};

export default function () {
    let res = http.get('http://localhost:8080/api/catalog/products?page=1', { tags: { name: 'catalog' } });
    check(res, { 'status is 200': (r) => r.status === 200 });
    sleep(1);
}
EOF

# 3. Check thresholds
# Exit code 0 = all thresholds met; non-zero = at least one SLO breach
```

### Capacity target (per addendum A3)

- **50,000 orders/day** = ~2,000 orders/hour at peak
- **500 concurrent checkouts at peak**
- Run 2x that = 1,000 concurrent users to verify scaling headroom

```bash
k6 run --vus 1000 --duration 30m - <<'EOF'
import http from 'k6/http';

export default function () {
    // Simulate full checkout flow
    let res1 = http.get('http://localhost:8080/api/catalog/products');
    let res2 = http.post('http://localhost:8080/api/cart/lines', JSON.stringify({ variantId: 1, qty: 1 }), { headers: { 'Content-Type': 'application/json' } });
    let res3 = http.post('http://localhost:8080/bff/storefront/checkout', JSON.stringify({ cartId: 'abc' }), { headers: { 'Content-Type': 'application/json' } });
    sleep(Math.random() * 5);
}
EOF
```

---

## 6. Security scanning (pre-release)

### Automated scans

```bash
# 1. Static analysis (SpotBugs, SonarQube)
mvn -B verify -P security-scan

# 2. Dependency vulnerability scan (OWASP)
mvn -B org.owasp:dependency-check-maven:check

# 3. Container image scan (Trivy)
trivy image side-project/catalog-service:1.0.0

# 4. License compliance
mvn -B license:check
```

### Manual security tests

- **PCI-DSS scope verification:** Run through every code path; verify no PAN ever touches our systems
- **Penetration test (R-05):** Hire external pen-tester or run OWASP ZAP
- **Authentication tests (AT-02):** Try to brute-force a customer account; verify lockout triggers after 5 failures
- **Authorization tests (R-15 + Sprint 3):** Try to access another user's data; verify server-side RBAC blocks
- **HMAC verification tests (R-04, AT-03):** Try to inject a fake event; verify consumer rejects bad signature

---

## 7. Regression suite

### Per-sprint regression (run before each sprint closes)

```bash
# Run ALL unit + integration tests
mvn verify -P integration-tests

# Run chaos tests
./scripts/run-chaos-suite.sh

# Run saga e2e
mvn -pl services/checkout verify -Dit.test=SagaE2EIT

# Run performance smoke
k6 run --vus 10 --duration 1m load-test.js
```

### Per-release regression (more thorough)

Add to the above:
- Full chaos game day (all experiments)
- 24-hour soak test (find memory leaks, slow drifts)
- Full dependency vulnerability scan
- Backup + restore drill (per `DEVOPS-RUNBOOK.md` §15)

---

## 8. Sprint-by-sprint QA tasks

| Sprint | QA task |
|---|---|
| 0 | Verify Sprint 0 stories 0.1..0.5 acceptance |
| 1 | Verify Story 1.6 (reservation) passes 100x concurrent test |
| 2 | Verify Story 2.5 (saga) happy + compensation paths |
| 3 | **CRITICAL**: Verify Stories 3.1, 3.2, 3.3, 3.4 (R-03, R-05, R-15 mitigations) + chaos tests |
| 4 | Verify Story 4.6 (webhook retry) + carrier-degraded test |
| 5 | Verify Story 5.4 (account lockout AT-02) + 5.2 (PDPD export) |
| 6 | Verify Story 6.2 (diacritic search R-07) with Vietnamese queries |
| 7 | Verify Story 7.3 (cumulative refund safety DI-07) |
| 8 | Verify Story 8.x (admin role gating) |
| 9 | **CRITICAL**: Verify Stories 9.2 + 9.2b (LC-03 + Q5 closure) + tax-invoice fail-fast |
| 10 | **CRITICAL**: Run all chaos experiments + e2e saga test + verify R-XX mitigations end-to-end |

---

## 9. Bug reporting (post-sprint)

When QA finds a bug:

1. **Verify** it's reproducible (at least 3x in same env)
2. **Categorize** by risk:
   - **Critical** (P0 risk not mitigated): page on-call
   - **High** (NFR breach): file in current sprint
   - **Medium** (edge case): file in next sprint
   - **Low** (cosmetic): backlog
3. **File the issue** with:
   - Reproduction steps
   - Expected vs actual
   - Risk ID (R-XX, DI-XX, AT-XX, LC-XX) if applicable
   - Trace ID + log lines (for SRE debugging)
4. **Link to the story** that owns the affected functionality

---

## 10. Cross-references

- **All risks to verify:** `RISK-REGISTER.md` (15 risks)
- **Architecture binding:** `ADR-INDEX.md` + `architecture.md` §"Critical-Risk-to-ADR Binding Table"
- **Chaos experiment specs:** `OBSERVABILITY-RUNBOOK.md` §8
- **Test patterns:** `INTEGRATION-TEST-CHEATSHEET.md`
- **Performance NFRs:** `ARCHITECTURE-QUICKREF.md` §12
- **Per-sprint backend work:** `SPRINT-1-DEV-HANDBOOK.md` + `epics.md`
