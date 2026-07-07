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
import vn.vnpt.inventory.application.AllocateInventoryUseCase;
import vn.vnpt.inventory.domain.InventoryReservation;
import vn.vnpt.inventory.domain.ReservationStatus;
import vn.vnpt.inventory.domain.exception.ReservationNotFoundException;

/**
 * Slice test for {@link AllocateInventoryController} — Story 1.8 / FR-11 (ALLOCATED).
 */
@SpringBootTest(classes = InventoryApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Testcontainers
class AllocateInventoryControllerTest {

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
  @MockitoBean AllocateInventoryUseCase allocateInventoryUseCase;

  private MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc = MockMvcBuilders.webAppContextSetup(wac).build();
    new JdbcTemplate(dataSource)
        .execute("TRUNCATE TABLE inventory_reservation, inventory_ledger, warehouses RESTART IDENTITY");
  }

  @Test
  void post_returns201OnSuccess() throws Exception {
    InventoryReservation reservation = new InventoryReservation();
    reservation.setUuid(101L);
    reservation.setVariantId(100L);
    reservation.setWarehouseId(1001L);
    reservation.setQuantity(3L);
    reservation.setStatus(ReservationStatus.COMMITTED);
    reservation.setExpiresAt(Instant.parse("2030-01-01T00:00:00Z"));
    java.lang.reflect.Field ssField = InventoryReservation.class.getDeclaredField("sagaStepId");
    ssField.setAccessible(true);
    ssField.set(reservation, "alloc-step-201");
    reservation.setOrderUuid(99L);
    reservation.setCreatedAt(LocalDateTime.now());
    reservation.setTenantId("default");
    when(allocateInventoryUseCase.allocate(any())).thenReturn(reservation);

    String body =
        """
        {
          "reservationUuid": 101,
          "sagaStepId": "alloc-step-201"
        }
        """;
    mvc.perform(
            post("/api/inventory-allocations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.reservationUuid").value(101))
        .andExpect(jsonPath("$.status").value("COMMITTED"));
  }

  @Test
  void post_returns404OnReservationNotFound() throws Exception {
    doThrow(new ReservationNotFoundException(999L))
        .when(allocateInventoryUseCase)
        .allocate(any());

    String body =
        """
        {
          "reservationUuid": 999,
          "sagaStepId": "alloc-step-404"
        }
        """;
    mvc.perform(
            post("/api/inventory-allocations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("reservation_not_found"));
  }
}