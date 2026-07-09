package vn.vnpt.order.application.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.vnpt.order.application.usecase.AccrueLoyaltyPointsUseCase;
import vn.vnpt.order.application.usecase.AdvanceOrderStateUseCase;
import vn.vnpt.order.application.usecase.AmendOrderAddressUseCase;
import vn.vnpt.order.application.usecase.AppendOrderTransitionUseCase;
import vn.vnpt.order.application.usecase.CancelOrderUseCase;
import vn.vnpt.order.application.usecase.GetLoyaltyAccountUseCase;
import vn.vnpt.order.application.usecase.GetLoyaltyForOrderUseCase;
import vn.vnpt.order.application.usecase.GetOrderTimelineUseCase;
import vn.vnpt.order.infrastructure.entity.OrderPriceSnapshot;
import vn.vnpt.order.infrastructure.repository.OrderPriceSnapshotRepository;
import vn.vnpt.order.infrastructure.repository.OrderStateTransitionRepository;
import vn.vnpt.order.infrastructure.web.OrderEditExceptionHandler;

class OrderControllerAccrueLoyaltyTest {

  private AccrueLoyaltyPointsUseCase accrueUseCase;
  private OrderPriceSnapshotRepository snapshotRepo;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    accrueUseCase = Mockito.mock(AccrueLoyaltyPointsUseCase.class);
    snapshotRepo = Mockito.mock(OrderPriceSnapshotRepository.class);
    OrderController controller = new OrderController(
        Mockito.mock(AppendOrderTransitionUseCase.class),
        Mockito.mock(AdvanceOrderStateUseCase.class),
        Mockito.mock(AmendOrderAddressUseCase.class),
        Mockito.mock(CancelOrderUseCase.class),
        Mockito.mock(GetOrderTimelineUseCase.class),
        Mockito.mock(GetLoyaltyAccountUseCase.class),
        Mockito.mock(GetLoyaltyForOrderUseCase.class),
        accrueUseCase,
        Mockito.mock(OrderStateTransitionRepository.class),
        snapshotRepo);
    mockMvc = MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new OrderEditExceptionHandler())
        .build();
  }

  @Test
  void accrueLoyalty_readsTotalCentsFromSnapshot_notRequest() throws Exception {
    when(snapshotRepo.findById(42L)).thenReturn(Optional.of(
        OrderPriceSnapshot.builder()
            .orderUuid(42L)
            .listPriceCents(10_000L)
            .taxCents(1_000L)
            .shippingCents(500L)
            .totalCents(11_500L)
            .currency("VND")
            .capturedAt(java.time.LocalDateTime.now())
            .build()));
    when(accrueUseCase.execute(anyLong(), anyLong(), anyLong())).thenReturn(115);

    // Request only carries customerId; totalCents comes from snapshot.
    mockMvc.perform(post("/api/orders/42/accrue-loyalty").param("customerId", "99"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orderUuid").value(42))
        .andExpect(jsonPath("$.customerId").value(99))
        .andExpect(jsonPath("$.totalCents").value(11500))
        .andExpect(jsonPath("$.points").value(115));

    // Verify the use case was called with the SNAPSHOT's totalCents, not a request param.
    Mockito.verify(accrueUseCase).execute(42L, 99L, 11_500L);
  }

  @Test
  void accrueLoyalty_unknownOrder_404_viaExceptionHandler() throws Exception {
    when(snapshotRepo.findById(404L)).thenReturn(Optional.empty());

    mockMvc.perform(post("/api/orders/404/accrue-loyalty").param("customerId", "99"))
        .andExpect(status().is4xxClientError());
  }

  @Test
  void accrueLoyalty_missingCustomerId_400() throws Exception {
    when(snapshotRepo.findById(42L)).thenReturn(Optional.of(
        OrderPriceSnapshot.builder().orderUuid(42L).totalCents(11_500L)
            .currency("VND").capturedAt(java.time.LocalDateTime.now()).build()));

    mockMvc.perform(post("/api/orders/42/accrue-loyalty"))
        .andExpect(status().isBadRequest());
  }
}