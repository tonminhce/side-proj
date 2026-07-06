package vn.vnpt.admin.web;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Maps {@link AccessDeniedException} to a 403 with a stable JSON body
 * (Story 1.4 / AC #7 — RBAC test contract).
 *
 * <p>Mirrors the catalog's {@code GlobalExceptionHandler} for the {@code IllegalArgumentException}
 * 400 case, but lives in the BFF because RBAC is the BFF's responsibility.
 */
@ControllerAdvice
public class GlobalAccessDeniedHandler {

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<Map<String, String>> handleForbidden(AccessDeniedException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(
            Map.of(
                "error", "forbidden",
                "message", ex.getMessage() == null ? "" : ex.getMessage()));
  }
}