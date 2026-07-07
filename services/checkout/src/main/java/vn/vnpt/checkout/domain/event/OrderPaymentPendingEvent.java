package vn.vnpt.checkout.domain.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * {@code order.payment_pending} event payload — Story 2.5 / FR-22 (ADR-12 / ADR-20).
 *
 * <p>Emitted on the {@code STOCK_RESERVED → PAYMENT_PENDING} transition. This is the event that
 * downstream consumers (notification, fulfillment — Epic 4/9) will subscribe to in the future.
 * Story 2.5 only publishes; no consumer is wired.
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrderPaymentPendingEvent {

  Long eventId;
  String aggregateType;
  Long aggregateId;
  Instant occurredAt;

  Long orderUuid;
  Long cartUuid;
  Long checkoutUuid;
  String paymentIntentId;
  String tenantId;

  Map<String, String> signatures;
}