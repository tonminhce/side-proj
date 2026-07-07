package vn.vnpt.cart.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import vn.vnpt.cart.application.port.OutboxPublisher;
import vn.vnpt.cart.domain.Cart;
import vn.vnpt.cart.domain.CartStatus;
import vn.vnpt.cart.domain.event.CartMergedEvent;
import vn.vnpt.util.events.HmacEventSigner;
import vn.vnpt.util.events.JcsCanonicalJson;

/**
 * HMAC signing + outbox dispatch — Story 2.1 / FR-14, ADR-20 (producer half).
 *
 * <p>Unit test (no Spring context). Verifies: (1) the signatures map carries a non-empty HMAC that
 * matches {@link HmacEventSigner#verify} over the JCS-canonical payload, (2) the outbox is called
 * with {@code aggregateType="Cart"} + {@code eventType="cart.merged"} + the signed event.
 */
@ExtendWith(MockitoExtension.class)
class CartEventPublisherTest {

  private static final String SECRET = "dev-only-secret-do-not-use-in-prod";

  @Mock OutboxPublisher outbox;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private CartEventPublisher publisher;

  private CartEventPublisher newPublisher() {
    CartEventPublisher p = new CartEventPublisher(outbox, objectMapper);
    ReflectionTestUtils.setField(p, "cartServiceSecret", SECRET);
    return p;
  }

  private Cart source() {
    Cart c = Cart.builder().tenantId("default").guestCartId("guest-1").status(CartStatus.ANONYMOUS).build();
    c.setUuid(100L);
    return c;
  }

  private Cart target() {
    Cart c = Cart.builder().tenantId("default").userId("user-1").status(CartStatus.ACTIVE).build();
    c.setUuid(200L);
    return c;
  }

  @Test
  void publishCartMerged_signsPayload_andDispatchesToOutbox() {
    publisher = newPublisher();
    Cart source = source();
    Cart target = target();

    publisher.publishCartMerged(source, target, 3);

    ArgumentCaptor<CartMergedEvent> eventCaptor = ArgumentCaptor.forClass(CartMergedEvent.class);
    ArgumentCaptor<Map<String, String>> sigsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(outbox)
        .append(eq("Cart"), eq(200L), eq(CartEventPublisher.CART_MERGED_TOPIC), eventCaptor.capture(),
            sigsCaptor.capture());

    CartMergedEvent signed = eventCaptor.getValue();
    assertThat(signed.getAggregateType()).isEqualTo("Cart");
    assertThat(signed.getAggregateId()).isEqualTo(200L);
    assertThat(signed.getUserId()).isEqualTo("user-1");
    assertThat(signed.getGuestCartId()).isEqualTo("guest-1");
    assertThat(signed.getSourceCartUuid()).isEqualTo(100L);
    assertThat(signed.getTargetCartUuid()).isEqualTo(200L);
    assertThat(signed.getMergedLinesCount()).isEqualTo(3);
    assertThat(signed.getEventId()).isNotNull();
    assertThat(signed.getOccurredAt()).isNotNull();
    assertThat(signed.getMergedAt()).isNotNull();

    Map<String, String> sigs = sigsCaptor.getValue();
    assertThat(sigs).containsKey("hmac_sha256");
    assertThat(sigs.get("hmac_sha256")).isNotBlank();

    // Re-derive the signature over the same JCS payload and confirm it matches.
    String signature = sigs.get("hmac_sha256");
    // The signatures map is OUT of the canonical payload (ADR-20 producer half) — re-derive
    // canonical JSON over the unsigned event fields.
    CartMergedEvent unsigned = withNullSignatures(signed);
    Map<String, Object> map = objectMapper.convertValue(unsigned, Map.class);
    String canonical = JcsCanonicalJson.serialize(map);
    assertThat(HmacEventSigner.verify(canonical, signature, SECRET)).isTrue();
  }

  @Test
  void publishCartMerged_differentSources_produceDifferentSignatures() {
    publisher = newPublisher();
    Cart sourceA = source();
    sourceA.setUuid(101L);
    Cart sourceB = source();
    sourceB.setUuid(102L);

    publisher.publishCartMerged(sourceA, target(), 1);
    publisher.publishCartMerged(sourceB, target(), 1);

    ArgumentCaptor<Map<String, String>> sigsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(outbox, org.mockito.Mockito.times(2))
        .append(any(), anyLong(), any(), any(), sigsCaptor.capture());
    assertThat(sigsCaptor.getAllValues().get(0).get("hmac_sha256"))
        .isNotEqualTo(sigsCaptor.getAllValues().get(1).get("hmac_sha256"));
  }

  private static CartMergedEvent withNullSignatures(CartMergedEvent e) {
    return CartMergedEvent.builder()
        .eventId(e.getEventId())
        .aggregateType(e.getAggregateType())
        .aggregateId(e.getAggregateId())
        .occurredAt(e.getOccurredAt())
        .guestCartId(e.getGuestCartId())
        .userId(e.getUserId())
        .sourceCartUuid(e.getSourceCartUuid())
        .targetCartUuid(e.getTargetCartUuid())
        .mergedLinesCount(e.getMergedLinesCount())
        .mergedAt(e.getMergedAt())
        .tenantId(e.getTenantId())
        .signatures(null)
        .build();
  }
}