package vn.vnpt.payment.infrastructure.outbox;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import vn.vnpt.util.events.ModulithOutboxPublisher;

/**
 * Payment Modulith outbox publisher — Story 3.1. Concrete subclass so the abstract
 * {@link ModulithOutboxPublisher} is registered as a Spring bean; the {@code @Import} below also
 * wires util's inner {@code ModulithBridgeSupport} {@code @Configuration} so the
 * {@code EventPublicationRepository} + {@code EventSerializer} beans are available (Spring's
 * nested-class component-scan doesn't pick them up when the outer is abstract).
 *
 * <p>Story 3.1 emits no events (FR-28 lands in Story 3.2 / 3.5); the {@code append(...)} method
 * inherited from {@link ModulithOutboxPublisher} stays unused until Story 3.5 wires the real
 * HMAC-signed outbox writes.
 */
@Component
@Import(ModulithOutboxPublisher.ModulithBridgeSupport.class)
public class PaymentModulithOutboxPublisher extends ModulithOutboxPublisher {

  public PaymentModulithOutboxPublisher(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      ApplicationEventPublisher applicationEventPublisher) {
    super(jdbcTemplate, objectMapper, applicationEventPublisher);
  }
}