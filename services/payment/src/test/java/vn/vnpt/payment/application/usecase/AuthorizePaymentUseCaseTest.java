package vn.vnpt.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import vn.vnpt.payment.application.port.AuthorizePaymentCommand;
import vn.vnpt.payment.application.port.PaymentResult;
import vn.vnpt.payment.infrastructure.IdempotencyKey;
import vn.vnpt.payment.infrastructure.stripe.StripePaymentAdapter;

/**
 * Wires the real {@link AuthorizePaymentUseCase} against the test-double {@link StripePaymentAdapter}
 * (no Spring context — the use case has no other collaborators) — Story 3.1 / AC #3, #9.
 *
 * <p>Asserts the use case passes the {@code forOrderStep(orderUuid, "payment.authorize")} key to
 * the port on every call, regardless of how many times {@code execute(...)} is invoked.
 */
class AuthorizePaymentUseCaseTest {

  @Test
  void execute_passesStableIdempotencyKeyAcrossCalls() {
    StripePaymentAdapter adapter = new StripePaymentAdapter();
    AuthorizePaymentUseCase useCase = new AuthorizePaymentUseCase(adapter);

    AuthorizePaymentCommand cmd = new AuthorizePaymentCommand(
        42L, 1999L, "VND", "cus_test_1", null);

    PaymentResult first = useCase.execute(cmd);
    PaymentResult second = useCase.execute(cmd);

    assertThat(first.status()).isEqualTo(PaymentResult.Status.SUCCEEDED);
    assertThat(second.status()).isEqualTo(PaymentResult.Status.SUCCEEDED);

    List<StripePaymentAdapter.CallRecord> calls = adapter.callsFor(42L);
    assertThat(calls).hasSize(2);

    String expectedKey = IdempotencyKey.forOrderStep(42L, "payment.authorize");
    assertThat(calls.get(0).idempotencyKey()).isEqualTo(expectedKey);
    assertThat(calls.get(1).idempotencyKey()).isEqualTo(expectedKey);
    // The whole point of FR-25: same key on every call so Stripe returns the cached response.
    assertThat(calls.get(0).idempotencyKey()).isEqualTo(calls.get(1).idempotencyKey());
  }

  @Test
  void execute_propagatesAmountAndCurrency() {
    StripePaymentAdapter adapter = new StripePaymentAdapter();
    AuthorizePaymentUseCase useCase = new AuthorizePaymentUseCase(adapter);

    useCase.execute(new AuthorizePaymentCommand(7L, 4242L, "USD", null, null));

    List<StripePaymentAdapter.CallRecord> calls = adapter.callsFor(7L);
    assertThat(calls).hasSize(1);
    assertThat(calls.get(0).amountCents()).isEqualTo(4242L);
    assertThat(calls.get(0).currency()).isEqualTo("USD");
  }
}