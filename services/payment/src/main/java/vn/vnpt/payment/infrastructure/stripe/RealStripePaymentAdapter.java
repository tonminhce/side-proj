package vn.vnpt.payment.infrastructure.stripe;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import vn.vnpt.payment.application.port.AuthorizePaymentCommand;
import vn.vnpt.payment.application.port.PaymentPort;
import vn.vnpt.payment.application.port.PaymentPortUnavailableException;
import vn.vnpt.payment.application.port.PaymentResult;

/**
 * Real {@link PaymentPort} backed by {@code com.stripe:stripe-java} SDK — Story 3.3 / FR-24, FR-29,
 * ADR-23, R-12, R-15. Active in non-test profiles (dev, prod). The test profile keeps the recording
 * test-double (Story 3.1's {@link StripePaymentAdapter}) so unit tests assert the idempotency-key
 * contract.
 *
 * <p>The adapter forwards {@code cmd.idempotencyKey()} unchanged as Stripe's
 * {@code Idempotency-Key} HTTP header — the use case owns the key (ADR-11).
 *
 * <p>API version is pinned per-request via {@link RequestOptions#unsafeSetStripeVersionOverride}
 * (stripe-java 28.x removed the {@code Stripe.apiVersion} static setter; only the SDK constant
 * {@link Stripe#API_VERSION} and per-request override remain).
 */
@Service
@Profile("!test")
public class RealStripePaymentAdapter implements PaymentPort {

  private static final Logger log = LoggerFactory.getLogger(RealStripePaymentAdapter.class);

  private final StripeProperties props;
  private String apiVersion;

  public RealStripePaymentAdapter(StripeProperties props) {
    this.props = props;
  }

  /**
   * Apply the API key to the stripe-java SDK on first use rather than in the constructor —
   * keeps the constructor side-effect-free per F17 and avoids mutating the static field if
   * Spring reinstantiates (devtools reload). Still a process-global static mutation; the
   * follow-up Story 3.5+ refactor moves to {@code StripeClient} (per-instance key) to make
   * multi-tenant / cross-service key isolation safe.
   */
  @PostConstruct
  void init() {
    Stripe.apiKey = props.apiKey();
    this.apiVersion = props.apiVersion() == null || props.apiVersion().isBlank()
        ? Stripe.API_VERSION
        : props.apiVersion();
    log.info("RealStripePaymentAdapter initialized (apiVersion={})", this.apiVersion);
  }

  @Override
  public PaymentResult authorize(AuthorizePaymentCommand cmd) {
    PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
        .setAmount(cmd.amountCents())
        .setCurrency(cmd.currency().toLowerCase())
        .setCustomer(cmd.stripeCustomerId())
        .setConfirm(true)
        .build();

    // stripe-java 28.x: pin API version per-request via the unsafe static helper on the inner
    // RequestOptionsBuilder class (the only path; no public setter exists for stripeVersionOverride).
    RequestOptions pinned = RequestOptions.RequestOptionsBuilder.unsafeSetStripeVersionOverride(
        RequestOptions.builder().setIdempotencyKey(cmd.idempotencyKey()),
        apiVersion).build();

    try {
      PaymentIntent intent = PaymentIntent.create(params, pinned);
      return mapResult(intent);
    } catch (StripeException e) {
      int status = e.getStatusCode() != null ? e.getStatusCode() : 0;
      if (status >= 500) {
        throw new PaymentPortUnavailableException(
            "Stripe 5xx authorizing order=" + cmd.orderUuid() + ": " + e.getMessage(), e);
      }
      // 4xx is a client-side / non-retryable failure; let it bubble as IllegalArgumentException
      // so the saga compensator treats it as terminal (don't retry a non-retryable error).
      throw new IllegalArgumentException(
          "Stripe 4xx authorizing order=" + cmd.orderUuid() + ": " + e.getMessage(), e);
    }
  }

  private PaymentResult mapResult(PaymentIntent intent) {
    String status = intent.getStatus();
    return switch (status) {
      case "succeeded" -> new PaymentResult(
          Long.parseLong(intent.getId().replaceAll("\\D", "")), PaymentResult.Status.SUCCEEDED);
      case "requires_action" -> new PaymentResult(
          Long.parseLong(intent.getId().replaceAll("\\D", "")), PaymentResult.Status.REQUIRES_ACTION);
      default -> new PaymentResult(
          Long.parseLong(intent.getId().replaceAll("\\D", "")), PaymentResult.Status.REQUIRES_CONFIRMATION);
    };
  }
}