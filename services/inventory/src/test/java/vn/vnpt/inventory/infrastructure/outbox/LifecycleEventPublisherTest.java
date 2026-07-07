package vn.vnpt.inventory.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.vnpt.inventory.application.port.OutboxPublisher;
import vn.vnpt.inventory.domain.event.InventoryLifecycleEvent;
import vn.vnpt.inventory.domain.event.LifecyclePhase;

/**
 * Pure-JUnit / Mockito test for {@link LifecycleEventPublisher} — Story 1.8 / FR-11.
 *
 * <p>Pins the dual-publish semantics: {@code RESERVED} + {@code RELEASED} publish to BOTH the
 * unified {@code inventory.lifecycle} topic AND the legacy {@code inventory.reserved} /
 * {@code inventory.released} topic. {@code ALLOCATED} / {@code SHIPPED} / {@code ADJUSTED}
 * publish only to the unified topic.
 */
@ExtendWith(MockitoExtension.class)
class LifecycleEventPublisherTest {

  @Mock OutboxPublisher outbox;

  @InjectMocks LifecycleEventPublisher publisher;

  private InventoryLifecycleEvent build(LifecyclePhase phase) {
    return InventoryLifecycleEvent.builder()
        .eventId(1L)
        .aggregateType("InventoryReservation")
        .aggregateId(2L)
        .occurredAt(Instant.parse("2026-07-07T11:30:00Z"))
        .phase(phase)
        .variantId(100L)
        .warehouseId(1001L)
        .quantity(3L)
        .reason(phase.name().toLowerCase())
        .tenantId("default")
        .signatures(Map.of("hmac_sha256", "fake"))
        .build();
  }

  @Test
  void publish_withReservedPhase_publishesToBothLifecycleAndReservedTopic() {
    publisher.publish(build(LifecyclePhase.RESERVED));
    verify(outbox, times(1))
        .append(eq("InventoryReservation"), eq(2L), eq("inventory.lifecycle"), any(), any());
    verify(outbox, times(1))
        .append(eq("InventoryReservation"), eq(2L), eq("inventory.reserved"), any(), any());
  }

  @Test
  void publish_withReleasedPhase_publishesToBothLifecycleAndReleasedTopic() {
    publisher.publish(build(LifecyclePhase.RELEASED));
    verify(outbox, times(1))
        .append(eq("InventoryReservation"), eq(2L), eq("inventory.lifecycle"), any(), any());
    verify(outbox, times(1))
        .append(eq("InventoryReservation"), eq(2L), eq("inventory.released"), any(), any());
  }

  @Test
  void publish_withAllocatedPhase_publishesOnlyToLifecycleTopic() {
    publisher.publish(build(LifecyclePhase.ALLOCATED));
    verify(outbox, times(1))
        .append(eq("InventoryReservation"), eq(2L), eq("inventory.lifecycle"), any(), any());
    verify(outbox, times(0))
        .append(eq("InventoryReservation"), eq(2L), eq("inventory.reserved"), any(), any());
    verify(outbox, times(0))
        .append(eq("InventoryReservation"), eq(2L), eq("inventory.released"), any(), any());
  }

  @Test
  void publish_withShippedPhase_publishesOnlyToLifecycleTopic() {
    publisher.publish(build(LifecyclePhase.SHIPPED));
    verify(outbox, times(1))
        .append(eq("InventoryReservation"), eq(2L), eq("inventory.lifecycle"), any(), any());
  }

  @Test
  void publish_withAdjustedPhase_publishesOnlyToLifecycleTopic() {
    publisher.publish(build(LifecyclePhase.ADJUSTED));
    verify(outbox, times(1))
        .append(eq("InventoryReservation"), eq(2L), eq("inventory.lifecycle"), any(), any());
  }

  /**
   * QA-pass gap — Security invariant: the dual-publish for RESERVED MUST reuse the SAME
   * signatures map on both calls. The JavaDoc states "the HMAC is computed once by the calling
   * use case and applied to both topics" — a regression that recomputes a fresh signature for
   * the legacy publish would produce two events with different signatures, breaking the saga's
   * HMAC verify path. Captures both {@code outbox.append} invocations and asserts the
   * signatures map is the SAME instance (not just equal).
   */
  @Test
  void publish_dualPublishForReservedPhase_usesIdenticalSignatures() {
    InventoryLifecycleEvent evt = build(LifecyclePhase.RESERVED);
    Map<String, String> sharedSigs = evt.getSignatures();
    publisher.publish(evt);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> sigsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(outbox, times(2)).append(any(), any(), any(), any(), sigsCaptor.capture());
    List<Map<String, String>> captured = sigsCaptor.getAllValues();
    assertThat(captured).hasSize(2);
    assertThat(captured.get(0)).isSameAs(sharedSigs);
    assertThat(captured.get(1)).isSameAs(sharedSigs);
    assertThat(captured.get(0)).isSameAs(captured.get(1));
  }

  /**
   * QA-pass gap — same security invariant for RELEASED dual-publish.
   */
  @Test
  void publish_dualPublishForReleasedPhase_usesIdenticalSignatures() {
    InventoryLifecycleEvent evt = build(LifecyclePhase.RELEASED);
    Map<String, String> sharedSigs = evt.getSignatures();
    publisher.publish(evt);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> sigsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(outbox, times(2)).append(any(), any(), any(), any(), sigsCaptor.capture());
    List<Map<String, String>> captured = sigsCaptor.getAllValues();
    assertThat(captured).hasSize(2);
    assertThat(captured.get(0)).isSameAs(sharedSigs);
    assertThat(captured.get(1)).isSameAs(sharedSigs);
  }

  /**
   * QA-pass gap — the publisher must pass the SAME event object instance (not a copy or a
   * different event) to both {@code outbox.append} calls. A regression that swaps the event
   * for a different object on the legacy publish would surface as a wire-format mismatch on
   * the saga's downstream consumer.
   */
  @Test
  void publish_passesTheSameEventObjectInstanceToBothAppends() {
    InventoryLifecycleEvent evt = build(LifecyclePhase.RESERVED);
    publisher.publish(evt);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<InventoryLifecycleEvent> evtCaptor =
        ArgumentCaptor.forClass(InventoryLifecycleEvent.class);
    verify(outbox, times(2)).append(any(), any(), any(), evtCaptor.capture(), any());
    List<InventoryLifecycleEvent> captured = evtCaptor.getAllValues();
    assertThat(captured).hasSize(2);
    assertThat(captured.get(0)).isSameAs(evt);
    assertThat(captured.get(1)).isSameAs(evt);
  }

  /**
   * QA-pass gap — for non-legacy phases (ALLOCATED, SHIPPED, ADJUSTED), the publisher MUST
   * NOT publish to either legacy topic. The existing tests cover ALLOCATED with explicit
   * {@code times(0)}; pin the same contract for SHIPPED + ADJUSTED in one parameterized
   * assertion to prevent future drift.
   */
  @Test
  void publish_nonLegacyPhasesNeverTouchLegacyTopics() {
    for (LifecyclePhase phase :
        new LifecyclePhase[] {LifecyclePhase.ALLOCATED, LifecyclePhase.SHIPPED, LifecyclePhase.ADJUSTED}) {
      // Reset interactions by using a fresh mock per phase.
      org.mockito.Mockito.reset(outbox);
      publisher.publish(build(phase));
      verify(outbox, times(0))
          .append(any(), any(), eq("inventory.reserved"), any(), any());
      verify(outbox, times(0))
          .append(any(), any(), eq("inventory.released"), any(), any());
      verify(outbox, times(1))
          .append(any(), any(), eq("inventory.lifecycle"), any(), any());
    }
  }

  /**
   * QA-pass gap — defensive: the publisher must tolerate a {@code null} signatures map (the
   * {@code InventoryLifecycleEvent.signatures} field is nullable by {@code @JsonInclude
   * (NON_NULL)} contract). The publisher MUST still emit; the outbox row carries a NULL
   * signatures column (downstream HMAC verify will be skipped per ADR-20's consumer-side
   * "skip on missing signature" branch — already covered in Story 1.5's HmacFailureTest).
   */
  @Test
  void publish_withNullSignatures_stillEmitsLifecycleRow() {
    InventoryLifecycleEvent evt =
        InventoryLifecycleEvent.builder()
            .eventId(7L)
            .aggregateType("InventoryReservation")
            .aggregateId(77L)
            .occurredAt(Instant.parse("2026-07-07T00:00:00Z"))
            .phase(LifecyclePhase.RESERVED)
            .variantId(100L)
            .warehouseId(1001L)
            .quantity(1L)
            .reason("reserve")
            .tenantId("default")
            // .signatures(null) — explicit null
            .build();
    doAnswer(inv -> null)
        .when(outbox)
        .append(any(), any(), any(), any(), any());
    publisher.publish(evt);
    verify(outbox, times(2)).append(any(), any(), any(), any(), any());
  }
}