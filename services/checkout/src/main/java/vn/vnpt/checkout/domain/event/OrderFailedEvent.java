package vn.vnpt.checkout.domain.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * {@code order.failed} event payload — Story 2.5 / FR-22 (ADR-12 / ADR-20).
 *
 * <p>Emitted when the saga transitions to {@code FAILED} (currently the
 * {@code InsufficientStockException} branch from {@code CREATED → FAILED}; future
 * {@code STOCK_RESERVED → FAILED} when inventory release fails is Epic 3). Reason values are a
 * free-text String for now (e.g. {@code "INSUFFICIENT_STOCK"}); later stories can promote to enum.
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrderFailedEvent {

  Long eventId;
  String aggregateType;
  Long aggregateId;
  Instant occurredAt;

  Long orderUuid;
  Long cartUuid;
  Long checkoutUuid;
  String failureSagaStep;
  String failureReason;
  String tenantId;

  Map<String, String> signatures;
}