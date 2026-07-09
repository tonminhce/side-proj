package vn.vnpt.gateway.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway BIN-velocity configuration — Story 3.4 LOW-3 review finding.
 *
 * <p>Prior to this, the {@code binVelocityMaxAttempts} threshold was an inline argument on the
 * route's {@code RateLimiter} filter in {@code application.yml}. Operationally changing the
 * threshold required editing the route definition rather than a single config key. Extracted
 * to a top-level {@code gateway.bin-velocity.*} block so it can be overridden per-environment
 * (dev / staging / prod) without touching route definitions.
 *
 * <p>// ponytail: defaulted to 10 attempts per 60-min window to match the previous inline value.
 * Add an {@code @Validated} constraint + jakarta.validation when the team wants a fail-fast
 * config check (e.g. {@code @Min(1) @Max(1000)}).
 */
@ConfigurationProperties(prefix = "gateway.bin-velocity")
public class GatewayBinVelocityProperties {

  /** Max BIN-attempts allowed within the rolling window. */
  private int maxAttempts = 10;

  /** Window size in minutes. */
  private int windowMinutes = 60;

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public void setMaxAttempts(int maxAttempts) {
    this.maxAttempts = maxAttempts;
  }

  public int getWindowMinutes() {
    return windowMinutes;
  }

  public void setWindowMinutes(int windowMinutes) {
    this.windowMinutes = windowMinutes;
  }
}
