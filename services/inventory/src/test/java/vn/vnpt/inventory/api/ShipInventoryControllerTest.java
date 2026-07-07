package vn.vnpt.inventory.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.vnpt.inventory.InventoryApplication;
import vn.vnpt.inventory.application.ShipInventoryUseCase;
import vn.vnpt.inventory.domain.InventoryLedgerEntry;
import vn.vnpt.inventory.domain.exception.InsufficientStockException;

/**
 * Slice test for {@link ShipInventoryController} — Story 1.8 / FR-11 (SHIPPED).
 */
@SpringBootTest(classes = InventoryApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Testcontainers
class ShipInventoryControllerTest {

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
  @Autowired DataSource dataSource;
  @MockitoBean ShipInventoryUseCase shipInventoryUseCase;

  private MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc = MockMvcBuilders.webAppContextSetup(wac).build();
    new JdbcTemplate(dataSource)
        .execute("TRUNCATE TABLE inventory_reservation, inventory_ledger, warehouses RESTART IDENTITY");
  }

  @Test
  void post_returns201OnSuccess() throws Exception {
    InventoryLedgerEntry entry =
        InventoryLedgerEntry.builder()
            .variantId(100L)
            .warehouseId(1001L)
            .delta(-3L)
            .reason("ship")
            .eventId(12345L)
            .build();
    entry.setUuid(42L);
    when(shipInventoryUseCase.ship(any())).thenReturn(entry);

    String body =
        """
        {
          "variantId": 100,
          "warehouseId": 1001,
          "quantity": 3,
          "sagaStepId": "ship-step-201"
        }
        """;
    mvc.perform(
            post("/api/inventory-shipments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.ledgerEntryUuid").value(42))
        .andExpect(jsonPath("$.delta").value(-3))
        .andExpect(jsonPath("$.reason").value("ship"));
  }

  @Test
  void post_returns409OnInsufficientStock() throws Exception {
    doThrow(new InsufficientStockException(100L, 1001L, 5L, 2L))
        .when(shipInventoryUseCase)
        .ship(any());

    String body =
        """
        {
          "variantId": 100,
          "warehouseId": 1001,
          "quantity": 5,
          "sagaStepId": "ship-step-409"
        }
        """;
    mvc.perform(
            post("/api/inventory-shipments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("insufficient_stock"));
  }
}