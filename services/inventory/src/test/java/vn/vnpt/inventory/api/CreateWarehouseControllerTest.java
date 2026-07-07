package vn.vnpt.inventory.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.Map;
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
import vn.vnpt.inventory.application.CreateWarehouseUseCase;
import vn.vnpt.inventory.domain.Region;
import vn.vnpt.inventory.domain.Warehouse;
import vn.vnpt.util.exception.InvalidInputException;

/**
 * Slice test for {@link CreateWarehouseController} — Story 1.8 / FR-12 / DI-09 fix.
 */
@SpringBootTest(classes = InventoryApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Testcontainers
class CreateWarehouseControllerTest {

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
  @MockitoBean CreateWarehouseUseCase createWarehouseUseCase;

  private MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc = MockMvcBuilders.webAppContextSetup(wac).build();
    new JdbcTemplate(dataSource)
        .execute("TRUNCATE TABLE inventory_reservation, inventory_ledger, warehouses RESTART IDENTITY");
  }

  @Test
  void post_returns201OnSuccess() throws Exception {
    Warehouse warehouse =
        Warehouse.builder()
            .code("DN-01")
            .displayName("Da Nang")
            .region(Region.CENTRAL)
            .build();
    warehouse.setUuid(11L);
    warehouse.setCreatedAt(LocalDateTime.now());
    when(createWarehouseUseCase.create(any())).thenReturn(warehouse);

    String body =
        """
        {
          "code": "DN-01",
          "displayName": "Da Nang",
          "region": "CENTRAL"
        }
        """;
    mvc.perform(
            post("/api/inventory-warehouses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.uuid").value(11))
        .andExpect(jsonPath("$.code").value("DN-01"))
        .andExpect(jsonPath("$.region").value("CENTRAL"));
  }

  @Test
  void post_returns400OnSoftUkViolation() throws Exception {
    doThrow(
            new InvalidInputException(
                Map.of(
                    "code",
                    "Giá trị đã tồn tại (vi phạm khóa duy nhất 'warehouse_code_per_tenant')")))
        .when(createWarehouseUseCase)
        .create(any());

    String body =
        """
        {
          "code": "DN-01",
          "displayName": "Da Nang",
          "region": "CENTRAL"
        }
        """;
    mvc.perform(
            post("/api/inventory-warehouses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void post_returns400OnMissingRegion() throws Exception {
    String body =
        """
        {
          "code": "DN-01",
          "displayName": "Da Nang"
        }
        """;
    mvc.perform(
            post("/api/inventory-warehouses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }
}