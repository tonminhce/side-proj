package vn.vnpt.checkout.domain.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * {@code order.stock_reserved} event payload — Story 2.5 / FR-22 (ADR-12 / ADR-20).
 *
 * <p>Emitted on the {@code CREATED → STOCK_RESERVED} transition. Carries the inventory
 * {@code reservationUuid} so downstream consumers can correlate (notification, fulfillment —
 * Epic 4/9).
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrderStockReservedEvent {

  Long eventId;
  String aggregateType;
  Long aggregateId;
  Instant occurredAt;

  Long orderUuid;
  Long cartUuid;
  Long checkoutUuid;
  /** Snowflake id of the inventory reservation row. */
  Long reservationUuid;
  String tenantId;

  Map<String, String> signatures;
}