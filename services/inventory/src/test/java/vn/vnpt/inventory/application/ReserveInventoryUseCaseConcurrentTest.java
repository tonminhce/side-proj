package vn.vnpt.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.vnpt.inventory.InventoryApplication;
import vn.vnpt.inventory.domain.InventoryLedgerEntry;
import vn.vnpt.inventory.domain.InventoryReason;
import vn.vnpt.inventory.domain.Warehouse;
import vn.vnpt.inventory.domain.exception.InsufficientStockException;
import vn.vnpt.inventory.infrastructure.repository.InventoryLedgerEntryRepository;
import vn.vnpt.inventory.infrastructure.repository.WarehouseRepository;
import vn.vnpt.util.common.SnowflakeIdGenerator;

/**
 * Concurrent reservation regression guard — Story 1.6 / FR-9, DI-01 root-cause fix.
 *
 * <p>The FR-9 {@code SELECT … FOR UPDATE} pattern is verified here. Two threads reserve the
 * same {@code (variant, warehouse)} with {@code qty=1} when only {@code on_hand = 1} exists.
 * The assertion: exactly ONE succeeds; the OTHER throws {@link InsufficientStockException}.
 *
 * <p>If this test fails intermittently, investigate the {@code FOR UPDATE} locking semantics —
 * do NOT relax the assertion. The 100x {@link RepeatedTest} amplifies any race condition.
 *
 * <p>ponytail: a real Postgres session per transaction is required (not H2); Testcontainers
 * supplies the real engine.
 */
@SpringBootTest(classes = InventoryApplication.class)
@ActiveProfiles("test")
@Testcontainers
class ReserveInventoryUseCaseConcurrentTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("inventory_db")
          .withUsername("inventory_user")
          .withPassword("inventory_pass");

  @DynamicPropertySource
  static void registerPostgres(DynamicPropertyRegistry registry) {
    registry.add("TC_POSTGRES_URL", POSTGRES::getJdbcUrl);
    registry.add("TC_POSTGRES_USER", POSTGRES::getUsername);
    registry.add("TC_POSTGRES_PASSWORD", POSTGRES::getPassword);
  }

  @Autowired ReserveInventoryUseCase useCase;
  @Autowired WarehouseRepository warehouseRepository;
  @Autowired InventoryLedgerEntryRepository ledgerRepository;
  @Autowired DataSource dataSource;

  private Long warehouseId;

  @BeforeEach
  void seedOneStock() {
    new JdbcTemplate(dataSource)
        .execute(
            "TRUNCATE TABLE inventory_reservation, inventory_ledger, warehouses RESTART IDENTITY");
    warehouseId =
        warehouseRepository
            .save(
                Warehouse.builder()
                    .code("HCM-01-CONC-" + System.nanoTime())
                    .displayName("Ho Chi Minh Concurrent")
                    .build())
            .getUuid();

    // Seed exactly on_hand = 1.
    ledgerRepository.save(
        InventoryLedgerEntry.builder()
            .variantId(900L)
            .warehouseId(warehouseId)
            .delta(1L)
            .reason(InventoryReason.RECEIVE.toColumnValue())
            .eventId(SnowflakeIdGenerator.generateId())
            .tenantId("default")
            .build());
  }

  @RepeatedTest(value = 100, name = "concurrent reserve attempt {currentRepetition}/{totalRepetitions}")
  void reserve_concurrent_onlyOneSucceedsWhenStockIsOne() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch startGate = new CountDownLatch(1);
    AtomicInteger successCount = new AtomicInteger();
    AtomicInteger insufficientCount = new AtomicInteger();
    AtomicInteger otherErrorCount = new AtomicInteger();

    String stepA = "step-conc-A-" + System.nanoTime();
    String stepB = "step-conc-B-" + System.nanoTime();

    Future<?> a =
        pool.submit(
            () -> {
              try {
                startGate.await();
                useCase.reserve(
                    new ReserveInventoryCommand(
                        900L, warehouseId, 1L, stepA, null, Duration.ofMinutes(15)));
                successCount.incrementAndGet();
              } catch (InsufficientStockException expected) {
                insufficientCount.incrementAndGet();
              } catch (Exception e) {
                otherErrorCount.incrementAndGet();
              }
            });

    Future<?> b =
        pool.submit(
            () -> {
              try {
                startGate.await();
                useCase.reserve(
                    new ReserveInventoryCommand(
                        900L, warehouseId, 1L, stepB, null, Duration.ofMinutes(15)));
                successCount.incrementAndGet();
              } catch (InsufficientStockException expected) {
                insufficientCount.incrementAndGet();
              } catch (Exception e) {
                otherErrorCount.incrementAndGet();
              }
            });

    // Release both threads simultaneously.
    startGate.countDown();
    a.get(10, TimeUnit.SECONDS);
    b.get(10, TimeUnit.SECONDS);
    pool.shutdown();
    pool.awaitTermination(5, TimeUnit.SECONDS);

    // The FR-9 binding: exactly one of two concurrent reserves succeeds when stock=1.
    assertThat(successCount.get())
        .as("exactly 1 success when on_hand=1, 2 concurrent reserves")
        .isEqualTo(1);
    assertThat(insufficientCount.get())
        .as("exactly 1 InsufficientStockException (the loser)")
        .isEqualTo(1);
    assertThat(otherErrorCount.get())
        .as("no other errors allowed (concurrency-safe failure modes)")
        .isEqualTo(0);
  }
}