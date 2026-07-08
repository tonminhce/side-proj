package vn.vnpt.order.infrastructure.outbox;

import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import vn.vnpt.order.application.port.OrderTransitionAppender;
import vn.vnpt.order.infrastructure.entity.OrderStateTransition;
import vn.vnpt.order.infrastructure.security.HmacServiceKeyProvider;
import vn.vnpt.util.events.HmacEventSigner;
import vn.vnpt.util.events.JcsCanonicalJson;
import vn.vnpt.util.events.ModulithOutboxPublisher;

/**
 * Order Modulith outbox publisher — Story 4.1. Mirrors Story 3.5's payment publisher: extends
 * the abstract {@link ModulithOutboxPublisher} (concrete subclass is the Spring bean
 * registration seam), implements {@link OrderTransitionAppender} (the use-case port) by
 * persisting the transition row + publishing the in-process event + signing the envelope.
 *
 * <p>HMAC envelope signature per ADR-20: {@code JcsCanonicalJson} over the envelope → HMAC-SHA-256
 * with the order's service key → base64url-no-padding. The {@code @Import} brings in util's
 * inner {@code ModulithBridgeSupport} so the autoconfig chain sees the no-op
 * {@code EventPublicationRepository} bean.
 */
@Component
@Import(ModulithOutboxPublisher.ModulithBridgeSupport.class)
public class OrderModulithOutboxPublisher extends ModulithOutboxPublisher
    implements OrderTransitionAppender {

  private final HmacServiceKeyProvider hmacKeyProvider;

  public OrderModulithOutboxPublisher(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      ApplicationEventPublisher applicationEventPublisher,
      HmacServiceKeyProvider hmacKeyProvider) {
    super(jdbcTemplate, objectMapper, applicationEventPublisher);
    this.hmacKeyProvider = hmacKeyProvider;
  }

  @Override
  public long append(OrderStateTransition transition) {
    append(
        "Order",
        transition.getOrderUuid(),
        "order." + transition.getToState(),
        transition,
        signaturesFor(
            transition.getEventId(),
            "order." + transition.getToState(),
            "Order",
            transition.getOrderUuid(),
            serialize(transition)));
    return transition.getId() == null ? -1L : transition.getId();
  }

  @Override
  protected Map<String, String> signaturesFor(
      long eventId, String eventType, String aggregateType, long aggregateId, String payloadJson) {
    Map<String, Object> envelope = Map.of(
        "event_id", eventId,
        "event_type", eventType,
        "aggregate_type", aggregateType,
        "aggregate_id", aggregateId,
        "payload", payloadJson);
    String canonical = JcsCanonicalJson.serialize(envelope);
    String sig = HmacEventSigner.sign(canonical, hmacKeyProvider.currentSecret());
    return Map.of(
        "service", "order",
        "hmac_sha256", sig,
        "key_id", "v1");
  }
}