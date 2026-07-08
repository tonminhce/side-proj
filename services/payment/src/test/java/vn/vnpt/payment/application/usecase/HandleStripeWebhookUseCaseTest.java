package vn.vnpt.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.Month;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;
import vn.vnpt.payment.application.event.PaymentCapturedEvent;
import vn.vnpt.payment.application.event.PaymentRefundedEvent;
import vn.vnpt.payment.application.port.PaymentOutboxPublisher;
import vn.vnpt.payment.application.port.StripeWebhookHandler.Outcome;
import vn.vnpt.payment.application.port.WebhookDedupPort;
import vn.vnpt.payment.application.port.WebhookDedupPort.AppendOutcome;
import vn.vnpt.payment.application.port.WebhookDeliveryLogPort;
import vn.vnpt.payment.application.webhook.StripeWebhookEvent;

/**
 * Wires the use case against mocked ports — Story 3.2 / AC #5, #8, #9 +
 * Story 3.5 follow-up / FR-28 (payment.captured / payment.refunded outbox publishing).
 *
 * <p>Asserts the canonical existsBy/append pattern: first delivery inserts the dedup row,
 * records the delivery-log entry, and (for known event types) publishes to the outbox;
 * duplicates skip all three.
 */
@ExtendWith(MockitoExtension.class)
class HandleStripeWebhookUseCaseTest {

  private static final LocalDateTime FIXED_TS = LocalDateTime.of(2026, Month.JULY, 7, 10, 0, 0);
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Mock WebhookDedupPort dedupPort;
  @Mock WebhookDeliveryLogPort deliveryLogPort;
  @Mock PaymentOutboxPublisher outboxPublisher;

  // ── existing Story 3.2 tests (now with the outbox publisher wired in) ──

  @Test
  void execute_firstDelivery_insertsDedupAndRecordsSideEffect() {
    when(dedupPort.append(anyString(), anyString(), anyBoolean(), any()))
        .thenReturn(new AppendOutcome(true, FIXED_TS));
    HandleStripeWebhookUseCase useCase = newUseCase();

    Outcome outcome = useCase.execute(event("evt_abc", "payment_intent.succeeded"));

    assertThat(outcome.inserted()).isTrue();
    assertThat(outcome.eventId()).isEqualTo("evt_abc");
    verify(deliveryLogPort, times(1)).record("evt_abc", "payment_intent.succeeded", "delivery-handled");
  }

  @Test
  void execute_duplicateDelivery_skipsDedupAndSideEffect() {
    when(dedupPort.append(anyString(), anyString(), anyBoolean(), any()))
        .thenReturn(new AppendOutcome(false, FIXED_TS));
    HandleStripeWebhookUseCase useCase = newUseCase();

    Outcome outcome = useCase.execute(event("evt_abc", "payment_intent.succeeded"));

    assertThat(outcome.inserted()).isFalse();
    assertThat(outcome.eventId()).isEqualTo("evt_abc");
    verify(deliveryLogPort, never()).record(anyString(), anyString(), anyString());
    verify(outboxPublisher, never()).append(anyString(), anyLong(), anyString(), any(), any());
  }

  @Test
  void execute_differentEventTypesSameId_returnsInsertedFalse() {
    when(dedupPort.append(anyString(), anyString(), anyBoolean(), any()))
        .thenReturn(new AppendOutcome(false, FIXED_TS));
    HandleStripeWebhookUseCase useCase = newUseCase();

    Outcome outcome = useCase.execute(event("evt_abc", "charge.refunded"));

    assertThat(outcome.inserted()).isFalse();
    verify(deliveryLogPort, never()).record(anyString(), anyString(), anyString());
    verify(outboxPublisher, never()).append(anyString(), anyLong(), anyString(), any(), any());
  }

  @Test
  void execute_nullEvent_throwsIAE() {
    HandleStripeWebhookUseCase useCase = newUseCase();

    assertThatThrownBy(() -> useCase.execute(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("StripeWebhookEvent must not be null");
    verify(dedupPort, never()).append(anyString(), anyString(), anyBoolean(), any());
    verify(outboxPublisher, never()).append(anyString(), anyLong(), anyString(), any(), any());
  }

  // ── new Story 3.5 follow-up / FR-28 tests ──

  @Test
  void execute_paymentIntentSucceeded_publishesPaymentCapturedToOutbox() {
    when(dedupPort.append(anyString(), anyString(), anyBoolean(), any()))
        .thenReturn(new AppendOutcome(true, FIXED_TS));
    HandleStripeWebhookUseCase useCase = newUseCase();

    // Stripe payload shape: data.object.id=pi_X, amount=1990000, currency=usd, metadata.order_uuid=42
    ObjectNode data = MAPPER.createObjectNode();
    ObjectNode obj = data.putObject("object");
    obj.put("id", "pi_123456789");
    obj.put("amount", 1990000);
    obj.put("currency", "usd");
    obj.putObject("metadata").put("order_uuid", "42");

    StripeWebhookEvent event = new StripeWebhookEvent(
        "evt_ok", "payment_intent.succeeded", false, data, 1700000000L);
    useCase.execute(event);

    verify(outboxPublisher, times(1)).append(
        eq("Payment"), eq(123456789L), eq("payment.captured"),
        any(PaymentCapturedEvent.class), any());
  }

  @Test
  void execute_chargeRefunded_publishesPaymentRefundedToOutbox() {
    when(dedupPort.append(anyString(), anyString(), anyBoolean(), any()))
        .thenReturn(new AppendOutcome(true, FIXED_TS));
    HandleStripeWebhookUseCase useCase = newUseCase();

    // Stripe charge.refunded payload: data.object.payment_intent=pi_X, amount_refunded=50000, currency=vnd
    ObjectNode data = MAPPER.createObjectNode();
    ObjectNode obj = data.putObject("object");
    obj.put("payment_intent", "pi_987654321");
    obj.put("amount_refunded", 50000);
    obj.put("currency", "vnd");

    StripeWebhookEvent event = new StripeWebhookEvent(
        "evt_refund", "charge.refunded", false, data, 1700000000L);
    useCase.execute(event);

    verify(outboxPublisher, times(1)).append(
        eq("Payment"), eq(987654321L), eq("payment.refunded"),
        any(PaymentRefundedEvent.class), any());
  }

  @Test
  void execute_unknownEventType_doesNotPublish() {
    when(dedupPort.append(anyString(), anyString(), anyBoolean(), any()))
        .thenReturn(new AppendOutcome(true, FIXED_TS));
    HandleStripeWebhookUseCase useCase = newUseCase();

    // setup_intent.succeeded is not in the type switch → no-op
    StripeWebhookEvent event = new StripeWebhookEvent(
        "evt_setup", "setup_intent.succeeded", false, NullNode.getInstance(), 1700000000L);
    useCase.execute(event);

    verify(outboxPublisher, never()).append(anyString(), anyLong(), anyString(), any(), any());
    // delivery-log row is still recorded
    verify(deliveryLogPort, times(1)).record("evt_setup", "setup_intent.succeeded", "delivery-handled");
  }

  @Test
  void execute_paymentIntentSucceeded_missingMetadataOrderUuid_skipsPublish() {
    when(dedupPort.append(anyString(), anyString(), anyBoolean(), any()))
        .thenReturn(new AppendOutcome(true, FIXED_TS));
    HandleStripeWebhookUseCase useCase = newUseCase();

    // metadata absent → skip the publish (audit row still lands)
    ObjectNode data = MAPPER.createObjectNode();
    ObjectNode obj = data.putObject("object");
    obj.put("id", "pi_111222333");
    obj.put("amount", 1000);
    obj.put("currency", "usd");
    // no metadata field

    StripeWebhookEvent event = new StripeWebhookEvent(
        "evt_no_meta", "payment_intent.succeeded", false, data, 1700000000L);
    useCase.execute(event);

    verify(outboxPublisher, never()).append(anyString(), anyLong(), anyString(), any(), any());
    verify(deliveryLogPort, times(1)).record("evt_no_meta", "payment_intent.succeeded", "delivery-handled");
  }

  @Test
  void execute_chargeRefunded_missingPaymentIntent_skipsPublish() {
    when(dedupPort.append(anyString(), anyString(), anyBoolean(), any()))
        .thenReturn(new AppendOutcome(true, FIXED_TS));
    HandleStripeWebhookUseCase useCase = newUseCase();

    // data.object present but no payment_intent field → skip
    ObjectNode data = MAPPER.createObjectNode();
    data.putObject("object").put("amount_refunded", 5000);

    StripeWebhookEvent event = new StripeWebhookEvent(
        "evt_bad_refund", "charge.refunded", false, data, 1700000000L);
    useCase.execute(event);

    verify(outboxPublisher, never()).append(anyString(), anyLong(), anyString(), any(), any());
  }

  private HandleStripeWebhookUseCase newUseCase() {
    return new HandleStripeWebhookUseCase(dedupPort, deliveryLogPort, outboxPublisher);
  }

  private StripeWebhookEvent event(String id, String type) {
    return new StripeWebhookEvent(id, type, false, NullNode.getInstance(), 1700000000L);
  }
}