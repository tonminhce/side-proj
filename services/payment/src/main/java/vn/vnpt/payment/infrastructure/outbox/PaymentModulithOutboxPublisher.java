package vn.vnpt.payment.infrastructure.outbox;

import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import vn.vnpt.payment.infrastructure.security.HmacServiceKeyProvider;
import vn.vnpt.util.events.HmacEventSigner;
import vn.vnpt.util.events.JcsCanonicalJson;
import vn.vnpt.util.events.ModulithOutboxPublisher;

/**
 * Payment Modulith outbox publisher — Story 3.1 + 3.5. Concrete subclass so the abstract
 * {@link ModulithOutboxPublisher} is registered as a Spring bean; the {@code @Import} below also
 * wires util's inner {@code ModulithBridgeSupport} {@code @Configuration} so the
 * {@code EventPublicationRepository} + {@code EventSerializer} beans are available (Spring's
 * nested-class component-scan doesn't pick them up when the outer is abstract).
 *
 * <p>Story 3.5 override: HMAC-sign every envelope before insert via
 * {@link HmacEventSigner#sign} + {@link JcsCanonicalJson#serialize} (ADR-20 / AT-03 mitigation).
 */
@Component
@Import(ModulithOutboxPublisher.ModulithBridgeSupport.class)
public class PaymentModulithOutboxPublisher extends ModulithOutboxPublisher {

  private final HmacServiceKeyProvider hmacKeyProvider;

  public PaymentModulithOutboxPublisher(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      ApplicationEventPublisher applicationEventPublisher,
      HmacServiceKeyProvider hmacKeyProvider) {
    super(jdbcTemplate, objectMapper, applicationEventPublisher);
    this.hmacKeyProvider = hmacKeyProvider;
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
        "service", "payment",
        "hmac_sha256", sig,
        "key_id", "v1");
  }
}