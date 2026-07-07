package vn.vnpt.checkout.infrastructure.inventory;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vn.vnpt.checkout.application.port.InventoryReservationPort;
import vn.vnpt.checkout.domain.Order;
import vn.vnpt.checkout.domain.ShippingAddress;
import vn.vnpt.checkout.domain.exception.InsufficientStockDomainException;
import vn.vnpt.checkout.domain.snapshot.CartLineSnapshot;
import vn.vnpt.inventory.application.ReserveInventoryCommand;
import vn.vnpt.inventory.application.ReserveInventoryUseCase;
import vn.vnpt.inventory.domain.InventoryReservation;
import vn.vnpt.inventory.domain.Region;
import vn.vnpt.inventory.domain.exception.InsufficientStockException;

/**
 * Java direct-call adapter for the inventory reservation port — Story 2.5 / FR-22 (ADR-01
 * intra-Modulith).
 *
 * <p>Wraps {@link ReserveInventoryUseCase#reserve(ReserveInventoryCommand)}. The adapter is
 * intentionally thin: builds the inventory command from the saga's port inputs and forwards. The
 * inventory call participates in the surrounding {@code @Transactional} boundary (the inventory
 * service uses {@code @Transactional} too — see {@code ReserveInventoryUseCase.java:31}).
 *
 * <p>ArchUnit boundary test: this package is the only one in checkout that may import
 * {@code vn.vnpt.inventory.application.*}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JavaDirectInventoryReservationAdapter implements InventoryReservationPort {

  private final ReserveInventoryUseCase reserveInventoryUseCase;
  private final RegionResolver regionResolver;

  @Override
  public Result reserve(Long orderUuid, List<LineItem> items, String idempotencyKey) {
    if (items == null || items.isEmpty()) {
      throw new IllegalArgumentException("cartLines are required");
    }
    LineItem first = items.get(0);
    ReserveInventoryCommand cmd = new ReserveInventoryCommand(
        first.variantId(),
        null,
        null,
        first.quantity(),
        idempotencyKey,
        orderUuid,
        null);
    try {
      InventoryReservation reservation = reserveInventoryUseCase.reserve(cmd);
      return new Result(reservation.getUuid(), reservation.getWarehouseId(),
          reservation.getExpiresAt());
    } catch (InsufficientStockException e) {
      throw new InsufficientStockDomainException(e.getMessage());
    }
  }

  /**
   * Convenience overload used by the orchestrator — the orchestrator carries the {@code Order}
   * aggregate, so it can resolve the shipping region from the embedded address.
   */
  public Result reserve(Order order, List<CartLineSnapshot> cartLines, String idempotencyKey) {
    ShippingAddress address = order.getShippingAddress();
    Region region = regionResolver.resolve(address);
    if (region == null) {
      throw new IllegalArgumentException(
          "Cannot resolve shipping region from address; saga fails closed (Story 2.5 stub)");
    }
    if (cartLines == null || cartLines.isEmpty()) {
      throw new IllegalArgumentException("cartLines are required");
    }
    CartLineSnapshot line = cartLines.get(0);
    ReserveInventoryCommand cmd = new ReserveInventoryCommand(
        line.getVariantId(),
        null,
        region,
        line.getQuantity(),
        idempotencyKey,
        order.getUuid(),
        null);
    try {
      InventoryReservation reservation = reserveInventoryUseCase.reserve(cmd);
      return new Result(reservation.getUuid(), reservation.getWarehouseId(),
          reservation.getExpiresAt());
    } catch (InsufficientStockException e) {
      throw new InsufficientStockDomainException(e.getMessage());
    }
  }
}