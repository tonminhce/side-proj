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
import vn.vnpt.inventory.domain.exception.WarehouseNotFoundException;

/**
 * Maps reservation-domain exceptions to HTTP status codes — Story 1.6 / FR-9.
 *
 * <ul>
 *   <li>{@link InsufficientStockException} → 409 Conflict (the FR-9 binding — exactly one of N
 *       concurrent reserves returns 409).
 *   <li>{@link WarehouseNotFoundException} → 404 Not Found.
 *   <li>{@link IllegalArgumentException} (validation) → 400 Bad Request.
 *   <li>{@link DataIntegrityViolationException} (e.g., duplicate {@code saga_step_id} from a
 *       saga retry race) → 409 Conflict.
 * </ul>
 *
 * <p>No generic {@code Exception → 500} catch — Boot 4's default handler returns 500. Future
 * Story 10.x adds structured error envelopes (correlation IDs, OTel trace IDs); not this story.
 */
@RestControllerAdvice
@Slf4j
public class ReservationControllerExceptionHandler {

  /**
   * Postgres surfaces the unique-constraint violation with the conflicting value in the message
   * (e.g. "duplicate key value violates unique constraint \"uq_inventory_reservation_saga_step\"
   * ... Key (saga_step_id)=(step-xyz) already exists"). Capture it for the saga's diagnostic
   * logging — the retry sidecar wants to know which step already won.
   */
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

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, Object>> handleValidation(IllegalArgumentException e) {
    log.debug("400 validation: {}", e.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(Map.of("error", "validation_error", "message", e.getMessage()));
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