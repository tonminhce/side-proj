package vn.vnpt.cart.domain.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * {@code cart.merged} event payload — Story 2.1 / FR-14, AC #6.
 *
 * <p>Emitted on the first successful merge of an anonymous cart into a user-bound cart. Serialized
 * to the {@code outbox.payload} JSONB column; the {@code signatures} map carries the ADR-20 producer
 * HMAC ({@code {"hmac_sha256": "<base64url>"}}).
 *
 * <p>{@code @Value} + {@code @Builder} + {@code @Jacksonized} produce an immutable record with a
 * builder factory. {@code @JsonInclude(NON_NULL)} omits null fields on the wire.
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CartMergedEvent {

  /** Snowflake id of the outbox row — the idempotency key for downstream consumers. */
  Long eventId;

  /** Aggregate root name ({@code "Cart"}). */
  String aggregateType;

  /** Snowflake id of the target (user-bound) cart. */
  Long aggregateId;

  /** Event timestamp (UTC). */
  Instant occurredAt;

  String guestCartId;
  String userId;
  Long sourceCartUuid;
  Long targetCartUuid;
  int mergedLinesCount;
  Instant mergedAt;
  String tenantId;

  /** ADR-20 producer HMAC map. */
  Map<String, String> signatures;
}
