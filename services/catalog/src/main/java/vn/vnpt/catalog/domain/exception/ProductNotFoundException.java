package vn.vnpt.catalog.domain.exception;

/**
 * Thrown when a {@code Product} aggregate is not found by Snowflake id (Story 1.3 / AC #10).
 *
 * <p>First domain exception in the catalog module. Carries the offending id so the global
 * exception handler (Story 10.1) can produce a stable error response.
 */
public class ProductNotFoundException extends RuntimeException {

  private final Long productUuid;

  public ProductNotFoundException(Long productUuid) {
    super("Product not found: uuid=" + productUuid);
    this.productUuid = productUuid;
  }

  public Long getProductUuid() {
    return productUuid;
  }
}
