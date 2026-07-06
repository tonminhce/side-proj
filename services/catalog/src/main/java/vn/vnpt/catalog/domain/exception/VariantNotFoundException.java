package vn.vnpt.catalog.domain.exception;

/**
 * Thrown when a {@code Variant} aggregate is not found by Snowflake id (Story 1.3 / AC #11).
 */
public class VariantNotFoundException extends RuntimeException {

  private final Long variantUuid;

  public VariantNotFoundException(Long variantUuid) {
    super("Variant not found: uuid=" + variantUuid);
    this.variantUuid = variantUuid;
  }

  public Long getVariantUuid() {
    return variantUuid;
  }
}
