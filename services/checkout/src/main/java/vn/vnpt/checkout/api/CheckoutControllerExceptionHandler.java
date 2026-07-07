package vn.vnpt.checkout.api;

import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.vnpt.checkout.domain.exception.CheckoutNotFoundException;
import vn.vnpt.checkout.domain.exception.CheckoutVersionConflictException;
import vn.vnpt.checkout.domain.exception.StripePaymentIntentException;

/** Checkout-domain exceptions → HTTP. Story 2.3 / FR-19, FR-21. */
@RestControllerAdvice
@Slf4j
public class CheckoutControllerExceptionHandler {

  @ExceptionHandler(CheckoutNotFoundException.class)
  public ResponseEntity<Map<String, Object>> handleNotFound(CheckoutNotFoundException e) {
    log.debug("404 not_found: {}", e.getMessage());
    Map<String, Object> body = new HashMap<>();
    body.put("code", 404);
    body.put("status", "NOT_FOUND");
    body.put("message", "Checkout not found");
    Map<String, Object> details = new HashMap<>();
    details.put("checkoutUuid", e.getCheckoutUuid());
    body.put("details", details);
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
  }

  @ExceptionHandler(CheckoutVersionConflictException.class)
  public ResponseEntity<Map<String, Object>> handleVersionConflict(
      CheckoutVersionConflictException e) {
    log.debug("409 checkout version conflict: {}", e.getMessage());
    Map<String, Object> details = new HashMap<>();
    details.put("expectedVersion", e.getExpectedVersion());
    details.put("actualVersion", e.getActualVersion());
    if (e.getLatestCheckout() != null) {
      details.put("checkout", CheckoutMapper.toResponse(e.getLatestCheckout()));
    }
    Map<String, Object> body = new HashMap<>();
    body.put("code", 409);
    body.put("status", "CONFLICT");
    body.put("message", "Checkout version conflict");
    body.put("details", details);
    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
  }

  /** Story 2.4 / FR-20 — never leak the raw Stripe body (architecture.md:532-535). */
  @ExceptionHandler(StripePaymentIntentException.class)
  public ResponseEntity<Map<String, Object>> handleStripePaymentIntentException(
      StripePaymentIntentException e) {
    log.debug("502/503 stripe payment intent failure");
    Map<String, Object> body = new HashMap<>();
    body.put("code", 502);
    body.put("status", "BAD_GATEWAY");
    body.put("message", "Payment provider unavailable");
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
  }
}