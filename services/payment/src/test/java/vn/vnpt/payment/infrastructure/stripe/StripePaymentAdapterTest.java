package vn.vnpt.payment.infrastructure.stripe;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import vn.vnpt.payment.application.port.AuthorizePaymentCommand;
import vn.vnpt.payment.application.port.PaymentResult;
import vn.vnpt.payment.application.port.RiskLevel;
import vn.vnpt.payment.infrastructure.IdempotencyKey;

/**
 * Contract test for the test-double {@link StripePaymentAdapter} — Story 3.1 / AC #3, #7, #9;
 * Story 3.5 follow-up / FR-27 (3DS decision).
 *
 * <p>Asserts the test-double records every input verbatim so the use-case test can compare
 * idempotency keys across calls. Input validation (negative amount, ISO-4217 currency) lives at
 * the trust boundary in {@link AuthorizePaymentCommand}'s compact constructor; the port itself
 * stays a dumb pass-through.
 *
 * <p>Story 3.5 follow-up: when the {@link vn.vnpt.payment.application.port.ThreeDSecureDecision}
 * triggers (EEA + elevated risk + above floor), the test-double returns
 * {@link PaymentResult.Status#REQUIRES_ACTION} with the stub 3DS URL so the saga branch can
 * be exercised end-to-end without a real Stripe call.
 */
class StripePaymentAdapterTest {

  @Test
  void authorize_recordsInputVerbatim() {
    StripePaymentAdapter adapter = new StripePaymentAdapter();
    String key = IdempotencyKey.forOrderStep(42L, "payment.authorize");
    AuthorizePaymentCommand cmd = new AuthorizePaymentCommand(
        42L, 1999L, "VND", "cus_test_1", key);

    PaymentResult result = adapter.authorize(cmd);

    assertThat(result.status()).isEqualTo(PaymentResult.Status.SUCCEEDED);
    assertThat(result.paymentIntentId()).isPositive();
    assertThat(result.requiresActionUrl()).isNull();

    List<StripePaymentAdapter.CallRecord> calls = adapter.callsFor(42L);
    assertThat(calls).hasSize(1);
    StripePaymentAdapter.CallRecord recorded = calls.get(0);
    assertThat(recorded.orderUuid()).isEqualTo(42L);
    assertThat(recorded.idempotencyKey()).isEqualTo(key);
    assertThat(recorded.amountCents()).isEqualTo(1999L);
    assertThat(recorded.currency()).isEqualTo("VND");
    assertThat(recorded.stripeCustomerId()).isEqualTo("cus_test_1");
    assertThat(recorded.country()).isNull();
    assertThat(recorded.riskLevel()).isNull();
  }

  @Test
  void authorize_requiresActionForEeaElevatedAboveFloor() {
    StripePaymentAdapter adapter = new StripePaymentAdapter();
    String key = IdempotencyKey.forOrderStep(7L, "payment.authorize");
    AuthorizePaymentCommand cmd = new AuthorizePaymentCommand(7L, 5000L, "EUR", "cus_de_1", key)
        .withCountry("DE")
        .withRiskLevel(RiskLevel.ELEVATED);

    PaymentResult result = adapter.authorize(cmd);

    assertThat(result.status()).isEqualTo(PaymentResult.Status.REQUIRES_ACTION);
    assertThat(result.requiresActionUrl()).isEqualTo(StripePaymentAdapter.STUB_3DS_URL);

    List<StripePaymentAdapter.CallRecord> calls = adapter.callsFor(7L);
    assertThat(calls).hasSize(1);
    assertThat(calls.get(0).country()).isEqualTo("DE");
    assertThat(calls.get(0).riskLevel()).isEqualTo(RiskLevel.ELEVATED);
  }

  @Test
  void authorize_succeedsForNonEeaEvenAtElevated() {
    StripePaymentAdapter adapter = new StripePaymentAdapter();
    AuthorizePaymentCommand cmd = new AuthorizePaymentCommand(99L, 5000L, "USD", "cus_us_1", "k")
        .withCountry("US")
        .withRiskLevel(RiskLevel.ELEVATED);

    PaymentResult result = adapter.authorize(cmd);

    assertThat(result.status()).isEqualTo(PaymentResult.Status.SUCCEEDED);
    assertThat(result.requiresActionUrl()).isNull();
  }
}