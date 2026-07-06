package vn.vnpt.inventory.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDateTime;
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
import vn.vnpt.inventory.application.ReserveInventoryUseCase;
import vn.vnpt.inventory.domain.InventoryReservation;
import vn.vnpt.inventory.domain.ReservationStatus;
import vn.vnpt.inventory.domain.exception.InsufficientStockException;
import vn.vnpt.inventory.domain.exception.WarehouseNotFoundException;

/**
 * Slice test for {@link InventoryReservationController} — Story 1.6 / FR-9 HTTP layer.
 *
 * <p>Spring Boot 4.0 removed {@code @WebMvcTest} and {@code @AutoConfigureMockMvc}. The
 * supported path is {@code @SpringBootTest(webEnvironment=MOCK)} with a manually-built
 * {@link MockMvc} via {@link MockMvcBuilders#webAppContextSetup}. {@code @MockBean} is
 * replaced by {@code @MockitoBean} (Spring 6.2+).
 */
@SpringBootTest(classes = InventoryApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Testcontainers
class InventoryReservationControllerTest {

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

  @MockitoBean ReserveInventoryUseCase reserveInventoryUseCase;
  @MockitoBean
  vn.vnpt.inventory.application.ReleaseInventoryUseCase releaseInventoryUseCase;

  private MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc = MockMvcBuilders.webAppContextSetup(wac).build();
    new JdbcTemplate(dataSource)
        .execute(
            "TRUNCATE TABLE inventory_reservation, inventory_ledger, warehouses RESTART IDENTITY");
  }

  @Test
  void post_returns201OnSuccess() throws Exception {
    InventoryReservation reservation = new InventoryReservation();
    reservation.setUuid(42L);
    reservation.setVariantId(100L);
    reservation.setWarehouseId(1L);
    reservation.setQuantity(3L);
    reservation.setStatus(ReservationStatus.ACTIVE);
    reservation.setExpiresAt(Instant.parse("2030-01-01T00:00:00Z"));
    // ADR-11: sagaStepId is immutable (no setter); reflectively set for this test fixture.
    java.lang.reflect.Field ssField = InventoryReservation.class.getDeclaredField("sagaStepId");
    ssField.setAccessible(true);
    ssField.set(reservation, "step-201");
    reservation.setOrderUuid(99L);
    reservation.setCreatedAt(LocalDateTime.now());
    reservation.setTenantId("default");
    when(reserveInventoryUseCase.reserve(any())).thenReturn(reservation);

    String body =
        """
        {
          "variantId": 100,
          "warehouseId": 1,
          "quantity": 3,
          "sagaStepId": "step-201",
          "orderUuid": 99,
          "ttlMinutes": 15
        }
        """;
    mvc.perform(
            post("/api/inventory-reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.reservationUuid").value(42))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.sagaStepId").value("step-201"));
  }

  @Test
  void post_returns409OnInsufficientStock() throws Exception {
    doThrow(new InsufficientStockException(100L, 1L, 5L, 2L))
        .when(reserveInventoryUseCase)
        .reserve(any());

    String body =
        """
        {
          "variantId": 100,
          "warehouseId": 1,
          "quantity": 5,
          "sagaStepId": "step-409"
        }
        """;
    mvc.perform(
            post("/api/inventory-reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("insufficient_stock"))
        .andExpect(jsonPath("$.requested").value(5))
        .andExpect(jsonPath("$.available").value(2));
  }

  @Test
  void post_returns404OnWarehouseNotFound() throws Exception {
    doThrow(new WarehouseNotFoundException(999L))
        .when(reserveInventoryUseCase)
        .reserve(any());

    String body =
        """
        {
          "variantId": 100,
          "warehouseId": 999,
          "quantity": 1,
          "sagaStepId": "step-404"
        }
        """;
    mvc.perform(
            post("/api/inventory-reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("warehouse_not_found"));
  }

  @Test
  void post_returns400OnValidation() throws Exception {
    doThrow(new IllegalArgumentException("quantity must be > 0"))
        .when(reserveInventoryUseCase)
        .reserve(any());

    String body =
        """
        {
          "variantId": 100,
          "warehouseId": 1,
          "quantity": 0,
          "sagaStepId": "step-400"
        }
        """;
    mvc.perform(
            post("/api/inventory-reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("validation_error"));
  }
}