package vn.vnpt.inventory.infrastructure.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vn.vnpt.inventory.application.port.OutboxPublisher;
import vn.vnpt.inventory.domain.event.InventoryLifecycleEvent;
import vn.vnpt.inventory.domain.event.LifecyclePhase;

/**
 * Lifecycle event publisher — Story 1.8 / FR-11 (unified {@code inventory.lifecycle} topic).
 *
 * <p>Wraps {@link OutboxPublisher#append} with the dual-publish logic for the Sprint 9
 * migration window: {@link LifecyclePhase#RESERVED} + {@link LifecyclePhase#RELEASED}
 * publish to BOTH the unified {@code inventory.lifecycle} topic AND the legacy
 * {@code inventory.reserved} / {@code inventory.released} topic. The other phases
 * ({@link LifecyclePhase#ALLOCATED}, {@link LifecyclePhase#SHIPPED},
 * {@link LifecyclePhase#ADJUSTED}) publish ONLY to {@code inventory.lifecycle}.
 *
 * <p>Both {@code outbox.append} calls reuse the SAME {@code signatures} map — the HMAC is
 * computed once by the calling use case and applied to both topics. This guarantees that a
 * consumer reading either topic sees an identical signature, and avoids double HMAC
 * computation.
 *
 * <p>The dual-publish is a ONE-SPRINT concession. The ArchUnit rule
 * {@code inventory_lifecycleEventsRouteThroughPublisher} guards against future drift
 * (use cases must NOT call {@code outbox.append(...)} directly for inventory lifecycle
 * events). Sprint 9 Story 9.x cuts the legacy topics.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LifecycleEventPublisher {

  /** Wire topic for new lifecycle events. */
  public static final String LIFECYCLE_TOPIC = "inventory.lifecycle";

  /** Legacy topic retained for the Sprint 9 migration window. */
  public static final String LEGACY_RESERVED_TOPIC = "inventory.reserved";

  /** Legacy topic retained for the Sprint 9 migration window. */
  public static final String LEGACY_RELEASED_TOPIC = "inventory.released";

  private final OutboxPublisher outbox;

  /**
   * Publish a unified lifecycle event. Dual-publishes for {@code RESERVED} / {@code RELEASED}.
   *
   * @param evt the event to publish — {@link InventoryLifecycleEvent#signatures()} is reused
   *     across both topics
   */
  public void publish(InventoryLifecycleEvent evt) {
    outbox.append(
        evt.getAggregateType(),
        evt.getAggregateId(),
        LIFECYCLE_TOPIC,
        evt,
        evt.getSignatures());

    if (evt.getPhase() == LifecyclePhase.RESERVED) {
      // Legacy alias — drop after Sprint 9 (Story 9.x migration complete).
      outbox.append(
          evt.getAggregateType(),
          evt.getAggregateId(),
          LEGACY_RESERVED_TOPIC,
          evt,
          evt.getSignatures());
    } else if (evt.getPhase() == LifecyclePhase.RELEASED) {
      // Legacy alias — drop after Sprint 9.
      outbox.append(
          evt.getAggregateType(),
          evt.getAggregateId(),
          LEGACY_RELEASED_TOPIC,
          evt,
          evt.getSignatures());
    }

    if (log.isDebugEnabled()) {
      log.debug(
          "Lifecycle event published: phase={} aggregateType={} aggregateId={} eventId={}",
          evt.getPhase(),
          evt.getAggregateType(),
          evt.getAggregateId(),
          evt.getEventId());
    }
  }
}