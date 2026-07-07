package vn.vnpt.checkout.infrastructure.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import vn.vnpt.checkout.application.port.OutboxPublisher;
import vn.vnpt.checkout.domain.Checkout;
import vn.vnpt.checkout.domain.event.CheckoutStartedEvent;
import vn.vnpt.checkout.domain.snapshot.CartLineSnapshot;
import vn.vnpt.util.common.SnowflakeIdGenerator;
import vn.vnpt.util.events.HmacEventSigner;
import vn.vnpt.util.events.JcsCanonicalJson;

/**
 * Checkout lifecycle event publisher — Story 2.3 / FR-19, FR-21 (ADR-20 producer-side HMAC).
 *
 * <p>Wraps {@link OutboxPublisher#append} with HMAC signing so use cases don't compute the signature
 * inline (mirrors cart's {@code CartEventPublisher}). The signature is HS256 over the JCS-canonical
 * payload ({@code HmacEventSigner.sign(JcsCanonicalJson.serialize(payload), secret)}).
 *
 * <p>The ArchUnit rule {@code checkout_lifecycleEventsRouteThroughPublisher} guards against use cases
 * calling {@code OutboxPublisher.append(...)} directly for checkout lifecycle events.
 */
@Component
@Slf4j
public class CheckoutEventPublisher {

  /** Wire topic / event type for checkout-start events (architecture.md line 341 dot-topic). */
  public static final String CHECKOUT_STARTED_TOPIC = "checkout.started";

  private final OutboxPublisher outbox;
  private final ObjectMapper objectMapper;

  @Value("${checkout.events.hmac-secret}")
  private String checkoutServiceSecret;

  public CheckoutEventPublisher(OutboxPublisher outbox, ObjectMapper objectMapper) {
    this.outbox = outbox;
    this.objectMapper = objectMapper;
  }

  /**
   * Build, sign, and publish the {@code checkout.started} event for a newly created checkout.
   *
   * @param checkout the checkout that was started (the aggregate)
   * @param cartLines the BFF-mediated cart-line snapshot captured at checkout-start time
   */
  public void publishCheckoutStarted(Checkout checkout, List<CartLineSnapshot> cartLines) {
    Instant now = Instant.now();
    CheckoutStartedEvent unsigned =
        CheckoutStartedEvent.builder()
            .eventId(SnowflakeIdGenerator.generateId())
            .aggregateType("Checkout")
            .aggregateId(checkout.getUuid())
            .occurredAt(now)
            .checkoutUuid(checkout.getUuid())
            .cartUuid(checkout.getCartUuid())
            .userId(checkout.getUserId())
            .guestCartId(checkout.getGuestCartId())
            .tenantId(checkout.getTenantId())
            .shippingAddress(checkout.getShippingAddress())
            .cartLines(cartLines)
            .paymentIntentId(checkout.getPaymentIntentId())
            .build();

    String signature = HmacEventSigner.sign(canonicalize(unsigned), checkoutServiceSecret);

    CheckoutStartedEvent signed =
        CheckoutStartedEvent.builder()
            .eventId(unsigned.getEventId())
            .aggregateType(unsigned.getAggregateType())
            .aggregateId(unsigned.getAggregateId())
            .occurredAt(unsigned.getOccurredAt())
            .checkoutUuid(unsigned.getCheckoutUuid())
            .cartUuid(unsigned.getCartUuid())
            .userId(unsigned.getUserId())
            .guestCartId(unsigned.getGuestCartId())
            .tenantId(unsigned.getTenantId())
            .shippingAddress(unsigned.getShippingAddress())
            .cartLines(unsigned.getCartLines())
            .paymentIntentId(unsigned.getPaymentIntentId())
            .signatures(Map.of("hmac_sha256", signature))
            .build();

    outbox.append("Checkout", checkout.getUuid(), CHECKOUT_STARTED_TOPIC, signed, signed.getSignatures());

    if (log.isDebugEnabled()) {
      log.debug(
          "checkout.started published: checkout={} cart={} lines={}",
          checkout.getUuid(),
          checkout.getCartUuid(),
          cartLines == null ? 0 : cartLines.size());
    }
  }

  @SuppressWarnings("unchecked")
  private String canonicalize(Object payload) {
    Map<String, Object> map = objectMapper.convertValue(payload, Map.class);
    return JcsCanonicalJson.serialize(map);
  }
}