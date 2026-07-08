package vn.vnpt.order.application.usecase;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.order.application.port.AppendOrderTransitionCommand;
import vn.vnpt.order.domain.OrderState;
import vn.vnpt.order.domain.exception.OrderEditWindowClosedException;
import vn.vnpt.order.domain.exception.OrderTerminalStateException;
import vn.vnpt.order.domain.exception.OrderVersionMismatchException;
import vn.vnpt.order.infrastructure.entity.OrderPriceSnapshot;
import vn.vnpt.order.infrastructure.entity.OrderStateTransition;
import vn.vnpt.order.infrastructure.repository.OrderPriceSnapshotRepository;
import vn.vnpt.order.infrastructure.repository.OrderStateTransitionRepository;

/**
 * Amend an order's shipping address within the 30-minute edit window — Story 4.4 / FR-34.
 * Optimistic concurrency: the client passes the current version (transition count); a mismatch
 * throws {@link OrderVersionMismatchException} (HTTP 412).
 */
@Service
@Transactional
public class AmendOrderAddressUseCase {

  private static final Duration EDIT_WINDOW = Duration.ofMinutes(30);
  private static final Set<OrderState> TERMINAL = Set.of(
      OrderState.CANCELLED, OrderState.SHIPPED, OrderState.DELIVERED);

  private final OrderStateTransitionRepository transitionRepository;
  private final OrderPriceSnapshotRepository priceSnapshotRepository;
  private final AppendOrderTransitionUseCase appendUseCase;
  private final Clock clock;
  private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

  public AmendOrderAddressUseCase(
      OrderStateTransitionRepository transitionRepository,
      OrderPriceSnapshotRepository priceSnapshotRepository,
      AppendOrderTransitionUseCase appendUseCase,
      Clock clock,
      org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
    this.transitionRepository = transitionRepository;
    this.priceSnapshotRepository = priceSnapshotRepository;
    this.appendUseCase = appendUseCase;
    this.clock = clock;
    this.jdbcTemplate = jdbcTemplate;
  }

  public long execute(long orderUuid, long expectedVersion, String newAddressJson) {
    // Genesis transition (PLACED) is the source of truth for the edit window.
    OrderStateTransition genesis = transitionRepository
        .findFirstByOrderUuidOrderByIdAsc(orderUuid)
        .orElseThrow(() -> new IllegalStateException("Order not found: orderUuid=" + orderUuid));
    Instant closesAt = genesis.getCreatedAt().toInstant(java.time.ZoneOffset.UTC).plus(EDIT_WINDOW);
    if (Instant.now(clock).isAfter(closesAt)) {
      throw new OrderEditWindowClosedException(orderUuid, closesAt);
    }

    // Current state — terminal check.
    OrderState currentState = OrderState.valueOf(
        transitionRepository.findFirstByOrderUuidOrderByIdDesc(orderUuid)
            .orElseThrow().getToState());
    if (TERMINAL.contains(currentState)) {
      throw new OrderTerminalStateException(orderUuid, currentState);
    }

    // Optimistic concurrency: version is the count of transitions.
    long currentVersion = transitionRepository.findByOrderUuidOrderByIdAsc(orderUuid).size();
    if (currentVersion != expectedVersion) {
      throw new OrderVersionMismatchException(orderUuid, expectedVersion, currentVersion);
    }

    // Update the snapshot's address_json (FR-31: only the address column is mutable, not the
    // price data). Use a native SQL UPDATE to cast the String to jsonb (Hibernate 7 + Postgres
    // jsonb type doesn't auto-cast from String; an @JdbcTypeCode(SqlTypes.JSON) alternative
    // exists but the explicit CAST is the minimal-code path).
    OrderPriceSnapshot snapshot = priceSnapshotRepository.findById(orderUuid)
        .orElseThrow(() -> new IllegalStateException("Snapshot missing: orderUuid=" + orderUuid));
    // Native UPDATE: cast the String to jsonb explicitly (Hibernate 7 doesn't auto-cast).
    // We deliberately do NOT call priceSnapshotRepository.save(snapshot) — the JPA merge
    // re-writes the jsonb column with the String value and triggers the type-mismatch error.
    jdbcTemplate.update(
        "UPDATE order_price_snapshot SET address_json = CAST(? AS jsonb) WHERE order_uuid = ?",
        newAddressJson, orderUuid);

    // Append the AMENDED transition (the existing appender writes to the outbox + HMAC signs).
    return appendUseCase.execute(new AppendOrderTransitionCommand(
        orderUuid, OrderState.AMENDED, "order.amended", snapshot));
  }
}