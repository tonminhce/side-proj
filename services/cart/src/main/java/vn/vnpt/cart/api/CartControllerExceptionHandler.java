package vn.vnpt.cart.api;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.vnpt.cart.domain.Cart;
import vn.vnpt.cart.domain.exception.AnonymousCartOwnershipConflictException;
import vn.vnpt.cart.domain.exception.CartLineNotFoundException;
import vn.vnpt.cart.domain.exception.CartNotFoundException;
import vn.vnpt.cart.domain.exception.CartVersionConflictException;

/** Cart-domain exceptions → HTTP. Story 2.1. */
@RestControllerAdvice
@Slf4j
public class CartControllerExceptionHandler {

  @ExceptionHandler({CartNotFoundException.class, CartLineNotFoundException.class})
  public ResponseEntity<Map<String, Object>> handleNotFound(RuntimeException e) {
    log.debug("404 not_found: {}", e.getMessage());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(Map.of("code", 404, "status", "NOT_FOUND", "message", e.getMessage()));
  }

  @ExceptionHandler(CartVersionConflictException.class)
  public ResponseEntity<Map<String, Object>> handleVersionConflict(CartVersionConflictException e) {
    log.debug("409 cart version conflict: {}", e.getMessage());
    Map<String, Object> details = new HashMap<>();
    details.put("expectedVersion", e.getExpectedVersion());
    details.put("actualVersion", e.getActualVersion());
    Cart latest = e.getLatestCart();
    if (latest != null) {
      details.put("cart", CartResponse.from(latest, List.of()));
    }
    Map<String, Object> body = new HashMap<>();
    body.put("code", 409);
    body.put("status", "CONFLICT");
    body.put("message", "Cart version conflict");
    body.put("details", details);
    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
  }

  @ExceptionHandler(AnonymousCartOwnershipConflictException.class)
  public ResponseEntity<Map<String, Object>> handleOwnershipConflict(
      AnonymousCartOwnershipConflictException e) {
    log.debug("409 ownership conflict: {}", e.getMessage());
    Map<String, Object> body = new HashMap<>();
    body.put("code", 409);
    body.put("status", "CONFLICT");
    body.put("message", "Anonymous cart belongs to a different user");
    body.put("details", Map.of("ownerUserId", e.getOwnerUserId()));
    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, Object>> handleValidation(IllegalArgumentException e) {
    log.debug("400 validation: {}", e.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(Map.of("code", 400, "status", "BAD_REQUEST", "message", e.getMessage()));
  }
}
