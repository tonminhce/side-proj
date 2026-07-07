package vn.vnpt.payment.application.webhook;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

/**
 * Minimal Stripe webhook payload shape (FR-26, ADR-21). The dedup contract needs only {@code id}
 * + {@code type} + {@code livemode}; {@code data} is captured for future Story 3.5 saga wiring.
 *
 * <p>Validation at the trust boundary (AC #7): {@code id} MUST be non-null, non-blank, 1..128
 * chars — without it, the {@code INSERT ... ON CONFLICT DO NOTHING} would treat the dedup key as
 * NULL and Postgres's unique-constraint behavior on NULL (NULLS DISTINCT by default) defeats dedup.
 */
public record StripeWebhookEvent(
    @JsonProperty("id") String id,
    @JsonProperty("type") String type,
    @JsonProperty("livemode") boolean livemode,
    @JsonProperty("data") JsonNode data,
    @JsonProperty("created") long created) {

  public StripeWebhookEvent {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("Stripe webhook event.id must not be null or blank");
    }
    if (id.length() > 128) {
      throw new IllegalArgumentException(
          "Stripe webhook event.id length must be 1..128 (got " + id.length() + ")");
    }
    if (type == null || type.isBlank()) {
      throw new IllegalArgumentException("Stripe webhook event.type must not be null or blank");
    }
  }
}