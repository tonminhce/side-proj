package vn.vnpt.checkout.infrastructure.stripe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import vn.vnpt.checkout.application.port.StripePaymentGateway;
import vn.vnpt.checkout.domain.exception.StripePaymentIntentException;

/**
 * Story 2.4 / FR-20 — Stripe SDK adapter test (mockStatic around {@link PaymentIntent#create}).
 * Asserts params (amount, currency, capture_method=manual), idempotency-key pass-through, and
 * StripeException → StripePaymentIntentException mapping.
 */
class StripePaymentIntentGatewayTest {

  private MockedStatic<PaymentIntent> paymentIntentStatic;

  @AfterEach
  void tearDown() {
    if (paymentIntentStatic != null) {
      paymentIntentStatic.close();
    }
  }

  private StripePaymentIntentGateway newGateway() {
    return new StripePaymentIntentGateway("sk_test_fake_key_for_unit_test_only");
  }

  @Test
  void createPaymentIntent_buildsManualCaptureParams_andPassesIdempotencyKey() throws StripeException {
    StripePaymentIntentGateway gateway = newGateway();
    paymentIntentStatic = mockStatic(PaymentIntent.class);

    PaymentIntent fake = mock(PaymentIntent.class);
    when(fake.getId()).thenReturn("pi_test_abc");
    when(fake.getClientSecret()).thenReturn("pi_test_abc_secret_xyz");
    paymentIntentStatic
        .when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
        .thenReturn(fake);

    StripePaymentGateway.Result result =
        gateway.createPaymentIntent(100_000L, "vnd", "12345:stripe.payment_intent.create");

    assertThat(result.paymentIntentId()).isEqualTo("pi_test_abc");
    assertThat(result.clientSecret()).isEqualTo("pi_test_abc_secret_xyz");

    org.mockito.ArgumentCaptor<PaymentIntentCreateParams> paramsCaptor =
        org.mockito.ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
    org.mockito.ArgumentCaptor<RequestOptions> optionsCaptor =
        org.mockito.ArgumentCaptor.forClass(RequestOptions.class);
    paymentIntentStatic.verify(
        () -> PaymentIntent.create(paramsCaptor.capture(), optionsCaptor.capture()));

    PaymentIntentCreateParams params = paramsCaptor.getValue();
    assertThat(params.getAmount()).isEqualTo(100_000L);
    assertThat(params.getCurrency()).isEqualTo("vnd");
    assertThat(params.getCaptureMethod()).isEqualTo(PaymentIntentCreateParams.CaptureMethod.MANUAL);

    RequestOptions options = optionsCaptor.getValue();
    assertThat(options.getIdempotencyKey()).isEqualTo("12345:stripe.payment_intent.create");
  }

  @Test
  void createPaymentIntent_wrapsStripeException_intoDomainException() throws StripeException {
    StripePaymentIntentGateway gateway = newGateway();
    paymentIntentStatic = mockStatic(PaymentIntent.class);
    paymentIntentStatic
        .when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
        .thenThrow(
            new InvalidRequestException(
                "boom", "param_xxx", "code", "type", 400, new RuntimeException()));

    assertThatThrownBy(
            () -> gateway.createPaymentIntent(50_000L, "vnd", "abc:stripe.payment_intent.create"))
        .isInstanceOf(StripePaymentIntentException.class);
  }
}