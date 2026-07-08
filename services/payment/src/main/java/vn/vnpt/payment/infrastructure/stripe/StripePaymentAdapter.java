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
import vn.vnpt.util.common.SnowflakeIdGenerator;

/**
 * Test-double {@link PaymentPort} — Story 3.1 / FR-25. Records every call keyed by {@code orderUuid}
 * so AC #3 can assert identical {@code idempotencyKey} across two invocations.
 *
 * <p>Replace with a real {@code com.stripe:stripe-java} adapter in Story 3.3 (Elements iframe). The
 * real adapter forwards {@code cmd.idempotencyKey()} as the Stripe {@code Idempotency-Key} header;
 * this test-double records it for assertions.
 *
 * <p>ponytail: a port is a dumb pass-through — input validation lives at the trust boundary
 * (the command's compact constructor), not here. {@link PaymentPortUnavailableException} is reserved
 * for transient failures (5xx, network timeout) the saga compensator retries.
 */
@Component
@Profile("test")
public class StripePaymentAdapter implements PaymentPort {

  private final Map<Long, List<CallRecord>> calls = new ConcurrentHashMap<>();

  @Override
  public PaymentResult authorize(AuthorizePaymentCommand cmd) {
    calls.computeIfAbsent(cmd.orderUuid(), k -> new java.util.ArrayList<>())
        .add(new CallRecord(
            cmd.orderUuid(),
            cmd.idempotencyKey(),
            cmd.amountCents(),
            cmd.currency(),
            cmd.stripeCustomerId()));
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
      String stripeCustomerId) {}
}