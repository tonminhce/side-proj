package vn.vnpt.checkout.infrastructure.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.vnpt.checkout.domain.Order;
import vn.vnpt.checkout.domain.OrderStatus;
import vn.vnpt.checkout.domain.ShippingAddress;
import vn.vnpt.checkout.domain.exception.InsufficientStockDomainException;
import vn.vnpt.checkout.domain.snapshot.CartLineSnapshot;
import vn.vnpt.inventory.application.ReserveInventoryCommand;
import vn.vnpt.inventory.application.ReserveInventoryUseCase;
import vn.vnpt.inventory.domain.InventoryReservation;
import vn.vnpt.inventory.domain.Region;
import vn.vnpt.inventory.domain.ReservationStatus;
import vn.vnpt.inventory.domain.exception.InsufficientStockException;

/**
 * Story 2.5 / FR-22 — the inventory port adapter's boundary contract: wraps the inventory
 * {@link InsufficientStockException} into {@link InsufficientStockDomainException} so the
 * orchestrator stays free of any {@code vn.vnpt.inventory..} import; passes the idempotency key
 * through unchanged; resolves the Region from the address.
 */
@ExtendWith(MockitoExtension.class)
class JavaDirectInventoryReservationAdapterTest {

  @Mock ReserveInventoryUseCase reserveInventoryUseCase;

  private JavaDirectInventoryReservationAdapter adapter;

  @BeforeEach
  void setUp() {
    RegionResolver regionResolver = new RegionResolver();
    adapter = new JavaDirectInventoryReservationAdapter(reserveInventoryUseCase, regionResolver);
  }

  @Test
  void reserve_successful_returnsResultAndPassesIdempotencyKeyUnchanged() {
    when(reserveInventoryUseCase.reserve(any(ReserveInventoryCommand.class)))
        .thenReturn(stubReservation());

    JavaDirectInventoryReservationAdapter.Result result =
        adapter.reserve(order(), List.of(line(1001L, 2L)), "42:stock.reserve");

    assertThat(result.reservationUuid()).isEqualTo(7L);
    assertThat(result.warehouseId()).isEqualTo(99L);

    ArgumentCaptor<ReserveInventoryCommand> cmdCaptor =
        ArgumentCaptor.forClass(ReserveInventoryCommand.class);
    verify(reserveInventoryUseCase).reserve(cmdCaptor.capture());
    ReserveInventoryCommand cmd = cmdCaptor.getValue();
    assertThat(cmd.sagaStepId()).isEqualTo("42:stock.reserve");
    assertThat(cmd.orderUuid()).isEqualTo(42L);
    assertThat(cmd.variantId()).isEqualTo(1001L);
    assertThat(cmd.quantity()).isEqualTo(2L);
    assertThat(cmd.shippingRegion()).isEqualTo(Region.SOUTH);
  }

  /** AC #6 — InsufficientStockException is wrapped, not leaked across the boundary. */
  @Test
  void reserve_inventoryThrowsInsufficientStock_wrappedAsInsufficientStockDomainException() {
    when(reserveInventoryUseCase.reserve(any(ReserveInventoryCommand.class)))
        .thenThrow(new InsufficientStockException(1001L, 99L, 2L, 0L));

    assertThatThrownBy(() -> adapter.reserve(order(), List.of(line(1001L, 2L)), "k"))
        .isInstanceOf(InsufficientStockDomainException.class)
        .hasMessageContaining("Insufficient stock");
  }

  @Test
  void reserve_emptyCartLines_failsClosed() {
    assertThatThrownBy(() -> adapter.reserve(order(), List.of(), "k"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cartLines are required");
  }

  @Test
  void reserve_nullCartLines_failsClosed() {
    assertThatThrownBy(() -> adapter.reserve(order(), null, "k"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void reserve_unresolvableRegion_failsClosed() {
    Order o = Order.builder()
        .tenantId("default").cartUuid(22L).checkoutUuid(11L).status(OrderStatus.CREATED)
        .shippingAddress(ShippingAddress.builder()
            .recipientName("A").phone("0").addressLine1("a")
            .city("Mendoza").province("Mendoza").country("AR").build())
        .build();
    o.setUuid(42L);

    assertThatThrownBy(() -> adapter.reserve(o, List.of(line(1001L, 1L)), "k"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("region");
  }

  private Order order() {
    Order o = Order.builder()
        .tenantId("default").cartUuid(22L).checkoutUuid(11L)
        .paymentIntentId("pi_x").status(OrderStatus.CREATED).version(0L)
        .shippingAddress(ShippingAddress.builder()
            .recipientName("A").phone("0").addressLine1("a")
            .city("HCM").province("HCM").country("VN").build())
        .build();
    o.setUuid(42L);
    return o;
  }

  private static CartLineSnapshot line(Long variantId, Long quantity) {
    return CartLineSnapshot.builder()
        .variantId(variantId).quantity(quantity.intValue()).unitPriceMinor(50_000L).build();
  }

  private static InventoryReservation stubReservation() {
    InventoryReservation r = InventoryReservation.builder()
        .variantId(1001L).warehouseId(99L).quantity(2L)
        .status(ReservationStatus.ACTIVE).tenantId("default")
        .sagaStepId("ignored").orderUuid(42L)
        .expiresAt(Instant.now().plusSeconds(900))
        .build();
    r.setUuid(7L);
    return r;
  }
}