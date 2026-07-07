package vn.vnpt.payment.application.usecase;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.payment.application.port.AuthorizePaymentCommand;
import vn.vnpt.payment.application.port.PaymentPort;
import vn.vnpt.payment.application.port.PaymentResult;
import vn.vnpt.payment.infrastructure.IdempotencyKey;

/**
 * Authorize a payment against the outbound {@link PaymentPort} with a stable idempotency key — FR-25,
 * NFR-IDEM-2, ADR-11.
 *
 * <p>The key input is the Stripe API call name {@code "payment.authorize"}, NOT the saga FSM
 * transition name ({@code payment.intent.created}) — they are distinct concepts; this is the
 * disambiguation the Story 2.4 review caught as a CRITICAL footgun.
 */
@Service
@Transactional
public class AuthorizePaymentUseCase {

  private static final String STEP_NAME = "payment.authorize";

  private final PaymentPort paymentPort;

  public AuthorizePaymentUseCase(PaymentPort paymentPort) {
    this.paymentPort = paymentPort;
  }

  public PaymentResult execute(AuthorizePaymentCommand cmd) {
    String idempotencyKey = IdempotencyKey.forOrderStep(cmd.orderUuid(), STEP_NAME);
    return paymentPort.authorize(cmd.withIdempotencyKey(idempotencyKey));
  }
}