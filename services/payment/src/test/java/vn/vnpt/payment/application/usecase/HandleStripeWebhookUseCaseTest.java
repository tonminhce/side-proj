package vn.vnpt.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
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
import vn.vnpt.payment.application.port.StripeWebhookHandler.Outcome;
import vn.vnpt.payment.application.port.WebhookDedupPort;
import vn.vnpt.payment.application.port.WebhookDedupPort.AppendOutcome;
import vn.vnpt.payment.application.port.WebhookDeliveryLogPort;
import vn.vnpt.payment.application.webhook.StripeWebhookEvent;

/**
 * Wires the use case against mocked ports — Story 3.2 / AC #5, #8, #9. Asserts the canonical
 * existsBy/append pattern: first delivery inserts the dedup row AND records the side-effect;
 * duplicates skip both.
 */
@ExtendWith(MockitoExtension.class)
class HandleStripeWebhookUseCaseTest {

  private static final LocalDateTime FIXED_TS = LocalDateTime.of(2026, Month.JULY, 7, 10, 0, 0);

  @Mock WebhookDedupPort dedupPort;
  @Mock WebhookDeliveryLogPort deliveryLogPort;

  @Test
  void execute_firstDelivery_insertsDedupAndRecordsSideEffect() {
    when(dedupPort.append(anyString(), anyString(), anyBoolean(), any()))
        .thenReturn(new AppendOutcome(true, FIXED_TS));
    HandleStripeWebhookUseCase useCase = new HandleStripeWebhookUseCase(dedupPort, deliveryLogPort);

    Outcome outcome = useCase.execute(event("evt_abc", "payment_intent.succeeded"));

    assertThat(outcome.inserted()).isTrue();
    assertThat(outcome.eventId()).isEqualTo("evt_abc");
    verify(deliveryLogPort, times(1)).record("evt_abc", "payment_intent.succeeded", "delivery-handled");
  }

  @Test
  void execute_duplicateDelivery_skipsDedupAndSideEffect() {
    when(dedupPort.append(anyString(), anyString(), anyBoolean(), any()))
        .thenReturn(new AppendOutcome(false, FIXED_TS));
    HandleStripeWebhookUseCase useCase = new HandleStripeWebhookUseCase(dedupPort, deliveryLogPort);

    Outcome outcome = useCase.execute(event("evt_abc", "payment_intent.succeeded"));

    assertThat(outcome.inserted()).isFalse();
    assertThat(outcome.eventId()).isEqualTo("evt_abc");
    verify(deliveryLogPort, never()).record(anyString(), anyString(), anyString());
  }

  @Test
  void execute_differentEventTypesSameId_returnsInsertedFalse() {
    // Paranoid: Stripe guarantees same-event-same-type, but the dedup contract must not depend
    // on that. The PK is event.id alone; if ON CONFLICT fires (because the row already exists),
    // we skip side-effects regardless of the new event_type the payload carried.
    when(dedupPort.append(anyString(), anyString(), anyBoolean(), any()))
        .thenReturn(new AppendOutcome(false, FIXED_TS));
    HandleStripeWebhookUseCase useCase = new HandleStripeWebhookUseCase(dedupPort, deliveryLogPort);

    Outcome outcome = useCase.execute(event("evt_abc", "charge.refunded"));

    assertThat(outcome.inserted()).isFalse();
    verify(deliveryLogPort, never()).record(anyString(), anyString(), anyString());
  }

  @Test
  void execute_nullEvent_throwsIAE() {
    HandleStripeWebhookUseCase useCase = new HandleStripeWebhookUseCase(dedupPort, deliveryLogPort);

    assertThatThrownBy(() -> useCase.execute(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("StripeWebhookEvent must not be null");
    verify(dedupPort, never()).append(anyString(), anyString(), anyBoolean(), any());
  }

  private StripeWebhookEvent event(String id, String type) {
    return new StripeWebhookEvent(id, type, false, NullNode.getInstance(), 1700000000L);
  }
}