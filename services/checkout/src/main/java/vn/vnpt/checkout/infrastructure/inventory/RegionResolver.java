package vn.vnpt.checkout.infrastructure.inventory;

import org.springframework.stereotype.Component;
import vn.vnpt.checkout.domain.ShippingAddress;
import vn.vnpt.inventory.domain.Region;

/**
 * Shipping-address → {@link Region} stub — Story 2.5 (FR-22).
 *
 * <p>// ponytail: stub region picker; Story 2.5 fails closed when address doesn't resolve. The
 * inventory service requires exactly one of {@code warehouseId} / {@code shippingRegion} on its
 * {@code ReserveInventoryCommand}; a centralized region mapping is deferred until the order
 * module splits (Epic 4) so the saga can call a {@code PricingService} / {@code CatalogService}
 * region lookup. For v1 we resolve {@code NORTH} / {@code SOUTH} / {@code CENTRAL} from the
 * address's province; addresses outside the resolver return {@code null} and the inventory call
 * fails with {@code IllegalArgumentException("warehouseId or shippingRegion required")} — the
 * orchestrator's catch block then marks the order FAILED.
 */
@Component
public class RegionResolver {

  /**
   * @return the inventory {@link Region}, or {@code null} if the address doesn't resolve.
   */
  public Region resolve(ShippingAddress address) {
    if (address == null || address.getProvince() == null) {
      return null;
    }
    String p = address.getProvince().trim().toLowerCase();
    if (p.contains("hà nội") || p.contains("ha noi") || p.equals("hn")) {
      return Region.NORTH;
    }
    if (p.contains("hồ chí minh") || p.contains("ho chi minh") || p.equals("hcm")) {
      return Region.SOUTH;
    }
    if (p.contains("đà nẵng") || p.contains("da nang") || p.contains("huế") || p.contains("hue")) {
      return Region.CENTRAL;
    }
    return null;
  }
}