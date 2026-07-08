package vn.vnpt.order.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import vn.vnpt.order.infrastructure.entity.OrderStateTransition;
import vn.vnpt.order.infrastructure.repository.OrderStateTransitionRepository;

/**
 * Tests for the timeline use case — Story 4.3 / FR-33.
 */
class GetOrderTimelineUseCaseTest {

  private OrderStateTransitionRepository repo;
  private GetOrderTimelineUseCase useCase;

  @BeforeEach
  void setUp() {
    repo = Mockito.mock(OrderStateTransitionRepository.class);
    useCase = new GetOrderTimelineUseCase(repo);
  }

  @Test
  void execute_returnsEmptyTimelineForUnknownOrder() {
    Mockito.when(repo.findByOrderUuidOrderByIdAsc(99L)).thenReturn(List.of());

    var response = useCase.execute(99L);

    assertThat(response.orderUuid()).isEqualTo(99L);
    assertThat(response.timeline()).isEmpty();
  }

  @Test
  void execute_returnsSingleEntryForGenesisTransition() {
    OrderStateTransition t = OrderStateTransition.builder()
        .id(1L).orderUuid(42L).fromState(null).toState("PLACED")
        .sagaStep("order.placed").eventId(100L)
        .createdAt(LocalDateTime.of(2026, 7, 8, 1, 0, 0))
        .build();
    Mockito.when(repo.findByOrderUuidOrderByIdAsc(42L)).thenReturn(List.of(t));

    var response = useCase.execute(42L);

    assertThat(response.timeline()).hasSize(1);
    assertThat(response.timeline().get(0).state()).isEqualTo("PLACED");
    assertThat(response.timeline().get(0).timestamp())
        .isEqualTo(t.getCreatedAt().toInstant(ZoneOffset.UTC));
  }

  @Test
  void execute_returnsEntriesInChronologicalOrder() {
    OrderStateTransition t1 = OrderStateTransition.builder()
        .id(1L).orderUuid(42L).fromState(null).toState("PLACED")
        .sagaStep("order.placed").eventId(100L)
        .createdAt(LocalDateTime.of(2026, 7, 8, 1, 0, 0))
        .build();
    OrderStateTransition t2 = OrderStateTransition.builder()
        .id(2L).orderUuid(42L).fromState("PLACED").toState("PAID")
        .sagaStep("payment.captured").eventId(200L)
        .createdAt(LocalDateTime.of(2026, 7, 8, 1, 5, 0))
        .build();
    OrderStateTransition t3 = OrderStateTransition.builder()
        .id(3L).orderUuid(42L).fromState("PAID").toState("PACKED")
        .sagaStep("order.packed").eventId(300L)
        .createdAt(LocalDateTime.of(2026, 7, 8, 1, 20, 0))
        .build();
    Mockito.when(repo.findByOrderUuidOrderByIdAsc(42L)).thenReturn(List.of(t1, t2, t3));

    var response = useCase.execute(42L);

    assertThat(response.timeline()).hasSize(3);
    assertThat(response.timeline().get(0).state()).isEqualTo("PLACED");
    assertThat(response.timeline().get(1).state()).isEqualTo("PAID");
    assertThat(response.timeline().get(2).state()).isEqualTo("PACKED");
  }

  @Test
  void execute_mapsTimestampsToInstantUTC() {
    OrderStateTransition t = OrderStateTransition.builder()
        .id(1L).orderUuid(1L).fromState(null).toState("PLACED")
        .sagaStep("order.placed").eventId(1L)
        .createdAt(LocalDateTime.of(2026, 7, 8, 12, 0, 0))
        .build();
    Mockito.when(repo.findByOrderUuidOrderByIdAsc(1L)).thenReturn(List.of(t));

    var response = useCase.execute(1L);

    assertThat(response.timeline().get(0).timestamp().toString()).isEqualTo("2026-07-08T12:00:00Z");
  }
}