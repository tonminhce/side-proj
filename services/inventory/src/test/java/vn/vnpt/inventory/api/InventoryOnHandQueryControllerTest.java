package vn.vnpt.inventory.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.vnpt.inventory.InventoryApplication;
import vn.vnpt.inventory.domain.InventoryLedgerEntry;
import vn.vnpt.inventory.domain.InventoryReason;
import vn.vnpt.inventory.domain.Region;
import vn.vnpt.inventory.domain.Warehouse;
import vn.vnpt.inventory.infrastructure.repository.InventoryLedgerEntryRepository;
import vn.vnpt.inventory.infrastructure.repository.WarehouseRepository;
import vn.vnpt.util.common.SnowflakeIdGenerator;

/**
 * Story 1.7 / FR-10 — per-warehouse breakdown endpoint {@code
 * GET /api/inventory/variants/{variantId}/on-hand}. Seeds 3 warehouses with mixed stock and
 * asserts the response has 3 OnHandView rows with correct counts.
 */
@SpringBootTest(classes = InventoryApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Testcontainers
class InventoryOnHandQueryControllerTest {

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

  @Autowired WebApplicationContext wac;
  @Autowired WarehouseRepository warehouseRepository;
  @Autowired InventoryLedgerEntryRepository ledgerRepository;
  @Autowired DataSource dataSource;

  private MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc = MockMvcBuilders.webAppContextSetup(wac).build();
    new JdbcTemplate(dataSource)
        .execute("TRUNCATE TABLE inventory_reservation, inventory_ledger, warehouses RESTART IDENTITY");
  }

  @Test
  void getOnHand_returnsPerWarehouseBreakdown() throws Exception {
    Long wh1 = saveWarehouse("HCM-BREAKDOWN-1-" + System.nanoTime(), Region.SOUTH);
    Long wh2 = saveWarehouse("HCM-BREAKDOWN-2-" + System.nanoTime(), Region.SOUTH);
    Long wh3 = saveWarehouse("HN-BREAKDOWN-3-" + System.nanoTime(), Region.NORTH);

    seed(500L, wh1, 7L);
    seed(500L, wh2, 13L);
    seed(500L, wh3, 4L);

    mvc.perform(get("/api/inventory/variants/500/on-hand"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[?(@.warehouseId == " + wh1 + " && @.onHand == 7)]").exists())
        .andExpect(jsonPath("$[?(@.warehouseId == " + wh2 + " && @.onHand == 13)]").exists())
        .andExpect(jsonPath("$[?(@.warehouseId == " + wh3 + " && @.onHand == 4)]").exists());
  }

  private Long saveWarehouse(String code, Region region) {
    return warehouseRepository
        .save(Warehouse.builder().code(code).displayName(code).region(region).build())
        .getUuid();
  }

  private void seed(long variantId, long warehouseId, long qty) {
    ledgerRepository.save(
        InventoryLedgerEntry.builder()
            .variantId(variantId)
            .warehouseId(warehouseId)
            .delta(qty)
            .reason(InventoryReason.RECEIVE.toColumnValue())
            .eventId(SnowflakeIdGenerator.generateId())
            .tenantId("default")
            .build());
  }
}