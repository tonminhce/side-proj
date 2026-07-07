package vn.vnpt.payment.application.port;

import vn.vnpt.payment.application.webhook.StripeWebhookEvent;

/**
 * Single-method contract for every Stripe webhook handler (Story 3.2 / FR-26 / ADR-21). The
 * in-process implementation lives at {@code application/usecase/HandleStripeWebhookUseCase.java};
 * every future webhook handler (Story 3.3 Elements iframe, Story 3.5 3DS, charge-dispute handlers)
 * reuses this exact dedup pattern.
 *
 * <p>Architecture §6 canonical idempotent consumer pattern: {@code if (processed.existsByEventId(...))
 * { return; } ... processed.save(...)} — mirrored here via the {@link WebhookDedupPort}.
 */
public interface StripeWebhookHandler {

  Outcome execute(StripeWebhookEvent event);

  /**
   * @param inserted true iff this delivery ran the side-effects (dedup row + log row); false iff the
   *     Stripe {@code event.id} was a duplicate and the side-effects were skipped.
   */
  record Outcome(boolean inserted, String eventId) {}
}