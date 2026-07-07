package vn.vnpt.checkout.domain.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;
import vn.vnpt.checkout.domain.ShippingAddress;
import vn.vnpt.checkout.domain.snapshot.CartLineSnapshot;

/**
 * {@code checkout.started} event payload — Story 2.3 / FR-19, FR-21.
 *
 * <p>Emitted by {@code StartCheckoutUseCase} on every successful checkout start. Serialized to the
 * {@code outbox.payload} JSONB column; the {@code signatures} map carries the ADR-20 producer HMAC
 * ({@code {"hmac_sha256": "<base64url>"}}).
 *
 * <p>{@code @Value} + {@code @Builder} + {@code @Jacksonized} produce an immutable record with a
 * builder factory. {@code @JsonInclude(NON_NULL)} omits null fields on the wire — {@code sellerId}
 * is intentionally nested under {@code CartLineSnapshot} (B2C v1 has {@code sellerId = null} per
 * ADR-07).
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CheckoutStartedEvent {

  /** Snowflake id of the outbox row — the idempotency key for downstream consumers. */
  Long eventId;

  /** Aggregate root name ({@code "Checkout"}). */
  String aggregateType;

  /** Snowflake id of the checkout. */
  Long aggregateId;

  /** Event timestamp (UTC). */
  Instant occurredAt;

  Long checkoutUuid;
  Long cartUuid;
  String userId;
  String guestCartId;
  String tenantId;
  ShippingAddress shippingAddress;
  List<CartLineSnapshot> cartLines;
  /** Story 2.4 / FR-20 — Stripe {@code pi_...} id (non-secret; may be logged). The
   *  {@code client_secret} is a R-15 / ADR-23 secret and MUST NOT appear in the event payload —
   *  it flows only via the HTTP response to the BFF for the Stripe Elements iframe handoff. */
  String paymentIntentId;

  /** ADR-20 producer HMAC map. */
  Map<String, String> signatures;
}