package vn.vnpt.catalog.application;

/**
 * Application command for {@link UpdatePriceUseCase} (Story 1.3 / AC #11).
 *
 * @param variantUuid Snowflake id of the variant whose price changes
 * @param newPriceCents new price in minor units (non-negative)
 */
public record UpdatePriceCommand(Long variantUuid, long newPriceCents) {}
