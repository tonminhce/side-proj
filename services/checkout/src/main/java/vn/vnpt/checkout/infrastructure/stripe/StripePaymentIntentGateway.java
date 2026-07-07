package vn.vnpt.checkout.infrastructure.stripe;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vn.vnpt.checkout.application.port.StripePaymentGateway;
import vn.vnpt.checkout.domain.exception.StripePaymentIntentException;

/**
 * Stripe PaymentIntent gateway adapter — Story 2.4 / FR-20.
 *
 * <p>Builds {@link PaymentIntentCreateParams} with {@code capture_method = MANUAL} (FR-20 two-step
 * confirm→capture flow; the saga captures after {@code payment_intent.succeeded} in Story 2.5) and
 * calls {@code PaymentIntent.create(params, RequestOptions.builder().setIdempotencyKey(...).build())}.
 *
 * <p>Idempotency key = {@code checkout.getUuid() + ":stripe.payment_intent.create"} per ADR-11
 * tuple {@code (aggregate_id, saga_step_name)}; retries reuse the same PaymentIntent.
 *
 * <p>Wraps {@link StripeException} → domain {@link StripePaymentIntentException} so the controller
 * never leaks the raw Stripe body (architecture.md:532-535). Never logs the API key or the
 * {@code client_secret} (R-15 / ADR-23 / NFR-OBS-5); only the {@code payment_intent_id} is logged.
 */
@Component
@Slf4j
public class StripePaymentIntentGateway implements StripePaymentGateway {

  private final String apiKey;

  public StripePaymentIntentGateway(@Value("${checkout.stripe.api-key}") String apiKey) {
    this.apiKey = apiKey;
  }

  @PostConstruct
  void configureStripeClient() {
    // Stripe SDK reads this static; set once at boot. The key is never logged.
    com.stripe.Stripe.apiKey = apiKey;
  }

  @Override
  public Result createPaymentIntent(long amountMinor, String currency, String idempotencyKey) {
    PaymentIntentCreateParams params =
        PaymentIntentCreateParams.builder()
            .setAmount(amountMinor)
            .setCurrency(currency)
            .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.MANUAL)
            .build();
    RequestOptions options =
        RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
    try {
      PaymentIntent intent = PaymentIntent.create(params, options);
      log.debug(
          "Stripe PaymentIntent created: paymentIntentId={} amountMinor={} currency={}",
          intent.getId(),
          amountMinor,
          currency);
      return new Result(intent.getId(), intent.getClientSecret());
    } catch (StripeException e) {
      // ponytail: never log the StripeException body — log only the payment_intent_id (when known)
      // and a sanitized reason. client_secret and apiKey are NEVER logged.
      throw new StripePaymentIntentException(
          "Stripe PaymentIntent creation failed: " + e.getCode(), e);
    }
  }
}