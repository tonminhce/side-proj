---
audience: dev-agent (Amelia), QA agent
project: side-project
date: 2026-07-06
how-to-use: Testcontainers + JUnit patterns. Copy-paste ready. Bootstrap test from `application-test.yml`.
---

# Integration Test Cheatsheet — side-project

> **Conventions:**
> - Unit tests: `src/test/java/.../XTest.java` — JUnit 5 + Mockito, no Spring context.
> - Integration tests: `src/test/java/.../XIT.java` — JUnit 5 + **Testcontainers** + `@SpringBootTest`.
> - **Test naming convention:** `<Class>Test` for unit, `<Class>IT` for integration (Maven Failsafe picks up `IT`).
> - **All tests must pass 100x consecutively** for the critical-path tests (saga, reservation, refund).

---

## 1. Bootstrap a Testcontainers-based IT

### Maven dependency (Story 0.4 should set this up)

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers</artifactId>
    <version>1.19.7</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <version>1.19.7</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>1.19.7</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>kafka</artifactId>
    <version>1.19.7</version>
    <scope>test</scope>
</dependency>
```

### Shared Testcontainers base class

```java
// src/test/java/.../support/AbstractIT.java
@Testcontainers
public abstract class AbstractIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("catalog_test")
        .withUsername("test")
        .withPassword("test");

    @Container
    static KafkaContainer kafka = new KafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.6.0")
    );

    @Container
    static GenericContainer<?> apicurio = new GenericContainer<>("apicurio/apicurio-registry:2.6")
        .withExposedPorts(8080);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("apicurio.url", () -> "http://" + apicurio.getHost() + ":" + apicurio.getMappedPort(8080));
    }
}
```

### Concrete IT example (CatalogService)

```java
@SpringBootTest
@AutoConfigureMockMvc
class CatalogServiceIT extends AbstractIT {

    @Autowired ProductRepository repo;
    @Autowired MockMvc mvc;

    @Test
    void createProductPersistsAndEmitsEvent() throws Exception {
        var result = mvc.perform(post("/api/catalog/products")
                .contentType(APPLICATION_JSON)
                .content("""
                    {
                      "name": "Test Product",
                      "slug": "test-product",
                      "variants": [
                        {"attributes": {"color": "red", "size": "M"}, "priceList": 100000}
                      ]
                    }
                """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.uuid").exists())
            .andReturn();

        var productUuid = JsonPath.read(result.getResponse().getContentAsString(), "$.uuid");

        // 1. Verify persisted
        assertThat(repo.findByUuid(productUuid)).isPresent();

        // 2. Verify outbox row written
        // (in another test, use Testcontainers Kafka to consume and verify)
    }
}
```

### Concrete IT example (concurrent reservation)

```java
@SpringBootTest
class InventoryReservationIT extends AbstractIT {

    @Autowired InventoryReservationService reservationService;

    @BeforeEach
    void seed() {
        // Insert variant with on_hand = 1
        // ...
    }

    @RepeatedTest(100)
    void onlyOneSucceedsOnLastUnit() {
        var result = reservationService.reserve(1L, "HCM", 1L);

        // Either success (only one) or 99 failures
        // We just count successes after the burst
    }

    @Test
    void hundredConcurrentReservationsYieldsOneSuccess() throws Exception {
        var executor = Executors.newFixedThreadPool(100);
        var latch = new CountDownLatch(100);
        var barrier = new CyclicBarrier(100);  // start all at same time
        var successes = new AtomicInteger(0);
        var failures = new AtomicInteger(0);

        for (int i = 0; i < 100; i++) {
            executor.submit(() -> {
                try {
                    barrier.await();
                    reservationService.reserve(1L, "HCM", 1L);
                    successes.incrementAndGet();
                } catch (InsufficientStockException e) {
                    failures.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(30, TimeUnit.SECONDS);

        assertEquals(1, successes.get(), "Exactly one reservation should succeed");
        assertEquals(99, failures.get(), "99 reservations should fail");
    }
}
```

---

## 2. Kafka testing patterns

### Consumer test with Testcontainers Kafka

```java
@SpringBootTest
class CatalogEventConsumerIT extends AbstractIT {

    @Autowired CatalogEventConsumer consumer;  // the @ApplicationModuleListener bean

    @Test
    void consumerIsIdempotent() throws Exception {
        var event = new CatalogProductCreated(123L, "test-product", Instant.now());

        // First delivery: should process
        consumer.handle(event);

        // Mark as processed
        assertThat(processedRepo.existsByEventId(123L)).isTrue();

        // Second delivery (Kafka redelivery simulation): should be no-op
        consumer.handle(event);

        // Verify the side effect didn't happen twice
        verify(notificationClient, times(1)).send(any());
    }
}
```

### Producer test (verify outbox → Kafka pipeline)

```java
@SpringBootTest
class OutboxPublishingIT extends AbstractIT {

    @Autowired OutboxRepository outboxRepo;
    @Autowired KafkaConsumer<String, byte[]> kafkaConsumer;

    @BeforeEach
    void cleanTopics() {
        // Delete test topic for clean state
        // Or use unique consumer group per test
    }

    @Test
    void outboxIsPublishedToKafka() throws Exception {
        // 1. Insert outbox row
        var eventId = snowflakeId();
        outboxRepo.save(new OutboxRow(eventId, "catalog.product.created", "payload", null));

        // 2. Wait for Modulith outbox bridge to pick up
        await().atMost(2, SECONDS).untilAsserted(() -> {
            // 3. Consume from Kafka
            var records = KafkaTestUtils.getRecords(kafkaConsumer, Duration.ofMillis(500));
            assertThat(records.count()).isGreaterThan(0);
        });
    }
}
```

---

## 3. Database testing patterns

### Cleanup between tests (each test starts with empty DB)

```java
@SpringBootTest
@AutoConfigureTestDatabase(replace = NONE)  // use Testcontainers
class CatalogRepositoryIT extends AbstractIT {

    @Autowired ProductRepository repo;
    @Autowired OutboxRepository outboxRepo;
    @Autowired ProcessedEventRepository processedRepo;

    @BeforeEach
    void cleanDb() {
        // Option 1: TRUNCATE all tables
        jdbc.execute("TRUNCATE TABLE products, variants, outbox, processed_event RESTART IDENTITY CASCADE");

        // Option 2: @Sql(scripts = "/sql/clean.sql") annotation
    }

    @Test
    void findBySkuReturnsProduct() {
        seedProduct("ABC-1");
        assertThat(repo.findBySku("ABC-1")).isPresent();
    }
}
```

### Schema migration testing

```java
@SpringBootTest
@AutoConfigureTestDatabase(replace = NONE)
@ActiveProfiles("test")
class MigrationIT extends AbstractIT {

    @Test
    void allMigrationsApplyCleanly() {
        // Flyway auto-runs on startup; if it succeeds, the test passes
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history", Integer.class))
            .isGreaterThan(0);
    }

    @Test
    void downMigrationWorks() {
        // ... but manual down-migration testing is hard. Document schema assumptions instead.
    }
}
```

---

## 4. Mocking patterns

### Mock util/SnowflakeIdGenerator for deterministic tests

```java
@SpringBootTest
class CatalogServiceIT extends AbstractIT {

    @MockBean SnowflakeIdGenerator snowflake;

    @BeforeEach
    void mockSnowflake() {
        // Return deterministic IDs for assertions
        when(snowflake.generateId()).thenReturn(12345L);
    }

    @Test
    void productHasMockedUuid() {
        // ...
        var product = createProduct();
        assertThat(product.getUuid()).isEqualTo(12345L);
    }
}
```

### Mock Stripe API for payment tests

```java
@SpringBootTest
class PaymentServiceIT extends AbstractIT {

    @MockBean StripeClient stripe;

    @Test
    void idempotencyKeyIsReusedAcrossRetries() {
        when(stripe.paymentIntents.capture(any(), any()))
            .thenReturn(mockPaymentIntent("pi_123", "succeeded"));

        // First capture
        var result1 = paymentService.capture(orderId, "payment.authorize");
        verify(stripe).paymentIntents.capture(eq("orderId-payment.authorize"), any());

        // Retry (should reuse same key)
        var result2 = paymentService.capture(orderId, "payment.authorize");
        verify(stripe, times(2)).paymentIntents.capture(eq("orderId-payment.authorize"), any());
    }
}
```

---

## 5. Critical-path test rules

### Must pass 100x consecutively

Per architecture, the following tests are critical-path:

1. **InventoryReservationIT.hundredConcurrentReservationsYieldsOneSuccess** — DI-01 mitigation
2. **PaymentServiceIT.idempotencyKeyIsReusedAcrossRetries** — DI-02 mitigation
3. **ReturnsServiceIT.cumulativeRefundSafetyBlocks** — DI-07 mitigation
4. **AuthServiceIT.accountLockoutAfter5FailedLogins** — AT-02 mitigation

Run them with:

```bash
mvn -pl services/inventory verify -Dtest=InventoryReservationIT#hundredConcurrentReservationsYieldsOneSuccess
mvn -pl services/inventory verify -Dit.test=InventoryReservationIT -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.runOrder=alphabetical
# Repeat 100x via a shell loop or CI matrix
```

### Test data setup helpers

```java
// services/<x>/src/test/java/.../support/TestFixtures.java
public class TestFixtures {
    public static Product givenProduct(String slug) { ... }
    public static Variant givenVariant(long productUuid, String sku) { ... }
    public static InventoryRecord givenStock(long variantUuid, String warehouse, long qty) { ... }
    public static Customer givenCustomer(String email) { ... }
    public static Order givenOrder(long customerUuid, long totalCents) { ... }
}
```

---

## 6. Test profiles & config

### application-test.yml

```yaml
# src/test/resources/application-test.yml
spring:
  jpa:
    hibernate.ddl-auto: validate  # never 'create' or 'update' in tests
  flyway:
    enabled: true
  datasource:
    # Overridden by Testcontainers @DynamicPropertySource

apicurio:
  url: ${apicurio.url}

logging:
  level:
    org.hibernate.SQL: DEBUG  # see generated SQL during tests
```

### application-it.yml (slower, full integration)

```yaml
# src/test/resources/application-it.yml
spring:
  jpa:
    show-sql: false
logging:
  level:
    org.springframework.kafka: INFO
    vn.vnpt: DEBUG
```

### Selective IT execution

```bash
# Run only ITs for a specific service
mvn -pl services/inventory verify -Dit.test=Inventory*IT

# Skip ITs during fast local dev
mvn -pl services/inventory test -DskipITs

# Run all ITs in the project
mvn verify -P integration-tests
```

---

## 7. Common pitfalls

### ❌ Don't use `@SpringBootTest` for unit tests

```java
// ❌ WRONG: Slow, full context load for a pure function test
@SpringBootTest
class PriceCalculatorTest {
    @Test
    void test() { ... }
}

// ✅ CORRECT: Plain JUnit for unit tests
class PriceCalculatorTest {
    @Test
    void test() { ... }
}
```

### ❌ Don't use `@Transactional` for IT cleanup

```java
// ❌ WRONG: Test rollback doesn't work for Testcontainers in many setups
@SpringBootTest
@Transactional
class MyIT { ... }

// ✅ CORRECT: Explicit @BeforeEach truncate
@SpringBootTest
class MyIT extends AbstractIT {
    @BeforeEach
    void cleanDb() { jdbc.execute("TRUNCATE ..."); }
}
```

### ❌ Don't share state between tests

```java
// ❌ WRONG: Static state leaks
class MyIT {
    static Product shared = createProduct();  // shared across all tests
}

// ✅ CORRECT: Each test seeds what it needs
class MyIT {
    @Test
    void test() { var p = createProduct(); ... }
}
```

### ❌ Don't rely on test execution order

```java
// ❌ WRONG: Test B depends on Test A's state
@Test void a() { repo.save(p); }
@Test void b() { assertThat(repo.findAll()).hasSize(1); }  // assumes A ran first

// ✅ CORRECT: Each test is self-contained
@Test void a() { repo.save(p); assertThat(...); }
@Test void b() { var p = createProduct(); repo.save(p); assertThat(...); }
```

### ❌ Don't use `@DirtiesContext` lightly

`@DirtiesContext` destroys + recreates the Spring context, taking 5-10 seconds. Use only when testing bean lifecycle (e.g., `@PostConstruct`).

### ❌ Don't use `Thread.sleep` for async verification

```java
// ❌ WRONG: Brittle
Thread.sleep(2000);
assertThat(outbox.count()).isEqualTo(0);

// ✅ CORRECT: Awaitility with timeout
await().atMost(5, SECONDS).untilAsserted(() -> {
    assertThat(outbox.count()).isEqualTo(0);
});
```

### ❌ Don't use H2 for tests of Postgres-specific features

H2 doesn't support `SELECT FOR UPDATE`, `JSONB`, partial indexes, etc. **Always use Testcontainers Postgres**.

---

## 8. CI configuration

```yaml
# .github/workflows/ci.yml (Story 0.4)
integration-tests:
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-java@v4
      with:
        java-version: '25'
    - name: Unit tests
      run: mvn test
    - name: Integration tests
      run: mvn verify -P integration-tests
      env:
        TESTCONTAINERS_RYUK_DISABLED: 'true'  # CI environments
```

---

## 9. Quick reference — common commands

```bash
# Run all tests in a service
mvn -pl services/inventory test

# Run only unit tests (skip ITs)
mvn -pl services/inventory test -DskipITs

# Run only ITs
mvn -pl services/inventory verify -Dit.test=*IT

# Run a single IT class
mvn -pl services/inventory verify -Dit.test=InventoryReservationIT

# Run a single test method
mvn -pl services/inventory verify -Dit.test=InventoryReservationIT#hundredConcurrentReservationsYieldsOneSuccess

# Run 100x for the critical-path test
for i in {1..100}; do
  mvn -pl services/inventory verify -Dit.test=InventoryReservationIT#hundredConcurrentReservationsYieldsOneSuccess -q
  [ $? -ne 0 ] && echo "FAIL on run $i" && break
done
```

---

## 10. Test coverage targets

| Test type | Coverage target | What it validates |
|---|---|---|
| Unit | 80%+ per class | Pure logic, edge cases |
| Integration (IT) | All happy paths + all DI/AT/R-XX root causes | Cross-component correctness |
| Saga e2e | All 10 saga state transitions | State machine integrity |
| Chaos | All 7 P0 risks | Mitigation effectiveness |
| Performance | All latency NFRs (catalog p99 < 100ms, etc.) | SLO compliance |

Coverage measured via `mvn jacoco:report` (Story 0.4 setup).
