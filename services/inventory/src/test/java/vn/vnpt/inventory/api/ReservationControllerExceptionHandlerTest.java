package vn.vnpt.inventory.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import vn.vnpt.inventory.domain.exception.InsufficientStockException;
import vn.vnpt.inventory.domain.exception.ReservationNotFoundException;
import vn.vnpt.inventory.domain.exception.WarehouseNotFoundException;

/**
 * Direct unit test for {@link ReservationControllerExceptionHandler} — Story 1.6 / AC #22.
 *
 * <p>Pins the 4-status mapping without spinning up a Spring context or MockMvc. The controller
 * test ({@link InventoryReservationControllerTest}) covers the integration; this covers the
 * mapping itself so a regression in the handler is caught even if the controller wiring changes.
 */
class ReservationControllerExceptionHandlerTest {

  private final ReservationControllerExceptionHandler handler =
      new ReservationControllerExceptionHandler();

  @Test
  void insufficientStock_mapsTo409WithDiagnosticFields() {
    ResponseEntity<Map<String, Object>> response =
        handler.handleInsufficientStock(
            new InsufficientStockException(42L, 7L, 10L, 3L));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    Map<String, Object> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.get("error")).isEqualTo("insufficient_stock");
    assertThat(body.get("variantId")).isEqualTo(42L);
    assertThat(body.get("warehouseId")).isEqualTo(7L);
    assertThat(body.get("requested")).isEqualTo(10L);
    assertThat(body.get("available")).isEqualTo(3L);
  }

  @Test
  void warehouseNotFound_mapsTo404() {
    ResponseEntity<Map<String, Object>> response =
        handler.handleWarehouseNotFound(new WarehouseNotFoundException(99L));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    Map<String, Object> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.get("error")).isEqualTo("warehouse_not_found");
  }

  @Test
  void illegalArgument_mapsTo400() {
    ResponseEntity<Map<String, Object>> response =
        handler.handleValidation(new IllegalArgumentException("bad input"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    Map<String, Object> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.get("error")).isEqualTo("validation_error");
    assertThat(body.get("message")).isEqualTo("bad input");
  }

  @Test
  void dataIntegrityViolation_mapsTo409IdempotencyConflict() {
    // ADR-11: saga retries with the same saga_step_id may hit
    // uq_inventory_reservation_saga_step. The handler maps to 409 idempotency_conflict so the
    // saga treats it as a no-op (the first call already returned the reservation).
    ResponseEntity<Map<String, Object>> response =
        handler.handleDataIntegrity(
            new DataIntegrityViolationException("duplicate saga_step_id"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    Map<String, Object> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.get("error")).isEqualTo("idempotency_conflict");
  }

  /**
   * QA-pass gap — Story 1.8 added {@link ReservationNotFoundException} for the ALLOCATED
   * path. The handler maps it to 404 with {@code error=reservation_not_found}. Direct unit
   * test pins the mapping so a regression in the handler (or the exception class) is caught
   * even if the controller wiring changes.
   */
  @Test
  void reservationNotFound_mapsTo404() {
    ResponseEntity<Map<String, Object>> response =
        handler.handleReservationNotFound(new ReservationNotFoundException(999_999L));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    Map<String, Object> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.get("error")).isEqualTo("reservation_not_found");
    assertThat((String) body.get("message")).contains("999999");
  }
}