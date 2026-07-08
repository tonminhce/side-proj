package vn.vnpt.pricing.domain;

import java.time.Instant;

/** Price entry — Story 5.7. {@code salePriceCents} is nullable (no active sale). */
public record PriceEntry(
    long listPriceCents,
    Long salePriceCents,
    String currency,
    Instant effectiveAt) {
}