package vn.vnpt.payment.infrastructure.repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import vn.vnpt.payment.application.port.WebhookDeliveryLogPort;
import vn.vnpt.payment.infrastructure.entity.WebhookDeliveryLog;

/**
 * Spring Data JPA repository for the {@link WebhookDeliveryLog} shim — plain {@code save}, no
 * dedup. Implements {@link WebhookDeliveryLogPort} so the use case depends on the port, not the repo.
 *
 * <p>TEST-OBSERVABILITY SHIM — deleted by Story 3.5 when real outbox events land.
 */
@Repository
public interface WebhookDeliveryLogRepository
    extends JpaRepository<WebhookDeliveryLog, String>, WebhookDeliveryLogPort {

  @Override
  default void record(String eventId, String eventType, String sideEffectsRecorded) {
    save(WebhookDeliveryLog.builder()
        .eventId(eventId)
        .eventType(eventType)
        .receivedAt(LocalDateTime.now(ZoneOffset.UTC))
        .sideEffectsRecorded(sideEffectsRecorded)
        .build());
  }
}