package vn.vnpt.checkout.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.stripe.exception.InvalidRequestException;
import vn.vnpt.checkout.domain.Checkout;
import vn.vnpt.checkout.domain.CheckoutStatus;
import vn.vnpt.checkout.domain.exception.CheckoutNotFoundException;
import vn.vnpt.checkout.domain.exception.CheckoutVersionConflictException;
import vn.vnpt.checkout.domain.exception.StripePaymentIntentException;
import vn.vnpt.util.web.RestExceptionHandler;

/** Story 2.3 / FR-19, FR-21 — exception → HTTP mapping. Direct handler invocation (no MockMvc). */
class CheckoutControllerExceptionHandlerTest {

  private final CheckoutControllerExceptionHandler handler = new CheckoutControllerExceptionHandler();
  private final RestExceptionHandler commonHandler = new RestExceptionHandler();

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
        commonHandler.handleValidation(new IllegalArgumentException("cartUuid is required"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    Map<String, Object> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.get("code")).isEqualTo(400);
    assertThat(body.get("status")).isEqualTo("BAD_REQUEST");
    assertThat(body.get("message")).isEqualTo("cartUuid is required");
  }

  /**
   * Story 2.4 / FR-20 / AC #5 — {@link StripePaymentIntentException} must map to 502 with a
   * sanitized body that NEVER leaks the raw Stripe exception (architecture.md:532-535). The handler
   * swallows the cause; only the generic "Payment provider unavailable" message reaches the client.
   */
  @Test
  void handleStripePaymentIntentException_returns502_withSanitizedBody() {
    String rawStripeDetail = "stripe-secret-internal-detail-that-must-not-leak";
    StripePaymentIntentException ex =
        new StripePaymentIntentException(
            "Stripe PaymentIntent creation failed: " + rawStripeDetail,
            new InvalidRequestException(
                rawStripeDetail, "param_xxx", "code", "type", 400, new RuntimeException()));

    ResponseEntity<Map<String, Object>> response = handler.handleStripePaymentIntentException(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    Map<String, Object> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.get("code")).isEqualTo(502);
    assertThat(body.get("status")).isEqualTo("BAD_GATEWAY");
    assertThat(body.get("message")).isEqualTo("Payment provider unavailable");
    // The raw Stripe detail MUST NOT appear in the response body (R-15 / ADR-23 / NFR-OBS-5).
    assertThat(String.valueOf(body.get("message"))).doesNotContain(rawStripeDetail);
    assertThat(body).doesNotContainKey("details");
  }
}