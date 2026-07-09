package vn.vnpt.order.infrastructure.web;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import vn.vnpt.order.domain.exception.OrderEditWindowClosedException;
import vn.vnpt.order.domain.exception.OrderSnapshotMissingException;
import vn.vnpt.order.domain.exception.OrderTerminalStateException;
import vn.vnpt.order.domain.exception.OrderVersionMismatchException;

/** Maps edit-after-pay exceptions to HTTP responses (Story 4.4 / FR-34). */
@ControllerAdvice
public class OrderEditExceptionHandler {

  @ExceptionHandler(OrderEditWindowClosedException.class)
  public ResponseEntity<Map<String, Object>> handleWindowClosed(OrderEditWindowClosedException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
        "error", "edit_window_closed",
        "message", e.getMessage()));
  }

  @ExceptionHandler(OrderVersionMismatchException.class)
  public ResponseEntity<Map<String, Object>> handleVersionMismatch(OrderVersionMismatchException e) {
    return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).body(Map.of(
        "error", "version_mismatch",
        "message", e.getMessage()));
  }

  @ExceptionHandler(OrderTerminalStateException.class)
  public ResponseEntity<Map<String, Object>> handleTerminal(OrderTerminalStateException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
        "error", "terminal_state",
        "message", e.getMessage()));
  }

  /** Story 5.10 / Move A — accrue-loyalty endpoint + saga listener both throw this when
   *  an orderUuid has no price snapshot. Targeted handler → 404 (not the broad
   *  IllegalArgumentException catch that Move B added — that handler was removed when the
   *  OrderController.accrueLoyalty site switched to the dedicated type). */
  @ExceptionHandler(OrderSnapshotMissingException.class)
  public ResponseEntity<Map<String, Object>> handleSnapshotMissing(OrderSnapshotMissingException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
        "error", "unknown_order",
        "message", e.getMessage()));
  }
}