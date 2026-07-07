package vn.vnpt.checkout.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import vn.vnpt.checkout.application.port.OutboxPublisher;
import vn.vnpt.checkout.domain.Checkout;
import vn.vnpt.checkout.domain.CheckoutStatus;
import vn.vnpt.checkout.domain.ShippingAddress;
import vn.vnpt.checkout.domain.event.CheckoutStartedEvent;
import vn.vnpt.checkout.domain.snapshot.CartLineSnapshot;
import vn.vnpt.util.events.HmacEventSigner;
import vn.vnpt.util.events.JcsCanonicalJson;

/** Story 2.3 / FR-19 — HMAC signing + outbox dispatch. */
@ExtendWith(MockitoExtension.class)
class CheckoutEventPublisherTest {

  private static final String SECRET = "dev-only-secret-do-not-use-in-prod";

  @Mock OutboxPublisher outbox;
  private final ObjectMapper objectMapper = new ObjectMapper();

  private CheckoutEventPublisher newPublisher() {
    CheckoutEventPublisher p = new CheckoutEventPublisher(outbox, objectMapper);
    ReflectionTestUtils.setField(p, "checkoutServiceSecret", SECRET);
    return p;
  }

  private Checkout checkout() {
    Checkout c =
        Checkout.builder()
            .tenantId("default")
            .cartUuid(99L)
            .userId("u-abc-123")
            .status(CheckoutStatus.PAYMENT_PENDING)
            .version(0L)
            .stripeClientSecret("pi_xxx_secret_xxx")
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
    c.setUuid(12345L);
    return c;
  }

  private List<CartLineSnapshot> cartLines() {
    return List.of(
        CartLineSnapshot.builder().variantId(1001L).sellerId("seller-1").quantity(2).build(),
        CartLineSnapshot.builder().variantId(1002L).quantity(1).build());
  }

  @Test
  void publishCheckoutStarted_buildsEvent_withHmacSignature_andCallsOutboxAppend() {
    CheckoutEventPublisher publisher = newPublisher();
    Checkout checkout = checkout();

    publisher.publishCheckoutStarted(checkout, cartLines());

    ArgumentCaptor<CheckoutStartedEvent> eventCaptor =
        ArgumentCaptor.forClass(CheckoutStartedEvent.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> sigsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(outbox)
        .append(
            eq("Checkout"),
            eq(12345L),
            eq(CheckoutEventPublisher.CHECKOUT_STARTED_TOPIC),
            eventCaptor.capture(),
            sigsCaptor.capture());

    CheckoutStartedEvent signed = eventCaptor.getValue();
    assertThat(signed.getAggregateType()).isEqualTo("Checkout");
    assertThat(signed.getAggregateId()).isEqualTo(12345L);
    assertThat(signed.getCheckoutUuid()).isEqualTo(12345L);
    assertThat(signed.getCartUuid()).isEqualTo(99L);
    assertThat(signed.getUserId()).isEqualTo("u-abc-123");
    assertThat(signed.getTenantId()).isEqualTo("default");
    assertThat(signed.getEventId()).isNotNull();
    assertThat(signed.getOccurredAt()).isNotNull();
    assertThat(signed.getStripeClientSecret()).isEqualTo("pi_xxx_secret_xxx");
    assertThat(signed.getShippingAddress().getCity()).isEqualTo("HCM");

    Map<String, String> sigs = sigsCaptor.getValue();
    assertThat(sigs).containsKey("hmac_sha256");
    assertThat(sigs.get("hmac_sha256")).isNotBlank();
  }

  @Test
  void publishCheckoutStarted_includesCartLineSnapshotInPayload() {
    CheckoutEventPublisher publisher = newPublisher();
    Checkout checkout = checkout();
    List<CartLineSnapshot> lines = cartLines();

    publisher.publishCheckoutStarted(checkout, lines);

    ArgumentCaptor<CheckoutStartedEvent> eventCaptor =
        ArgumentCaptor.forClass(CheckoutStartedEvent.class);
    verify(outbox)
        .append(
            any(String.class),
            anyLong(),
            eq(CheckoutEventPublisher.CHECKOUT_STARTED_TOPIC),
            eventCaptor.capture(),
            any(Map.class));

    CheckoutStartedEvent signed = eventCaptor.getValue();
    assertThat(signed.getCartLines()).hasSize(2);
    assertThat(signed.getCartLines().get(0).getVariantId()).isEqualTo(1001L);
    assertThat(signed.getCartLines().get(0).getSellerId()).isEqualTo("seller-1");
    assertThat(signed.getCartLines().get(0).getQuantity()).isEqualTo(2);
    assertThat(signed.getCartLines().get(1).getSellerId()).isNull(); // @JsonInclude strips
  }

  @Test
  void publishCheckoutStarted_signsAfterPayloadConstruction_perAdr20() {
    CheckoutEventPublisher publisher = newPublisher();
    Checkout checkout = checkout();

    Instant fixed = Instant.parse("2026-07-07T10:15:30Z");
    ReflectionTestUtils.setField(checkout, "createdAt", null);
    // We can't truly fix Instant.now() but we can verify the signature is computed over the
    // payload — the proof is that verify() returns true.
    publisher.publishCheckoutStarted(checkout, cartLines());

    ArgumentCaptor<CheckoutStartedEvent> eventCaptor =
        ArgumentCaptor.forClass(CheckoutStartedEvent.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> sigsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(outbox)
        .append(
            any(),
            anyLong(),
            any(),
            eventCaptor.capture(),
            sigsCaptor.capture());

    CheckoutStartedEvent signed = eventCaptor.getValue();
    String signature = sigsCaptor.getValue().get("hmac_sha256");

    // Reconstruct the unsigned payload by stripping signatures — verify HMAC matches.
    CheckoutStartedEvent unsigned =
        CheckoutStartedEvent.builder()
            .eventId(signed.getEventId())
            .aggregateType(signed.getAggregateType())
            .aggregateId(signed.getAggregateId())
            .occurredAt(signed.getOccurredAt())
            .checkoutUuid(signed.getCheckoutUuid())
            .cartUuid(signed.getCartUuid())
            .userId(signed.getUserId())
            .guestCartId(signed.getGuestCartId())
            .tenantId(signed.getTenantId())
            .shippingAddress(signed.getShippingAddress())
            .cartLines(signed.getCartLines())
            .stripeClientSecret(signed.getStripeClientSecret())
            .signatures(null)
            .build();

    @SuppressWarnings("unchecked")
    Map<String, Object> map = objectMapper.convertValue(unsigned, Map.class);
    String canonical = JcsCanonicalJson.serialize(map);
    assertThat(HmacEventSigner.verify(canonical, signature, SECRET)).isTrue();
  }
}