package vn.vnpt.catalog.infrastructure.web;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Maps domain-level exceptions to HTTP responses (Story 1.4 / AC #3 — 400 on
 * {@link IllegalArgumentException}).
 *
 * <p>Default Spring error response shape is HTML / opaque — the admin frontend's
 * MockMvc + curl assertions need a stable JSON body {@code {"error":"...","message":"..."}}.
 * Story 8.1 may add explicit exception types per RFC 7807; v1 keeps the simple shape.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(Map.of("error", "bad_request", "message", ex.getMessage() == null ? "" : ex.getMessage()));
  }
}