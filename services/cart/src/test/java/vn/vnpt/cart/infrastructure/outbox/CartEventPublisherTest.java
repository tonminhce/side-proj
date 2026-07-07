package vn.vnpt.cart.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import vn.vnpt.cart.application.port.OutboxPublisher;
import vn.vnpt.cart.domain.Cart;
import vn.vnpt.cart.domain.CartLine;
import vn.vnpt.cart.domain.CartStatus;
import vn.vnpt.cart.domain.event.CartExpiredEvent;
import vn.vnpt.cart.domain.event.CartLineAddedEvent;
import vn.vnpt.cart.domain.event.CartMergedEvent;
import vn.vnpt.util.events.HmacEventSigner;
import vn.vnpt.util.events.JcsCanonicalJson;

/**
 * HMAC signing + outbox dispatch — Story 2.1 / FR-14, ADR-20 (producer half); extended in
 * Story 2.2 with FR-17 ({@code cart.line.added}) and FR-18 ({code cart.expired}).
 *
 * <p>Unit test (no Spring context). Verifies: (1) the signatures map carries a non-empty HMAC that
 * matches {@link HmacEventSigner#verify} over the JCS-canonical payload, (2) the outbox is called
 * with {@code aggregateType="Cart"} + correct event-type + the signed event.
 */
@ExtendWith(MockitoExtension.class)
class CartEventPublisherTest {

  private static final String SECRET = "dev-only-secret-do-not-use-in-prod";

  @Mock OutboxPublisher outbox;
  private final ObjectMapper objectMapper = new ObjectMapper();

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

  private CartLine line(long variantId, int qty) {
    CartLine l = CartLine.builder().cartUuid(200L).variantId(variantId).quantity(qty).build();
    l.setUuid(300L);
    return l;
  }

  @Test
  void publishCartMerged_signsPayload_andDispatchesToOutbox() {
    CartEventPublisher publisher = newPublisher();
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

    String signature = sigs.get("hmac_sha256");
    CartMergedEvent unsigned = withNullSignatures(signed);
    Map<String, Object> map = objectMapper.convertValue(unsigned, Map.class);
    String canonical = JcsCanonicalJson.serialize(map);
    assertThat(HmacEventSigner.verify(canonical, signature, SECRET)).isTrue();
  }

  @Test
  void publishCartMerged_differentSources_produceDifferentSignatures() {
    CartEventPublisher publisher = newPublisher();
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

  @Test
  void publishLineAdded_signsAndAppendsToOutbox() {
    CartEventPublisher publisher = newPublisher();
    Cart target = target();
    CartLine line = line(1001L, 2);

    publisher.publishLineAdded(target, line);

    ArgumentCaptor<CartLineAddedEvent> eventCaptor = ArgumentCaptor.forClass(CartLineAddedEvent.class);
    ArgumentCaptor<Map<String, String>> sigsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(outbox)
        .append(eq("Cart"), eq(200L), eq(CartEventPublisher.CART_LINE_ADDED_TOPIC), eventCaptor.capture(),
            sigsCaptor.capture());

    CartLineAddedEvent signed = eventCaptor.getValue();
    assertThat(signed.getAggregateType()).isEqualTo("Cart");
    assertThat(signed.getAggregateId()).isEqualTo(200L);
    assertThat(signed.getCartUuid()).isEqualTo(200L);
    assertThat(signed.getLineUuid()).isEqualTo(300L);
    assertThat(signed.getVariantId()).isEqualTo(1001L);
    assertThat(signed.getQuantity()).isEqualTo(2);
    assertThat(signed.getTenantId()).isEqualTo("default");
    assertThat(signed.getEventId()).isNotNull();

    Map<String, String> sigs = sigsCaptor.getValue();
    assertThat(sigs).containsKey("hmac_sha256");
    String signature = sigs.get("hmac_sha256");
    CartLineAddedEvent unsigned = lineAddedWithNullSignatures(signed);
    Map<String, Object> map = objectMapper.convertValue(unsigned, Map.class);
    String canonical = JcsCanonicalJson.serialize(map);
    assertThat(HmacEventSigner.verify(canonical, signature, SECRET)).isTrue();
  }

  @Test
  void publishCartExpired_signsAndAppendsToOutbox() {
    CartEventPublisher publisher = newPublisher();
    Cart target = target();
    target.setExpiresAt(Instant.now().minusSeconds(60));

    publisher.publishCartExpired(target, CartStatus.ACTIVE, 5);

    ArgumentCaptor<CartExpiredEvent> eventCaptor = ArgumentCaptor.forClass(CartExpiredEvent.class);
    ArgumentCaptor<Map<String, String>> sigsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(outbox)
        .append(eq("Cart"), eq(200L), eq(CartEventPublisher.CART_EXPIRED_TOPIC), eventCaptor.capture(),
            sigsCaptor.capture());

    CartExpiredEvent signed = eventCaptor.getValue();
    assertThat(signed.getAggregateType()).isEqualTo("Cart");
    assertThat(signed.getCartUuid()).isEqualTo(200L);
    assertThat(signed.getUserId()).isEqualTo("user-1");
    assertThat(signed.getPreviousStatus()).isEqualTo(CartStatus.ACTIVE);
    assertThat(signed.getExpiredLinesCount()).isEqualTo(5);
    assertThat(signed.getExpiresAt()).isNotNull();
    assertThat(signed.getExpiredAt()).isNotNull();

    Map<String, String> sigs = sigsCaptor.getValue();
    assertThat(sigs).containsKey("hmac_sha256");
    String signature = sigs.get("hmac_sha256");
    CartExpiredEvent unsigned = expiredWithNullSignatures(signed);
    Map<String, Object> map = objectMapper.convertValue(unsigned, Map.class);
    String canonical = JcsCanonicalJson.serialize(map);
    assertThat(HmacEventSigner.verify(canonical, signature, SECRET)).isTrue();
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

  private static CartLineAddedEvent lineAddedWithNullSignatures(CartLineAddedEvent e) {
    return CartLineAddedEvent.builder()
        .eventId(e.getEventId())
        .aggregateType(e.getAggregateType())
        .aggregateId(e.getAggregateId())
        .occurredAt(e.getOccurredAt())
        .cartUuid(e.getCartUuid())
        .lineUuid(e.getLineUuid())
        .variantId(e.getVariantId())
        .quantity(e.getQuantity())
        .tenantId(e.getTenantId())
        .signatures(null)
        .build();
  }

  private static CartExpiredEvent expiredWithNullSignatures(CartExpiredEvent e) {
    return CartExpiredEvent.builder()
        .eventId(e.getEventId())
        .aggregateType(e.getAggregateType())
        .aggregateId(e.getAggregateId())
        .occurredAt(e.getOccurredAt())
        .cartUuid(e.getCartUuid())
        .guestCartId(e.getGuestCartId())
        .userId(e.getUserId())
        .previousStatus(e.getPreviousStatus())
        .expiredLinesCount(e.getExpiredLinesCount())
        .expiresAt(e.getExpiresAt())
        .expiredAt(e.getExpiredAt())
        .tenantId(e.getTenantId())
        .signatures(null)
        .build();
  }
}