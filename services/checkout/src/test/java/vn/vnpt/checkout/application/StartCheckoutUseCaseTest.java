package vn.vnpt.checkout.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import vn.vnpt.checkout.application.port.StripePaymentGateway;
import vn.vnpt.checkout.domain.Checkout;
import vn.vnpt.checkout.domain.CheckoutStatus;
import vn.vnpt.checkout.domain.ShippingAddress;
import vn.vnpt.checkout.domain.snapshot.CartLineSnapshot;
import vn.vnpt.checkout.infrastructure.outbox.CheckoutEventPublisher;
import vn.vnpt.checkout.infrastructure.repository.CheckoutRepository;
import vn.vnpt.util.common.SnowflakeIdGenerator;

/** Story 2.3 / FR-19; Story 2.4 / FR-20 — happy path + 3 validation paths + amount computation. */
@ExtendWith(MockitoExtension.class)
class StartCheckoutUseCaseTest {

  private static final String PI_ID = "pi_test_abc123";
  private static final String PI_SECRET = "pi_test_abc123_secret_xyz";

  @Mock CheckoutRepository checkoutRepository;
  @Mock CheckoutEventPublisher checkoutEventPublisher;
  @Mock StripePaymentGateway stripePaymentGateway;
  @InjectMocks StartCheckoutUseCase useCase;

  private ShippingAddress address() {
    return ShippingAddress.builder()
        .recipientName("Nguyen Van A")
        .phone("0901234567")
        .addressLine1("123 Le Loi")
        .city("HCM")
        .province("HCM")
        .country("VN")
        .build();
  }

  private List<CartLineSnapshot> cartLines() {
    return List.of(
        CartLineSnapshot.builder().variantId(1001L).quantity(2).unitPriceMinor(50_000L).build());
  }

  /** Story 2.4 default currency wiring — {@code @Value} default in the use case. */
  private void injectCurrencyDefault(String vnd) {
    ReflectionTestUtils.setField(useCase, "currencyDefault", vnd);
  }

  @Test
  void start_validRequest_persistsCheckoutInPaymentPendingAndEmitsCheckoutStarted() {
    injectCurrencyDefault("VND");
    when(stripePaymentGateway.createPaymentIntent(anyLong(), eq("vnd"), anyString()))
        .thenReturn(new StripePaymentGateway.Result(PI_ID, PI_SECRET));
    // Mimic BaseEntity.@PrePersist — production assigns the Snowflake ID during persist().
    when(checkoutRepository.save(any(Checkout.class)))
        .thenAnswer(
            inv -> {
              Checkout c = inv.getArgument(0);
              if (c.getUuid() == null) {
                c.setUuid(SnowflakeIdGenerator.generateId());
              }
              return c;
            });

    Checkout result =
        useCase.start(
            StartCheckoutRequest.builder()
                .cartUuid(12345L)
                .userId("u-abc-123")
                .shippingAddress(address())
                .cartLines(cartLines())
                .build());

    assertThat(result.getCartUuid()).isEqualTo(12345L);
    assertThat(result.getUserId()).isEqualTo("u-abc-123");
    assertThat(result.getStatus()).isEqualTo(CheckoutStatus.PAYMENT_PENDING);
    assertThat(result.getStripeClientSecret()).isEqualTo(PI_SECRET);
    assertThat(result.getPaymentIntentId()).isEqualTo(PI_ID);
    assertThat(result.getUuid()).isNotNull();
    assertThat(result.getTenantId()).isEqualTo("default");

    ArgumentCaptor<Checkout> captor = ArgumentCaptor.forClass(Checkout.class);
    verify(checkoutRepository).save(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo(CheckoutStatus.PAYMENT_PENDING);
    assertThat(captor.getValue().getPaymentIntentId()).isEqualTo(PI_ID);

    ArgumentCaptor<List<CartLineSnapshot>> linesCaptor = ArgumentCaptor.forClass(List.class);
    verify(checkoutEventPublisher)
        .publishCheckoutStarted(any(Checkout.class), linesCaptor.capture());
    assertThat(linesCaptor.getValue()).hasSize(1);
    assertThat(linesCaptor.getValue().get(0).getVariantId()).isEqualTo(1001L);

    // PaymentIntent amountMinor = Σ(unitPriceMinor × quantity) = 50_000 × 2 = 100_000.
    ArgumentCaptor<Long> amountCaptor = ArgumentCaptor.forClass(Long.class);
    verify(stripePaymentGateway)
        .createPaymentIntent(amountCaptor.capture(), eq("vnd"), anyString());
    assertThat(amountCaptor.getValue()).isEqualTo(100_000L);

    // Story 2.4 / FR-20 / AC #4 — idempotency key = (cartUuid, "stripe.payment_intent.create")
    // (ADR-11 / NFR-IDEM-2). The key is derived from the STABLE cartUuid so retries of the same
    // logical checkout reuse the same PaymentIntent — not from the freshly-generated Snowflake
    // checkout uuid (which would change per call and create duplicates).
    ArgumentCaptor<String> idemCaptor = ArgumentCaptor.forClass(String.class);
    verify(stripePaymentGateway)
        .createPaymentIntent(anyLong(), anyString(), idemCaptor.capture());
    assertThat(idemCaptor.getValue()).isEqualTo("12345:stripe.payment_intent.create");
  }

  /**
   * AC #4 regression — calling {@code start()} twice with the SAME {@code cartUuid} MUST derive the
   * SAME Stripe idempotency key. Locks in NFR-IDEM-2: Stripe returns the existing PaymentIntent,
   * never a duplicate. (Pre-C2-fix the key was derived from the freshly-generated Snowflake uuid,
   * so each call got a different key and a new PaymentIntent was created on retry.)
   */
  @Test
  void start_repeatedCallWithSameCartUuid_derivesSameIdempotencyKey() {
    injectCurrencyDefault("VND");
    when(stripePaymentGateway.createPaymentIntent(anyLong(), eq("vnd"), anyString()))
        .thenReturn(new StripePaymentGateway.Result(PI_ID, PI_SECRET));
    when(checkoutRepository.save(any(Checkout.class))).thenAnswer(inv -> inv.getArgument(0));

    StartCheckoutRequest request =
        StartCheckoutRequest.builder()
            .cartUuid(77777L)
            .userId("u-abc-123")
            .shippingAddress(address())
            .cartLines(cartLines())
            .build();

    useCase.start(request);
    useCase.start(request);

    ArgumentCaptor<String> idemCaptor = ArgumentCaptor.forClass(String.class);
    verify(stripePaymentGateway, org.mockito.Mockito.times(2))
        .createPaymentIntent(anyLong(), anyString(), idemCaptor.capture());
    List<String> keys = idemCaptor.getAllValues();
    assertThat(keys).hasSize(2);
    assertThat(keys.get(0)).isEqualTo("77777:stripe.payment_intent.create");
    assertThat(keys.get(1)).isEqualTo("77777:stripe.payment_intent.create");
  }

  @Test
  void start_cartUuidRequired_throwsIllegalArgumentException() {
    assertThatThrownBy(
            () ->
                useCase.start(
                    StartCheckoutRequest.builder()
                        .userId("u-abc-123")
                        .shippingAddress(address())
                        .cartLines(cartLines())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cartUuid");
  }

  @Test
  void start_userIdOrGuestCartIdRequired_throwsIllegalArgumentException() {
    assertThatThrownBy(
            () ->
                useCase.start(
                    StartCheckoutRequest.builder()
                        .cartUuid(12345L)
                        .shippingAddress(address())
                        .cartLines(cartLines())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("userId")
        .hasMessageContaining("guestCartId");
  }

  @Test
  void start_shippingAddressRequired_throwsIllegalArgumentException() {
    assertThatThrownBy(
            () ->
                useCase.start(
                    StartCheckoutRequest.builder()
                        .cartUuid(12345L)
                        .userId("u-abc-123")
                        .cartLines(cartLines())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("shippingAddress");
  }

  @Test
  void start_guestCartId_accepted_whenUserIdMissing() {
    injectCurrencyDefault("VND");
    when(stripePaymentGateway.createPaymentIntent(anyLong(), eq("vnd"), anyString()))
        .thenReturn(new StripePaymentGateway.Result(PI_ID, PI_SECRET));
    when(checkoutRepository.save(any(Checkout.class))).thenAnswer(inv -> inv.getArgument(0));

    Checkout result =
        useCase.start(
            StartCheckoutRequest.builder()
                .cartUuid(12345L)
                .guestCartId("guest-cookie-uuid")
                .shippingAddress(address())
                .cartLines(cartLines())
                .build());

    assertThat(result.getGuestCartId()).isEqualTo("guest-cookie-uuid");
    assertThat(result.getUserId()).isNull();
    assertThat(result.getPaymentIntentId()).isEqualTo(PI_ID);
  }

  @Test
  void start_amountMinorMustBePositive_throwsIllegalArgumentException() {
    // unitPriceMinor = 0 → sum = 0 → rejected.
    List<CartLineSnapshot> zero =
        List.of(
            CartLineSnapshot.builder().variantId(1001L).quantity(1).unitPriceMinor(0L).build());

    assertThatThrownBy(
            () ->
                useCase.start(
                    StartCheckoutRequest.builder()
                        .cartUuid(12345L)
                        .userId("u-abc-123")
                        .shippingAddress(address())
                        .cartLines(zero)
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("amountMinor");
  }
}