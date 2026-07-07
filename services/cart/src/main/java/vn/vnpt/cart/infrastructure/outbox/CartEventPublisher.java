package vn.vnpt.cart.infrastructure.outbox;

import java.time.Instant;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import vn.vnpt.cart.application.port.OutboxPublisher;
import vn.vnpt.cart.domain.Cart;
import vn.vnpt.cart.domain.CartLine;
import vn.vnpt.cart.domain.CartStatus;
import vn.vnpt.cart.domain.event.CartExpiredEvent;
import vn.vnpt.cart.domain.event.CartLineAddedEvent;
import vn.vnpt.cart.domain.event.CartMergedEvent;
import vn.vnpt.util.common.SnowflakeIdGenerator;
import vn.vnpt.util.events.HmacEventSigner;
import vn.vnpt.util.events.JcsCanonicalJson;

/**
 * Cart lifecycle event publisher — Story 2.1 / FR-14 (ADR-20 producer-side HMAC), extended in
 * Story 2.2 with FR-17 ({@code cart.line.added}) and FR-18 ({code cart.expired}).
 *
 * <p>Wraps {@link OutboxPublisher#append} with HMAC signing so use cases don't compute the signature
 * inline (mirrors Story 1.8's {@code LifecycleEventPublisher}). The signature is HS256 over the
 * JCS-canonical payload ({@code HmacEventSigner.sign(JcsCanonicalJson.serialize(payload), secret)}).
 *
 * <p>The ArchUnit rule {@code cart_lifecycleEventsRouteThroughPublisher} guards against use cases
 * calling {@code OutboxPublisher.append(...)} directly for cart lifecycle events. The
 * {@code cart_expiryEventsRouteThroughPublisher} rule (Story 2.2) extends the same guard to the
 * new event types.
 */
@Component
@Slf4j
public class CartEventPublisher {

  /** Wire topic / event type for cart merge events (architecture.md line 341 dot-topic). */
  public static final String CART_MERGED_TOPIC = "cart.merged";

  /** Wire topic for cart line-add events (FR-17 recommendation signal). */
  public static final String CART_LINE_ADDED_TOPIC = "cart.line.added";

  /** Wire topic for cart expiry events (FR-18 sweeper signal). */
  public static final String CART_EXPIRED_TOPIC = "cart.expired";

  private final OutboxPublisher outbox;
  private final ObjectMapper objectMapper;

  @Value("${cart.events.hmac-secret}")
  private String cartServiceSecret;

  public CartEventPublisher(OutboxPublisher outbox, ObjectMapper objectMapper) {
    this.outbox = outbox;
    this.objectMapper = objectMapper;
  }

  /**
   * Build, sign, and publish the {@code cart.merged} event for a completed merge.
   *
   * @param sourceCart the anonymous cart that was merged (source)
   * @param targetCart the user-bound cart that received the lines (target — the aggregate)
   * @param mergedLinesCount number of lines transferred in this merge
   */
  public void publishCartMerged(Cart sourceCart, Cart targetCart, int mergedLinesCount) {
    Instant now = Instant.now();
    CartMergedEvent unsigned =
        CartMergedEvent.builder()
            .eventId(SnowflakeIdGenerator.generateId())
            .aggregateType("Cart")
            .aggregateId(targetCart.getUuid())
            .occurredAt(now)
            .guestCartId(sourceCart.getGuestCartId())
            .userId(targetCart.getUserId())
            .sourceCartUuid(sourceCart.getUuid())
            .targetCartUuid(targetCart.getUuid())
            .mergedLinesCount(mergedLinesCount)
            .mergedAt(now)
            .tenantId(targetCart.getTenantId())
            .build();

    String signature = HmacEventSigner.sign(canonicalize(unsigned), cartServiceSecret);

    CartMergedEvent signed =
        CartMergedEvent.builder()
            .eventId(unsigned.getEventId())
            .aggregateType(unsigned.getAggregateType())
            .aggregateId(unsigned.getAggregateId())
            .occurredAt(unsigned.getOccurredAt())
            .guestCartId(unsigned.getGuestCartId())
            .userId(unsigned.getUserId())
            .sourceCartUuid(unsigned.getSourceCartUuid())
            .targetCartUuid(unsigned.getTargetCartUuid())
            .mergedLinesCount(unsigned.getMergedLinesCount())
            .mergedAt(unsigned.getMergedAt())
            .tenantId(unsigned.getTenantId())
            .signatures(Map.of("hmac_sha256", signature))
            .build();

    outbox.append("Cart", targetCart.getUuid(), CART_MERGED_TOPIC, signed, signed.getSignatures());

    if (log.isDebugEnabled()) {
      log.debug(
          "cart.merged published: source={} target={} lines={}",
          sourceCart.getUuid(),
          targetCart.getUuid(),
          mergedLinesCount);
    }
  }

  /** Publish the {@code cart.line.added} event (FR-17). Emitted by AddLine + per merged line. */
  public void publishLineAdded(Cart cart, CartLine line) {
    Instant now = Instant.now();
    CartLineAddedEvent unsigned =
        CartLineAddedEvent.builder()
            .eventId(SnowflakeIdGenerator.generateId())
            .aggregateType("Cart")
            .aggregateId(cart.getUuid())
            .occurredAt(now)
            .cartUuid(cart.getUuid())
            .lineUuid(line.getUuid())
            .variantId(line.getVariantId())
            .quantity(line.getQuantity())
            .tenantId(cart.getTenantId())
            .build();

    String signature = HmacEventSigner.sign(canonicalize(unsigned), cartServiceSecret);

    CartLineAddedEvent signed =
        CartLineAddedEvent.builder()
            .eventId(unsigned.getEventId())
            .aggregateType(unsigned.getAggregateType())
            .aggregateId(unsigned.getAggregateId())
            .occurredAt(unsigned.getOccurredAt())
            .cartUuid(unsigned.getCartUuid())
            .lineUuid(unsigned.getLineUuid())
            .variantId(unsigned.getVariantId())
            .quantity(unsigned.getQuantity())
            .tenantId(unsigned.getTenantId())
            .signatures(Map.of("hmac_sha256", signature))
            .build();

    outbox.append("Cart", cart.getUuid(), CART_LINE_ADDED_TOPIC, signed, signed.getSignatures());

    if (log.isDebugEnabled()) {
      log.debug(
          "cart.line.added published: cart={} line={} variant={} qty={}",
          cart.getUuid(),
          line.getUuid(),
          line.getVariantId(),
          line.getQuantity());
    }
  }

  /**
   * Publish the {@code cart.expired} event (FR-18). Emitted by {@code ExpireCartUseCase} inside the
   * sweeper-initiated {@code @Transactional(REQUIRES_NEW)} boundary.
   *
   * @param cart the cart that was expired (status already transitioned to {@code ABANDONED})
   * @param previousStatus the status BEFORE the transition (captured by ExpireCartUseCase)
   * @param expiredLinesCount count of active lines at expiry time
   */
  public void publishCartExpired(Cart cart, CartStatus previousStatus, int expiredLinesCount) {
    Instant now = Instant.now();
    CartExpiredEvent unsigned =
        CartExpiredEvent.builder()
            .eventId(SnowflakeIdGenerator.generateId())
            .aggregateType("Cart")
            .aggregateId(cart.getUuid())
            .occurredAt(now)
            .cartUuid(cart.getUuid())
            .guestCartId(cart.getGuestCartId())
            .userId(cart.getUserId())
            .previousStatus(previousStatus)
            .expiredLinesCount(expiredLinesCount)
            .expiresAt(cart.getExpiresAt())
            .expiredAt(now)
            .tenantId(cart.getTenantId())
            .build();

    String signature = HmacEventSigner.sign(canonicalize(unsigned), cartServiceSecret);

    CartExpiredEvent signed =
        CartExpiredEvent.builder()
            .eventId(unsigned.getEventId())
            .aggregateType(unsigned.getAggregateType())
            .aggregateId(unsigned.getAggregateId())
            .occurredAt(unsigned.getOccurredAt())
            .cartUuid(unsigned.getCartUuid())
            .guestCartId(unsigned.getGuestCartId())
            .userId(unsigned.getUserId())
            .previousStatus(unsigned.getPreviousStatus())
            .expiredLinesCount(unsigned.getExpiredLinesCount())
            .expiresAt(unsigned.getExpiresAt())
            .expiredAt(unsigned.getExpiredAt())
            .tenantId(unsigned.getTenantId())
            .signatures(Map.of("hmac_sha256", signature))
            .build();

    outbox.append("Cart", cart.getUuid(), CART_EXPIRED_TOPIC, signed, signed.getSignatures());

    if (log.isDebugEnabled()) {
      log.debug(
          "cart.expired published: cart={} previousStatus={} lines={}",
          cart.getUuid(),
          previousStatus,
          expiredLinesCount);
    }
  }

  @SuppressWarnings("unchecked")
  private String canonicalize(Object payload) {
    Map<String, Object> map = objectMapper.convertValue(payload, Map.class);
    return JcsCanonicalJson.serialize(map);
  }
}