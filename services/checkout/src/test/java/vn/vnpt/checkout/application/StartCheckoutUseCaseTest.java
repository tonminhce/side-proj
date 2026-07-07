package vn.vnpt.checkout.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.vnpt.checkout.domain.Checkout;
import vn.vnpt.checkout.domain.CheckoutStatus;
import vn.vnpt.checkout.domain.ShippingAddress;
import vn.vnpt.checkout.domain.snapshot.CartLineSnapshot;
import vn.vnpt.checkout.infrastructure.outbox.CheckoutEventPublisher;
import vn.vnpt.checkout.infrastructure.repository.CheckoutRepository;
import vn.vnpt.util.common.SnowflakeIdGenerator;

/** Story 2.3 / FR-19 — happy path + 3 validation paths. */
@ExtendWith(MockitoExtension.class)
class StartCheckoutUseCaseTest {

  @Mock CheckoutRepository checkoutRepository;
  @Mock CheckoutEventPublisher checkoutEventPublisher;
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
    return List.of(CartLineSnapshot.builder().variantId(1001L).quantity(2).build());
  }

  @Test
  void start_validRequest_persistsCheckoutInPaymentPendingAndEmitsCheckoutStarted() {
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
                .stripeClientSecret("pi_xxx_secret_xxx")
                .build());

    assertThat(result.getCartUuid()).isEqualTo(12345L);
    assertThat(result.getUserId()).isEqualTo("u-abc-123");
    assertThat(result.getStatus()).isEqualTo(CheckoutStatus.PAYMENT_PENDING);
    assertThat(result.getStripeClientSecret()).isEqualTo("pi_xxx_secret_xxx");
    assertThat(result.getUuid()).isNotNull();
    assertThat(result.getTenantId()).isEqualTo("default");

    ArgumentCaptor<Checkout> captor = ArgumentCaptor.forClass(Checkout.class);
    verify(checkoutRepository).save(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo(CheckoutStatus.PAYMENT_PENDING);

    ArgumentCaptor<List<CartLineSnapshot>> linesCaptor = ArgumentCaptor.forClass(List.class);
    verify(checkoutEventPublisher)
        .publishCheckoutStarted(any(Checkout.class), linesCaptor.capture());
    assertThat(linesCaptor.getValue()).hasSize(1);
    assertThat(linesCaptor.getValue().get(0).getVariantId()).isEqualTo(1001L);
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
  }
}