package vn.vnpt.payment.infrastructure.stripe;

import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Stripe configuration — Story 3.3 / FR-24 / FR-29 / ADR-23. Bound from {@code stripe.*} in
 * {@code application.yml}; defaults come from env vars per the codebase's {@code dev/.env}
 * triple convention.
 */
@Validated
@ConfigurationProperties(prefix = "stripe")
public record StripeProperties(
    @Pattern(regexp = "real|test") String mode,
    String apiKey,
    String apiVersion,
    String webhookSigningSecret) {

  /** ponytail: dev-only API key placeholder; never a real key. See ADR-21 + R-12. */
  public static final String DEV_PLACEHOLDER_KEY = "sk_test_dev_placeholder";

  public static StripeProperties dev() {
    return new StripeProperties("test", DEV_PLACEHOLDER_KEY, "2025-09-30.clover", "");
  }
}