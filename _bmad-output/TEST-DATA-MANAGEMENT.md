---
audience: dev, QA, SRE
project: side-project
date: 2026-07-06
how-to-use: how to create + manage test data. Pair with INTEGRATION-TEST-CHEATSHEET.md (tests) + DATA-MODEL.md (schema).
---

# Test Data Management — side-project

> **Goal:** Fast, hermetic, realistic test data. NO sensitive PII in test data.
> **Convention (per addendum §A3):** Test data should not include real customer data; use synthesized data.

---

## 1. Test data principles

1. **Hermetic:** Each test creates its own data; no shared state
2. **Realistic:** Use realistic data shapes (Vietnamese names, VND currency, real phone formats)
3. **Isolated:** Tests don't share DB state
4. **Deterministic:** Same seed → same data
5. **Fast:** Test data setup < 1 sec
6. **Safe:** NO real PII (per addendum §A3 / R-15 / FR-49)
7. **Cleanup:** Drop test data after test (TRUNCATE tables, not DELETE)

---

## 2. Levels of test data

### Unit test data (in code)

```java
// Inline test data in unit tests
@Test
void calculateDiscount_givenVipCustomer_applies15Percent() {
    var customer = new Customer(/* ... */);  // anonymous
    var order = new Order(/* ... */);
    var discount = pricingService.calculate(customer, order);
    assertEquals(0.15, discount.getRate());
}
```

Pros: explicit, easy to read
Cons: lots of boilerplate for complex objects

### Integration test data (in test fixtures)

```java
// TestFixtures.java - centralized
public class TestFixtures {
    public static Customer givenVietnameseCustomer() {
        return new Customer(/* Vietnamese name, +84 phone, VND */);
    }

    public static Product givenIPhone15Pro() {
        return new Product(/* ... */);
    }
}
```

Pros: shared, consistent
Cons: single test class can become large

### E2E test data (seed scripts)

```bash
# dev/seed-data/seed-iphone.sh - seed a real product for E2E
./dev/seed-data/seed-iphone.sh
```

Pros: real, visible
Cons: not hermetic, hard to reset

---

## 3. Data factories (preferred pattern)

### Java factory pattern (per service)

```java
// services/customer/src/test/java/.../support/CustomerTestDataFactory.java
public class CustomerTestDataFactory {
    private static final AtomicLong SEED = new AtomicLong(System.currentTimeMillis());

    public static Customer aCustomer() {
        long seed = SEED.incrementAndGet();
        return Customer.builder()
            .email("test-user-" + seed + "@example.com")
            .name("Nguyen Van Test")  // Vietnamese name
            .phone("+8490" + String.format("%07d", seed % 10000000))
            .role(Role.CUSTOMER)
            .isActive(true)
            .build();
    }

    public static Customer aVipCustomer() {
        return aCustomer().toBuilder()
            .role(Role.VIP)  // if applicable
            .loyaltyPoints(10000)
            .build();
    }

    public static Customer aLockedAccount() {
        return aCustomer().toBuilder()
            .failedLoginCount(5)
            .lockedUntil(Instant.now().plus(30, ChronoUnit.MINUTES))
            .build();
    }

    public static Address aHcmAddress() {
        return Address.builder()
            .recipientName("Nguyen Van Test")
            .phone("+84901234567")
            .province("Ho Chi Minh")
            .district("Quan 1")
            .commune("Phuong Ben Nghe")
            .street("123 Le Loi")
            .postalCode("700000")
            .build();
    }
}
```

### Why factories (vs fixtures)?

- Each test gets a fresh instance (no shared state)
- Each test has unique data (no cross-test pollution)
- Easy to customize per test (just override fields)
- Realistic + reproducible

---

## 4. Test data by service

### Catalog

```java
ProductTestDataFactory:
    givenProduct()                // iPhone 15 Pro, $30M
    givenCheapProduct()           // VND 100k
    givenOutOfStockProduct()      // on_hand = 0
    givenMultiVariantProduct()    // color + storage variants
    givenDeletedProduct()         // soft-deleted
    givenVietnameseOnlyProduct()  // no en description
```

### Inventory

```java
InventoryTestDataFactory:
    givenWarehouse()                // "default" or "HCM"
    givenReservation(quantity, ttl) // reservation in 15 min
    givenExpiredReservation()      // already past expiry
    givenStockAt(variant, qty)     // on_hand = qty
```

### Cart

```java
CartTestDataFactory:
    givenEmptyCart()
    givenCartWithLine(variant, qty)
    givenCartWithMultipleLines()  // 3+ lines
    givenExpiredCart()             // past 30 days
```

### Checkout / Order

```java
CheckoutTestDataFactory:
    givenCheckout(priceCents)
    givenPaidCheckout()
    givenStuckCheckout()           // in PAYMENT_PENDING for 1h
    givenCompensatedCheckout()

OrderTestDataFactory:
    givenOrder(items, address)
    givenPaidOrder(items, address)
    givenShippedOrder()
```

### Payment

```java
PaymentTestDataFactory:
    givenPaymentIntent(amount)
    givenCapturedPaymentIntent()
    givenFailedPaymentIntent()
    givenRefundedPaymentIntent()  // partial
    givenWebhookEvent(eventType, eventId)  // for webhook dedup tests
```

### Customer

```java
CustomerTestDataFactory:
    givenVietnameseCustomer()      // Vietnamese name + +84 phone
    givenInternationalCustomer()    // foreign name + email
    givenLockedCustomer()          // 5 failed login attempts
    givenMfaCustomer()             // MFA enrolled
    givenAdminUser()               // staff role
```

---

## 5. Vietnamese test data (per FR-60)

### Names (real Vietnamese names, not "Test User")

```java
public static String randomVietnameseName() {
    String[] firstNames = {"Nguyen Van", "Tran Thi", "Le Hoang", "Pham Minh",
                          "Vu Quoc", "Bui Thanh", "Doan Kim", "Hoang Thi"};
    String[] lastNames = {"An", "Binh", "Cuong", "Duc", "Hieu", "Linh", "Phuong", "Quynh"};
    Random r = new Random();
    return firstNames[r.nextInt(firstNames.length)] + " " + lastNames[r.nextInt(lastNames.length)];
}
```

### Phone numbers (Vietnam format: +84 + 9 digits)

```java
public static String randomVietnamesePhone() {
    Random r = new Random();
    return "+84" + String.format("%09d", r.nextInt(1000000000));
}
```

### Email (use example.com domain, never real domains)

```java
public static String randomEmail() {
    return "test-" + UUID.randomUUID() + "@example.com";
}
```

### Address (Vietnamese administrative hierarchy)

```java
// Use the util's ProvinceDto / DistrictDto / CommuneDto
public static Address aHcmAddress() {
    return Address.builder()
        .province("Ho Chi Minh")
        .district("Quan 1")
        .commune("Phuong Ben Nghe")
        .street("123 Le Loi")
        .build();
}
```

### Currency (VND, no decimals per FR-65)

```java
public static long priceCentsVnd(long vnd) {
    return vnd;  // 1 VND = 1 cent (no fractional unit in VND)
}
```

---

## 6. NO real PII (per addendum §A3 / R-15 / FR-49)

### What NOT to use

- ❌ Real names (use Vietnamese placeholder names)
- ❌ Real phone numbers (use +84 + 9 random digits)
- ❌ Real email addresses (use example.com domain)
- ❌ Real credit card numbers (use Stripe test cards: `4242 4242 4242 4242`)
- ❌ Real addresses (use placeholder addresses)
- ❌ Real national IDs (CMND/CCCD) — never use, even in test data
- ❌ Real bank accounts — never use, even in test data

### What to use

- ✅ Stripe test cards (`4242 4242 4242 4242`, `4000 0027 6000 3184`, etc.)
- ✅ Stripe test IDs (`tok_visa`, `pm_card_visa`, etc.)
- ✅ Vietnamese placeholder names (Nguyen Van Test, etc.)
- ✅ Placeholder addresses (123 Le Loi, Quan 1, HCM)
- ✅ Phone: +84 90 + 7 random digits (e.g., +84901234567)
- ✅ Email: test-<uuid>@example.com

---

## 7. Test data lifecycle

### For unit tests

```java
@AfterEach
void cleanup() {
    // Most unit tests don't have shared state; no cleanup needed
    // If you used TestFixtures.factories, they're scoped to the test
}
```

### For integration tests

```java
@AfterEach
void cleanup() {
    // TRUNCATE all tables (faster than DELETE)
    jdbc.execute("TRUNCATE TABLE outbox, processed_event, products, variants CASCADE");
}
```

### For E2E tests

```bash
# Before test: reset to clean state
./scripts/reset-e2e-data.sh
# Run test
./scripts/run-e2e-test.sh
# After test: snapshot for debugging (or delete)
./scripts/snapshot-e2e-data.sh
```

---

## 8. Test data versioning

### Snapshot strategy

```bash
# dev/seed-data/snapshots/
# - sprint-1-baseline.tar.gz   (initial state at Sprint 1 start)
# - sprint-2-baseline.tar.gz   (after Sprint 1 done)
# - ...

# Restore
tar -xzf snapshots/sprint-1-baseline.tar.gz
psql < snapshots/sprint-1-baseline.sql
```

### Version control

Test data in `dev/seed-data/` is version-controlled (not test-data inside tests). Tests reference fixtures, not raw SQL.

---

## 9. Test data management patterns

### Pattern A: Factory per service (recommended)

```
services/customer/src/test/java/.../support/CustomerTestDataFactory.java
services/inventory/src/test/java/.../support/InventoryTestDataFactory.java
services/order/src/test/java/.../support/OrderTestDataFactory.java
```

Each service has its own factory. Factories are independent but use shared types from util (Address, Money, etc.).

### Pattern B: E2E seed scripts (for saga tests)

```
dev/seed-data/
├── seed-catalog.sh       # creates a real product
├── seed-inventory.sh     # sets up warehouses + initial stock
├── seed-cart.sh          # creates a cart
├── seed-checkout.sh       # creates a checkout in PAYMENT_PENDING
├── seed-e2e-flow.sh      # full happy-path scenario
```

Used for: saga e2e tests, manual smoke tests, dev environment.

### Pattern C: Snapshot for chaos tests

```
tests/chaos/snapshots/
├── r-04-baseline.tar.gz       # before Kafka broker kill
├── r-05-baseline.tar.gz       # before card-testing burst
├── ...
```

Restored between chaos test runs to ensure consistent state.

---

## 10. Test data anti-patterns

### ❌ Anti-pattern 1: Sharing state between tests

```java
// ❌ WRONG
class MyTest {
    static Product sharedProduct;  // shared across tests!

    @Test void test1() { sharedProduct = createProduct(); ... }
    @Test void test2() { use(sharedProduct); ... }  // depends on test1
}

// ✅ RIGHT
class MyTest {
    @Test void test1() { var product = createProduct(); ... }
    @Test void test2() { var product = createProduct(); ... }  // independent
}
```

### ❌ Anti-pattern 2: Real PII in tests

```java
// ❌ WRONG
String myPhone = "+84 901 234 567";  // could be real

// ✅ RIGHT
String myPhone = "+84 90" + randomDigits(7);  // always random
```

### ❌ Anti-pattern 3: Tests that depend on each other

```java
// ❌ WRONG
@Test void a() { /* creates User X */ }
@Test void b() { /* assumes User X exists */ }

// ✅ RIGHT
@Test void a() { var user = createUser(); ... }
@Test void b() { var user = createUser(); /* independent */ }
```

### ❌ Anti-pattern 4: Test data with real API tokens

```java
// ❌ WRONG
String stripeKey = "sk_live_xxx";  // real production key

// ✅ RIGHT
String stripeKey = "sk_test_xxx";  // test mode
```

### ❌ Anti-pattern 5: Tests that mutate global state

```java
// ❌ WRONG: Mutating TimeZone
@Test void test1() {
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    // ...
}

// ✅ RIGHT: Save + restore
@BeforeEach void save() { originalTz = TimeZone.getDefault(); }
@AfterEach void restore() { TimeZone.setDefault(originalTz); }
```

---

## 11. CI integration

### Pre-commit check

```yaml
# .github/workflows/pr.yml (already in CONTRIBUTING.md §4)
- name: Check for real PII in test data
  run: |
    ! grep -rE "(real[_-]?(name|phone|email)|sk[_-]?live)" --include="*.java" --include="*.ts" services/ frontend/ || \
    (echo "Real PII detected" && exit 1)
```

### Lint rule (custom)

Add a custom checkstyle / eslint rule that fails the build if a test file contains:
- `sk_live_` (real Stripe key prefix)
- `+84 9XX-XXX-XXX` (looks like a real Vietnamese phone format)
- `@gmail.com`, `@yahoo.com`, `@outlook.com` (real email domains)
- A real-looking name (e.g., from a public list of common names)

---

## 12. E2E test data setup (chaos + saga)

### Setup

```java
@SpringBootTest
@AutoConfigureMockMvc
class SagaE2EIT extends AbstractIT {

    @BeforeAll
    static void setup() {
        // 1. Reset to clean state
        jdbc.execute("TRUNCATE outbox, processed_event, products, variants, orders, payments, refunds CASCADE");
        
        // 2. Seed a real-ish product catalog
        var p = givenProduct("iphone-15-pro", "iPhone 15 Pro", 30000_00); // 30M VND
        productRepo.save(p);
        
        // 3. Set up inventory
        invRepo.appendLedger(p.uuid, "HCM", 100, "init", null);
        invRepo.appendLedger(p.uuid, "HN", 50, "init", null);
    }

    @Test
    void happyPathSaga() {
        // 1. Create cart
        var cart = cartService.create(customerId, ...);
        cartService.addLine(cart.id, p.uuid, 1);
        
        // 2. Start checkout
        var checkout = checkoutService.start(cart.id, ...);
        
        // 3. Simulate payment
        // (Stripe webhook is called by chaos tools in real env)
        paymentService.simulateWebhook(checkout.paymentIntentId, "succeeded");
        
        // 4. Wait for saga to complete
        await().atMost(10, SECONDS).untilAsserted(() -> {
            var order = orderService.getByCheckoutId(checkout.id);
            assertEquals("PAID", order.state);
        });
    }
}
```

### Cleanup

```java
@AfterEach
void cleanup() {
    // Truncate so next test starts clean
    jdbc.execute("TRUNCATE outbox, processed_event, products, variants, orders, payments, refunds CASCADE");
}
```

---

## 13. Test data for chaos experiments

### R-04 (Kafka broker kill)

```bash
# Setup
psql -c "INSERT INTO outbox (event_id, event_type, aggregate_id, payload) VALUES (1, 'orders.placed', 1, '{}');"
# Trigger chaos
kubectl apply -f platform/chaos/chaos-mesh/r-04-kafka-broker-kill.yaml
# Verify outbox accumulates
sleep 60
psql -c "SELECT COUNT(*) FROM outbox WHERE published_at IS NULL;"
# Should be > 0
# Recovery
kubectl delete -f platform/chaos/chaos-mesh/r-04-kafka-broker-kill.yaml
# Verify outbox drains
psql -c "SELECT COUNT(*) FROM outbox WHERE published_at IS NULL;"
# Should be 0
```

### R-05 (card-testing burst)

```bash
# Setup
# Use Stripe test cards (NOT real cards)
# Run 1000+ rapid POSTs to /api/payment/charge
# Use k6 or vegeta for load
vegeta attack -duration 60s -rate 1000 -targets card-test.json
# Verify
# - Most are blocked (429)
# - BIN velocity check fires
# - Account not actually charged
```

---

## 14. Cross-references

- **Test patterns:** `INTEGRATION-TEST-CHEATSHEET.md`
- **Data model:** `DATA-MODEL.md`
- **API contract:** `API-CONTRACT.md`
- **Sprint 0 setup:** `SPRINT-0-ONBOARDING.md`
- **Local setup:** `LOCAL-DEV-SETUP-CHECKLIST.md`
- **Operational runbook:** `DEVOPS-RUNBOOK.md` §11
- **Compliance (PDPD, R2F):** `COMPLIANCE-VN.md`
- **Security (no PAN):** `SECURITY-MODEL.md`
- **Onboarding:** `AGENT-ONBOARDING.md`
- **Glossary:** `GLOSSARY.md`
