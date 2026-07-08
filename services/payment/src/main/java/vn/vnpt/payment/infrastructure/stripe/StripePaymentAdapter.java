package vn.vnpt.payment.infrastructure.stripe;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import vn.vnpt.payment.application.port.AuthorizePaymentCommand;
import vn.vnpt.payment.application.port.PaymentPort;
import vn.vnpt.payment.application.port.PaymentPortUnavailableException;
import vn.vnpt.payment.application.port.PaymentResult;
import vn.vnpt.payment.application.port.ThreeDSecureDecision;
import vn.vnpt.util.common.SnowflakeIdGenerator;

/**
 * Test-double {@link PaymentPort} — Story 3.1 / FR-25; Story 3.5 follow-up / FR-27.
 * Records every call keyed by {@code orderUuid} so AC #3 can assert identical {@code idempotencyKey}
 * across two invocations.
 *
 * <p>Replace with a real {@code com.stripe:stripe-java} adapter in Story 3.3 (Elements iframe). The
 * real adapter forwards {@code cmd.idempotencyKey()} as the Stripe {@code Idempotency-Key} header;
 * this test-double records it for assertions.
 *
 * <p>Story 3.5 follow-up: the test-double now evaluates {@link ThreeDSecureDecision} and, if true,
 * returns {@link PaymentResult.Status#REQUIRES_ACTION} with a fake challenge URL so the saga
 * branch logic can be exercised end-to-end without a real Stripe call.
 *
 * <p>ponytail: a port is a dumb pass-through — input validation lives at the trust boundary
 * (the command's compact constructor), not here. {@link PaymentPortUnavailableException} is reserved
 * for transient failures (5xx, network timeout) the saga compensator retries.
 */
@Component
@Profile("test")
public class StripePaymentAdapter implements PaymentPort {

  /** Fixed stub URL for the 3DS challenge (test-double — never used in prod). */
  static final String STUB_3DS_URL = "https://stripe.test/3ds/challenge-stub";

  private final Map<Long, List<CallRecord>> calls = new ConcurrentHashMap<>();

  @Override
  public PaymentResult authorize(AuthorizePaymentCommand cmd) {
    calls.computeIfAbsent(cmd.orderUuid(), k -> new java.util.ArrayList<>())
        .add(new CallRecord(
            cmd.orderUuid(),
            cmd.idempotencyKey(),
            cmd.amountCents(),
            cmd.currency(),
            cmd.stripeCustomerId(),
            cmd.country(),
            cmd.riskLevel()));

    if (ThreeDSecureDecision.shouldRequire(cmd.country(), cmd.amountCents(), cmd.riskLevel())) {
      return new PaymentResult(SnowflakeIdGenerator.generateId(),
          PaymentResult.Status.REQUIRES_ACTION, STUB_3DS_URL);
    }
    return new PaymentResult(SnowflakeIdGenerator.generateId(), PaymentResult.Status.SUCCEEDED);
  }

  /** Test accessor — recorded calls per {@code orderUuid}, in insertion order. */
  public List<CallRecord> callsFor(long orderUuid) {
    return calls.getOrDefault(orderUuid, List.of());
  }

  public record CallRecord(
      long orderUuid,
      String idempotencyKey,
      long amountCents,
      String currency,
      String stripeCustomerId,
      String country,
      vn.vnpt.payment.application.port.RiskLevel riskLevel) {}
}