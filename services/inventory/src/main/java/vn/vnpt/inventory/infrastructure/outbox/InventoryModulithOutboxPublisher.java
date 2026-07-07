package vn.vnpt.inventory.infrastructure.outbox;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import vn.vnpt.inventory.application.port.OutboxPublisher;
import vn.vnpt.util.events.ModulithOutboxPublisher;

/** Inventory outbox publisher — no HMAC signing for inventory outbound events. */
@Component
public class InventoryModulithOutboxPublisher extends ModulithOutboxPublisher implements OutboxPublisher {

  public InventoryModulithOutboxPublisher(
      JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, ApplicationEventPublisher applicationEventPublisher) {
    super(jdbcTemplate, objectMapper, applicationEventPublisher);
  }
}
