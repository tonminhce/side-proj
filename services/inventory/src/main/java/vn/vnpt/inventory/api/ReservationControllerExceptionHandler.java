package vn.vnpt.inventory.api;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.vnpt.inventory.domain.exception.InsufficientStockException;
import vn.vnpt.inventory.domain.exception.ReservationNotFoundException;
import vn.vnpt.inventory.domain.exception.WarehouseNotFoundException;

/** Reservation-domain exceptions → HTTP. Story 1.6. */
@RestControllerAdvice
@Slf4j
public class ReservationControllerExceptionHandler {

  /** Postgres surfaces the duplicate saga_step_id in the message; capture for saga retry diagnostics. */
  private static final Pattern SAGA_STEP_PATTERN =
      Pattern.compile("\\(saga_step_id\\)=\\(([^)]+)\\)");

  private static String extractSagaStepId(DataIntegrityViolationException e) {
    Throwable root = e.getMostSpecificCause();
    String message = root != null ? root.getMessage() : e.getMessage();
    if (message == null) {
      return null;
    }
    Matcher m = SAGA_STEP_PATTERN.matcher(message);
    return m.find() ? m.group(1) : null;
  }

  @ExceptionHandler(InsufficientStockException.class)
  public ResponseEntity<Map<String, Object>> handleInsufficientStock(InsufficientStockException e) {
    log.debug("409 insufficient_stock: {}", e.getMessage());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            Map.of(
                "error", "insufficient_stock",
                "variantId", e.getVariantId(),
                "warehouseId", e.getWarehouseId(),
                "requested", e.getRequested(),
                "available", e.getAvailable()));
  }

  @ExceptionHandler(WarehouseNotFoundException.class)
  public ResponseEntity<Map<String, Object>> handleWarehouseNotFound(WarehouseNotFoundException e) {
    log.debug("404 warehouse_not_found: {}", e.getMessage());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(Map.of("error", "warehouse_not_found", "message", e.getMessage()));
  }

  /** Story 1.8 / FR-11 — allocation on an unknown reservationUuid → 404. */
  @ExceptionHandler(ReservationNotFoundException.class)
  public ResponseEntity<Map<String, Object>> handleReservationNotFound(
      ReservationNotFoundException e) {
    log.debug("404 reservation_not_found: {}", e.getMessage());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(Map.of("error", "reservation_not_found", "message", e.getMessage()));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<Map<String, Object>> handleDataIntegrity(
      DataIntegrityViolationException e) {
    // ADR-11: saga retries with the same saga_step_id may hit uq_inventory_reservation_saga_step.
    // The duplicate-key error is benign; the saga already received the first reservation in its
    // prior call. Map to 409 with idempotency_conflict so the saga can treat it as a no-op.
    log.debug("409 idempotency_conflict: {}", e.getMostSpecificCause().getMessage());
    String sagaStepId = extractSagaStepId(e);
    Map<String, Object> body =
        sagaStepId != null
            ? Map.of("error", "idempotency_conflict", "sagaStepId", sagaStepId)
            : Map.of("error", "idempotency_conflict", "message", "duplicate saga_step_id");
    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
  }
}