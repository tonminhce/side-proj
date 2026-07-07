package vn.vnpt.checkout.domain.snapshot;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Cart-line snapshot — Story 2.3 / FR-19, ADR-07; Story 2.4 adds {@code unitPriceMinor}.
 *
 * <p>BFF-mediated cart snapshot captured at checkout-start time (ponytail: simpler than a synchronous
 * {@code CartService} HTTP call — no Resilience4j circuit breaker; the BFF reads the cart and passes
 * the snapshot to {@code POST /api/checkouts/start}). The saga (Story 2.5) can switch to an async
 * {@code cart.checked_out} event-driven sync if the snapshot drifts.
 *
 * <p>{@code sellerId} is intentionally nullable (ADR-07 B2C v1; marketplace v2 placeholder).
 *
 * <p>{@code unitPriceMinor} (Story 2.4 / FR-20) — Long minor units of the variant's unit price.
 * Stripe {@code amount} for VND is the đồng value (VND has no fractional unit). Never BigDecimal
 * or double — see architecture.md:457. Pricing service (FR-65/67) is an Epic 5 stub.
 */
@Value
@Builder
@Jacksonized
public class CartLineSnapshot {

  Long variantId;
  String sellerId;
  Integer quantity;
  Long unitPriceMinor;
}