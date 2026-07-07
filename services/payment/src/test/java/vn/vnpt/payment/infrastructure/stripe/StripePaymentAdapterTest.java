package vn.vnpt.payment.infrastructure.stripe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import vn.vnpt.payment.application.port.AuthorizePaymentCommand;
import vn.vnpt.payment.application.port.PaymentPortUnavailableException;
import vn.vnpt.payment.application.port.PaymentResult;
import vn.vnpt.payment.infrastructure.IdempotencyKey;

/**
 * Contract test for the test-double {@link StripePaymentAdapter} — Story 3.1 / AC #3, #7, #9.
 *
 * <p>Asserts the test-double records every input verbatim (so the use-case test can compare
 * idempotency keys across calls) and simulates the transient-failure path for the saga compensator.
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

    List<StripePaymentAdapter.CallRecord> calls = adapter.callsFor(42L);
    assertThat(calls).hasSize(1);
    StripePaymentAdapter.CallRecord recorded = calls.get(0);
    assertThat(recorded.orderUuid()).isEqualTo(42L);
    assertThat(recorded.idempotencyKey()).isEqualTo(key);
    assertThat(recorded.amountCents()).isEqualTo(1999L);
    assertThat(recorded.currency()).isEqualTo("VND");
    assertThat(recorded.stripeCustomerId()).isEqualTo("cus_test_1");
  }

  @Test
  void authorize_negativeAmountThrowsPaymentPortUnavailable() {
    StripePaymentAdapter adapter = new StripePaymentAdapter();
    String key = IdempotencyKey.forOrderStep(7L, "payment.authorize");
    AuthorizePaymentCommand cmd = new AuthorizePaymentCommand(
        7L, -1L, "VND", null, key);

    assertThatThrownBy(() -> adapter.authorize(cmd))
        .isInstanceOf(PaymentPortUnavailableException.class)
        .hasMessageContaining("amountCents");
  }
}