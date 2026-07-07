package vn.vnpt.cart.domain.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * {@code cart.line.added} event payload — Story 2.2 / FR-17 (recommendation signal).
 *
 * <p>Emitted on every successful add/upsert ({@code AddLineUseCase.addLine}) and once per transferred
 * line during a merge ({@code MergeCartUseCase.merge}). The recommendation engine (Story 6.4 / FR-54)
 * subscribes via Modulith outbox bridge and uses the {@code variantId} stream as the real-time signal.
 * Consumers dedupe on {@code eventId} (Snowflake) via the {@code processed_event} table per NFR-IDEM-1.
 *
 * <p>{@code sellerId} is intentionally OMITTED — v1 B2C has {@code sellerId = null} per ADR-07
 * marketplace v2 placeholder; marketplace v2 adds the field as a single backward-compatible
 * addition per ADR-15 strict compat rules.
 *
 * <p>Wire topic: {@code cart.line.added}. Aggregate type: {@code "Cart"} (the line's {@code cartUuid}
 * is a child reference).
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CartLineAddedEvent {

  /** Snowflake id of the outbox row — the idempotency key for downstream consumers. */
  Long eventId;

  /** Aggregate root name ({@code "Cart"}). */
  String aggregateType;

  /** Snowflake id of the cart aggregate. */
  Long aggregateId;

  /** Event timestamp (UTC). */
  Instant occurredAt;

  /** Cart that owns the line. */
  Long cartUuid;

  /** Snowflake id of the line that was added/upserted. */
  Long lineUuid;

  /** Recommendation signal — what the user is interested in. */
  Long variantId;

  /** Line quantity (post-upsert). */
  Integer quantity;

  String tenantId;

  /** ADR-20 producer HMAC map. */
  Map<String, String> signatures;
}