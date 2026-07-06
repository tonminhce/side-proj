package vn.vnpt.catalog.application;

/**
 * Application command for {@link UpdateProductUseCase} (Story 1.3 / AC #10).
 *
 * @param productUuid Snowflake id of the product to update
 * @param name new product name (required, non-blank)
 * @param description new product description (nullable)
 * @param brand new brand (nullable)
 */
public record UpdateProductCommand(
    Long productUuid, String name, String description, String brand) {}
