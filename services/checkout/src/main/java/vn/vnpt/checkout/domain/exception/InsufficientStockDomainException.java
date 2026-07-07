package vn.vnpt.checkout.domain.exception;

/**
 * Domain-side insufficient-stock marker — Story 2.5 / FR-22.
 *
 * <p>The inventory service throws {@code vn.vnpt.inventory.domain.exception.InsufficientStockException}
 * from inside the intra-Modulith adapter call. To keep the orchestrator free of an inventory
 * dependency (ArchUnit boundary test — checkout.application.. must NOT depend on
 * {@code vn.vnpt.inventory..}), the adapter catches the inventory exception and throws this
 * domain-shaped exception instead.
 */
public class InsufficientStockDomainException extends RuntimeException {

  public InsufficientStockDomainException(String message) {
    super(message);
  }
}