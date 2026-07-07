package vn.vnpt.payment.application.usecase;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.payment.application.port.StripeWebhookHandler;
import vn.vnpt.payment.application.port.WebhookDedupPort;
import vn.vnpt.payment.application.port.WebhookDeliveryLogPort;
import vn.vnpt.payment.application.webhook.StripeWebhookEvent;

/**
 * Handle a Stripe webhook delivery — FR-26 + ADR-21 + R-03. Dedupe on Stripe's {@code event.id},
 * skip side-effects on duplicates, record a placeholder {@link WebhookDeliveryLogPort} entry on first
 * delivery. Side-effect transactions and the {@code webhook_dedup} insert run in one DB transaction
 * (mirrors {@code AuthorizePaymentUseCase}); a crash between the two re-runs on next delivery
 * because the {@code processed_at} NULL check is the future fix (out of scope for this story).
 */
@Service
@Transactional
@Slf4j
public class HandleStripeWebhookUseCase implements StripeWebhookHandler {

  private final WebhookDedupPort dedupPort;
  private final WebhookDeliveryLogPort deliveryLogPort;

  public HandleStripeWebhookUseCase(
      WebhookDedupPort dedupPort, WebhookDeliveryLogPort deliveryLogPort) {
    this.dedupPort = dedupPort;
    this.deliveryLogPort = deliveryLogPort;
  }

  @Override
  public Outcome execute(StripeWebhookEvent event) {
    if (event == null) {
      throw new IllegalArgumentException("StripeWebhookEvent must not be null");
    }
    WebhookDedupPort.AppendOutcome outcome =
        dedupPort.append(event.id(), event.type(), event.livemode(), LocalDateTime.now(ZoneOffset.UTC));
    if (!outcome.inserted()) {
      log.info("Skipping duplicate webhook event {}", event.id());
      return new Outcome(false, event.id());
    }
    deliveryLogPort.record(event.id(), event.type(), "delivery-handled");
    return new Outcome(true, event.id());
  }
}