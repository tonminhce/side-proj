package vn.vnpt.checkout.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import vn.vnpt.checkout.domain.Checkout;
import vn.vnpt.checkout.domain.CheckoutStatus;
import vn.vnpt.checkout.domain.exception.CheckoutNotFoundException;
import vn.vnpt.checkout.domain.exception.CheckoutVersionConflictException;

/** Story 2.3 / FR-19, FR-21 — exception → HTTP mapping. Direct handler invocation (no MockMvc). */
class CheckoutControllerExceptionHandlerTest {

  private final CheckoutControllerExceptionHandler handler = new CheckoutControllerExceptionHandler();

  @Test
  void handleCheckoutNotFoundException_returns404() {
    ResponseEntity<Map<String, Object>> response =
        handler.handleNotFound(new CheckoutNotFoundException(12345L));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    Map<String, Object> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.get("code")).isEqualTo(404);
    assertThat(body.get("status")).isEqualTo("NOT_FOUND");
    assertThat(body.get("message")).isEqualTo("Checkout not found");
    assertThat(body.get("details")).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> details = (Map<String, Object>) body.get("details");
    assertThat(details.get("checkoutUuid")).isEqualTo(12345L);
  }

  @Test
  void handleCheckoutVersionConflictException_returns409_withLatestState() {
    Checkout latest =
        Checkout.builder()
            .tenantId("default")
            .cartUuid(99L)
            .userId("u-1")
            .status(CheckoutStatus.PAYMENT_PENDING)
            .version(5L)
            .build();
    latest.setUuid(12345L);

    ResponseEntity<Map<String, Object>> response =
        handler.handleVersionConflict(
            new CheckoutVersionConflictException(0L, 5L, latest));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    Map<String, Object> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.get("code")).isEqualTo(409);
    assertThat(body.get("status")).isEqualTo("CONFLICT");
    assertThat(body.get("message")).isEqualTo("Checkout version conflict");
    @SuppressWarnings("unchecked")
    Map<String, Object> details = (Map<String, Object>) body.get("details");
    assertThat(details.get("expectedVersion")).isEqualTo(0L);
    assertThat(details.get("actualVersion")).isEqualTo(5L);
    // The latest Checkout is stored as CheckoutResponse, not a Map — verify the typed object.
    Object checkoutView = details.get("checkout");
    assertThat(checkoutView).isInstanceOf(CheckoutResponse.class);
    CheckoutResponse cr = (CheckoutResponse) checkoutView;
    assertThat(cr.checkoutId()).isEqualTo(12345L);
    assertThat(cr.version()).isEqualTo(5L);
  }

  /**
   * QA-pass pin — {@code IllegalArgumentException} thrown by use-case validation (cartUuid null /
   * userId missing / shippingAddress null) maps to 400 with the message echoed in the body. The
   * controller test covers the integration; this pins the handler-level mapping so a regression in
   * the handler's branch ordering is caught even if the controller wiring changes.
   */
  @Test
  void handleIllegalArgument_returns400_withMessage() {
    ResponseEntity<Map<String, Object>> response =
        handler.handleValidation(new IllegalArgumentException("cartUuid is required"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    Map<String, Object> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.get("code")).isEqualTo(400);
    assertThat(body.get("status")).isEqualTo("BAD_REQUEST");
    assertThat(body.get("message")).isEqualTo("cartUuid is required");
  }
}