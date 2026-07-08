package vn.vnpt.order.infrastructure.web;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import vn.vnpt.order.domain.exception.OrderEditWindowClosedException;
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
}