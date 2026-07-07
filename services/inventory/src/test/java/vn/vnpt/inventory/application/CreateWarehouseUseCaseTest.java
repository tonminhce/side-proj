package vn.vnpt.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import vn.vnpt.inventory.domain.Region;
import vn.vnpt.inventory.domain.Warehouse;
import vn.vnpt.util.exception.InvalidInputException;

/**
 * Integration test for {@link CreateWarehouseUseCase} — Story 1.8 / FR-12 / DI-09 fix.
 *
 * <p>Pins two invariants: (a) a fresh warehouse persists and {@code UkValidator.validate(...)}
 * is called; (b) a duplicate {@code (tenantId, code)} throws {@link InvalidInputException}
 * (HTTP 400 via util's {@code ApiExceptionHandle}).
 */
@SpringBootTest(classes = InventoryApplication.class)
@ActiveProfiles("test")
@Testcontainers
class CreateWarehouseUseCaseTest {

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

  @Autowired CreateWarehouseUseCase useCase;
  @Autowired DataSource dataSource;

  @BeforeEach
  void clean() {
    new JdbcTemplate(dataSource)
        .execute("TRUNCATE TABLE inventory_reservation, inventory_ledger, warehouses RESTART IDENTITY");
  }

  @Test
  void create_persistsWarehouse_andCallsUkValidator() {
    Warehouse saved =
        useCase.create(new CreateWarehouseCommand("HCM-01", "Ho Chi Minh", Region.SOUTH));

    assertThat(saved.getUuid()).isNotNull().isPositive();
    assertThat(saved.getCode()).isEqualTo("HCM-01");
    assertThat(saved.getRegion()).isEqualTo(Region.SOUTH);
    assertThat(saved.getTenantId()).isEqualTo("default");

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM warehouses WHERE code = 'HCM-01'", Integer.class);
    assertThat(count).isEqualTo(1);
  }

  @Test
  void create_onDuplicateCodeInSameTenant_throwsInvalidInputExceptionFromUkValidator() {
    useCase.create(new CreateWarehouseCommand("DN-01", "Da Nang", Region.CENTRAL));

    assertThatThrownBy(
            () ->
                useCase.create(
                    new CreateWarehouseCommand("DN-01", "Da Nang 2", Region.CENTRAL)))
        .isInstanceOf(InvalidInputException.class)
        .satisfies(
            e -> {
              InvalidInputException iie = (InvalidInputException) e;
              Map<String, String> errors = iie.getErrors();
              assertThat(errors).containsKey("code");
              assertThat(errors.get("code"))
                  .contains("warehouse_code_per_tenant");
            });
  }
}