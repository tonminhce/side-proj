package vn.vnpt.payment.application.usecase;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import vn.vnpt.payment.application.event.PaymentCapturedEvent;
import vn.vnpt.payment.application.event.PaymentRefundedEvent;
import vn.vnpt.payment.application.port.PaymentOutboxPublisher;
import vn.vnpt.payment.application.port.StripeWebhookHandler;
import vn.vnpt.payment.application.port.WebhookDedupPort;
import vn.vnpt.payment.application.port.WebhookDeliveryLogPort;
import vn.vnpt.payment.application.webhook.StripeWebhookEvent;

/**
 * Handle a Stripe webhook delivery — FR-26 + ADR-21 + R-03. Dedupe on Stripe's {@code event.id},
 * skip side-effects on duplicates, record a placeholder {@link WebhookDeliveryLogPort} entry on first
 * delivery.
 *
 * <p>Story 3.5 follow-up / FR-28: after the dedup path passes, switch on {@code event.type()} and
 * emit a cross-module event via {@link PaymentOutboxPublisher}:
 * <ul>
 *   <li>{@code payment_intent.succeeded} → {@code PaymentCapturedEvent} (consumed by order service's
 *       {@code PaymentCapturedOrderAdvancer} to advance {@code PLACED → PAID}).</li>
 *   <li>{@code charge.refunded} → {@code PaymentRefundedEvent} (no consumer yet; published for
 *       downstream services).</li>
 *   <li>Other types → no-op.</li>
 * </ul>
 *
 * <p>{@code orderUuid} is sourced from {@code data.object.metadata.order_uuid} (set by checkout when
 * it created the PaymentIntent). If metadata is missing, the publish is skipped with a warning
 * (the dedup row + delivery-log row still land — webhook audit trail stays intact).
 */
@Service
@Transactional
@Slf4j
public class HandleStripeWebhookUseCase implements StripeWebhookHandler {

  static final String TYPE_PAYMENT_INTENT_SUCCEEDED = "payment_intent.succeeded";
  static final String TYPE_CHARGE_REFUNDED = "charge.refunded";

  private final WebhookDedupPort dedupPort;
  private final WebhookDeliveryLogPort deliveryLogPort;
  private final PaymentOutboxPublisher outboxPublisher;

  public HandleStripeWebhookUseCase(
      WebhookDedupPort dedupPort,
      WebhookDeliveryLogPort deliveryLogPort,
      PaymentOutboxPublisher outboxPublisher) {
    this.dedupPort = dedupPort;
    this.deliveryLogPort = deliveryLogPort;
    this.outboxPublisher = outboxPublisher;
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
    publishOutboxEvent(event);
    return new Outcome(true, event.id());
  }

  private void publishOutboxEvent(StripeWebhookEvent event) {
    JsonNode dataObj = event.data() == null ? null : event.data().get("object");
    switch (event.type()) {
      case TYPE_PAYMENT_INTENT_SUCCEEDED -> {
        if (dataObj == null) {
          log.warn("Skipping payment.captured: missing data.object on event {}", event.id());
          return;
        }
        Long orderUuid = extractOrderUuid(dataObj);
        String piId = textOrNull(dataObj, "id");
        long amount = dataObj.has("amount") ? dataObj.get("amount").asLong(0L) : 0L;
        String currency = textOrNull(dataObj, "currency");
        if (orderUuid == null || piId == null || currency == null) {
          log.warn("Skipping payment.captured: missing fields on event {} (orderUuid={}, piId={}, currency={})",
              event.id(), orderUuid, piId, currency);
          return;
        }
        outboxPublisher.append(
            "Payment",
            Long.parseLong(stripNonDigits(piId)),
            "payment.captured",
            new PaymentCapturedEvent(orderUuid, piId, amount, currency.toUpperCase(),
                LocalDateTime.now(ZoneOffset.UTC)),
            Map.of());
      }
      case TYPE_CHARGE_REFUNDED -> {
        if (dataObj == null) {
          log.warn("Skipping payment.refunded: missing data.object on event {}", event.id());
          return;
        }
        // For charge.refunded, the original PaymentIntent id is at data.object.payment_intent.
        String piId = textOrNull(dataObj, "payment_intent");
        if (piId == null) {
          log.warn("Skipping payment.refunded: missing data.object.payment_intent on event {}", event.id());
          return;
        }
        long amountRefunded = dataObj.has("amount_refunded") ? dataObj.get("amount_refunded").asLong(0L) : 0L;
        String currency = textOrNull(dataObj, "currency");
        if (currency == null) {
          log.warn("Skipping payment.refunded: missing currency on event {}", event.id());
          return;
        }
        // orderUuid is NOT in the charge payload — set to 0 sentinel; consumers must look up by piId
        // until the Stripe metadata convention is extended to refunds (separate story).
        outboxPublisher.append(
            "Payment",
            Long.parseLong(stripNonDigits(piId)),
            "payment.refunded",
            new PaymentRefundedEvent(0L, piId, amountRefunded, currency.toUpperCase(),
                LocalDateTime.now(ZoneOffset.UTC)),
            Map.of());
      }
      default -> {
        // Other event types: no-op (delivery-log row already recorded).
      }
    }
  }

  /** Reads {@code data.object.metadata.order_uuid}; returns null if missing or non-numeric. */
  private static Long extractOrderUuid(JsonNode dataObj) {
    JsonNode meta = dataObj.get("metadata");
    if (meta == null || !meta.isObject()) {
      return null;
    }
    JsonNode orderUuidNode = meta.get("order_uuid");
    if (orderUuidNode == null || !orderUuidNode.isString() && !orderUuidNode.isNumber()) {
      return null;
    }
    try {
      return orderUuidNode.isNumber() ? orderUuidNode.asLong() : Long.parseLong(orderUuidNode.asString());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String textOrNull(JsonNode parent, String field) {
    JsonNode n = parent.get(field);
    return (n == null || n.isNull()) ? null : n.asText();
  }

  /** Stripe IDs look like {@code pi_3O8...}; strip the prefix so it fits a Snowflake long. */
  private static String stripNonDigits(String s) {
    return s.replaceAll("\\D", "");
  }
}