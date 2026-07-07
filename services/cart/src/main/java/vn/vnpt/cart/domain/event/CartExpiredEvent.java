package vn.vnpt.cart.domain.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;
import vn.vnpt.cart.domain.CartStatus;

/**
 * {@code cart.expired} event payload — Story 2.2 / FR-18 (auto-expire signal).
 *
 * <p>Emitted by {@code CartAutoExpireSweeperJob} for each cart whose {@code expires_at} has passed
 * AND status is {@code ANONYMOUS} or {@code ACTIVE}. The sweeper transitions the cart to
 * {@code ABANDONED} (terminal) and emits the event in the same transaction (ADR-04 atomicity).
 *
 * <p>{@code previousStatus} is the status BEFORE the transition (captured by {@code ExpireCartUseCase}
 * BEFORE the mutation). Anonymous-cart abandonment vs user-bound-cart abandonment carry different
 * business signals (guest-cookie churn vs authed-user churn).
 *
 * <p>Wire topic: {@code cart.expired}. Aggregate type: {@code "Cart"}.
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CartExpiredEvent {

  /** Snowflake id of the outbox row — the idempotency key for downstream consumers. */
  Long eventId;

  /** Aggregate root name ({@code "Cart"}). */
  String aggregateType;

  /** Snowflake id of the cart aggregate. */
  Long aggregateId;

  /** Event timestamp (UTC). */
  Instant occurredAt;

  /** The cart that was expired. */
  Long cartUuid;

  /** Cookie UUID for anonymous carts (null for user-bound). */
  String guestCartId;

  /** Auth user id for user-bound carts (null for anonymous). */
  String userId;

  /** Status before the sweeper transitioned to {@code ABANDONED}. */
  CartStatus previousStatus;

  /** Number of active lines at expiry time. */
  int expiredLinesCount;

  /** TTL anchor from the {@code carts.expires_at} column. */
  Instant expiresAt;

  /** When the sweeper performed the transition. */
  Instant expiredAt;

  String tenantId;

  /** ADR-20 producer HMAC map. */
  Map<String, String> signatures;
}