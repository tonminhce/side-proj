package vn.vnpt.cart.infrastructure.outbox;

import java.time.Instant;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import vn.vnpt.cart.application.port.OutboxPublisher;
import vn.vnpt.cart.domain.Cart;
import vn.vnpt.cart.domain.event.CartMergedEvent;
import vn.vnpt.util.common.SnowflakeIdGenerator;
import vn.vnpt.util.events.HmacEventSigner;
import vn.vnpt.util.events.JcsCanonicalJson;

/**
 * Cart lifecycle event publisher — Story 2.1 / FR-14 (ADR-20 producer-side HMAC).
 *
 * <p>Wraps {@link OutboxPublisher#append} with HMAC signing so use cases don't compute the signature
 * inline (mirrors Story 1.8's {@code LifecycleEventPublisher}). The signature is HS256 over the
 * JCS-canonical payload ({@code HmacEventSigner.sign(JcsCanonicalJson.serialize(payload), secret)}).
 *
 * <p>The ArchUnit rule {@code cart_lifecycleEventsRouteThroughPublisher} guards against use cases
 * calling {@code OutboxPublisher.append(...)} directly for cart lifecycle events.
 */
@Component
@Slf4j
public class CartEventPublisher {

  /** Wire topic / event type for cart merge events (architecture.md line 341 dot-topic). */
  public static final String CART_MERGED_TOPIC = "cart.merged";

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

  @SuppressWarnings("unchecked")
  private String canonicalize(Object payload) {
    Map<String, Object> map = objectMapper.convertValue(payload, Map.class);
    return JcsCanonicalJson.serialize(map);
  }
}
