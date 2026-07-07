package vn.vnpt.checkout.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.vnpt.checkout.domain.Checkout;
import vn.vnpt.checkout.domain.CheckoutStatus;
import vn.vnpt.checkout.domain.ShippingAddress;
import vn.vnpt.checkout.domain.exception.CheckoutNotFoundException;
import vn.vnpt.checkout.infrastructure.repository.CheckoutRepository;

/** Story 2.3 / FR-21 — polling use case. */
@ExtendWith(MockitoExtension.class)
class GetCheckoutUseCaseTest {

  @Mock CheckoutRepository checkoutRepository;
  @InjectMocks GetCheckoutUseCase useCase;

  private Checkout checkout(long uuid) {
    Checkout c =
        Checkout.builder()
            .tenantId("default")
            .cartUuid(12345L)
            .userId("u-abc-123")
            .status(CheckoutStatus.PAYMENT_PENDING)
            .version(0L)
            .shippingAddress(
                ShippingAddress.builder()
                    .recipientName("Nguyen Van A")
                    .phone("0901234567")
                    .addressLine1("123 Le Loi")
                    .city("HCM")
                    .province("HCM")
                    .country("VN")
                    .build())
            .build();
    c.setUuid(uuid);
    return c;
  }

  @Test
  void findByCheckoutUuid_existingCheckout_returnsCheckoutResponse() {
    when(checkoutRepository.findByUuid(200L)).thenReturn(Optional.of(checkout(200L)));

    Checkout result = useCase.findByCheckoutUuid(200L);

    assertThat(result.getUuid()).isEqualTo(200L);
    assertThat(result.getStatus()).isEqualTo(CheckoutStatus.PAYMENT_PENDING);
    assertThat(result.getUserId()).isEqualTo("u-abc-123");
  }

  @Test
  void findByCheckoutUuid_unknownUuid_throwsCheckoutNotFoundException() {
    when(checkoutRepository.findByUuid(404L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> useCase.findByCheckoutUuid(404L))
        .isInstanceOf(CheckoutNotFoundException.class)
        .hasMessageContaining("404");
  }

  @Test
  void findByCheckoutUuid_paymentPendingStatus_returnsPaymentPendingWireValue() {
    when(checkoutRepository.findByUuid(200L)).thenReturn(Optional.of(checkout(200L)));

    Checkout result = useCase.findByCheckoutUuid(200L);

    assertThat(result.getStatus().name()).isEqualTo("PAYMENT_PENDING");
    assertThat(result.getStatus().isTerminal()).isFalse();
  }
}