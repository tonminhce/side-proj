package vn.vnpt.payment.application.port;

import java.util.Set;

/**
 * Pure decision function for the 3DS step-up trigger — Story 3.5 follow-up / FR-27 / PSD2 RTS.
 *
 * <p>Rule (PSD2 RTS Article 18 + acquirer risk policy): require 3DS when ALL of:
 * <ul>
 *   <li>issuer country is in the EEA, AND</li>
 *   <li>amount in minor units &ge; 30 EUR equivalent (configurable threshold; default 3000 = 30.00 EUR),
 *       AND</li>
 *   <li>{@code riskLevel} is {@link RiskLevel#ELEVATED} or {@link RiskLevel#HIGHEST}.</li>
 * </ul>
 *
 * <p>PSD2 also requires 3DS for any EEA transaction outside the low-value exemption
 * (low-value = &lt; 30 EUR; this rule is the floor, not the ceiling). Out-of-scope for this story
 * (the SCA Engine in {@code request_three_d_secure=AUTOMATIC} covers the case where the merchant
 * lets Stripe decide); this function only encodes the merchant-driven override.
 *
 * <p>ponytail: extracted as a pure function so it is unit-testable without a Spring context and
 * without a Stripe SDK mock. The EEA list is hard-coded for v1; if the merchant expands to non-EEA
 * acquirer jurisdictions the list moves to a config bean.
 */
public final class ThreeDSecureDecision {

  /** ISO-3166-1 alpha-2 country codes in the European Economic Area (EU + IS + LI + NO). */
  public static final Set<String> EEA_COUNTRIES = Set.of(
      "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR",
      "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL",
      "PL", "PT", "RO", "SK", "SI", "ES", "SE",
      "IS", "LI", "NO");

  /** Amount floor (minor units) below which 3DS is skipped regardless of risk — 30.00 EUR. */
  public static final long LOW_VALUE_FLOOR_MINOR = 3000L;

  private ThreeDSecureDecision() {}

  /**
   * @param country  ISO-3166-1 alpha-2 country code (case-insensitive); null treated as unknown.
   * @param amountCents transaction amount in minor units of {@code currency}.
   * @param riskLevel nullable; null treated as {@link RiskLevel#LOW}.
   * @return true iff the 3DS step-up should be requested for this transaction.
   */
  public static boolean shouldRequire(String country, long amountCents, RiskLevel riskLevel) {
    if (country == null || country.length() != 2) {
      return false;
    }
    if (amountCents < LOW_VALUE_FLOOR_MINOR) {
      return false;
    }
    RiskLevel effective = riskLevel == null ? RiskLevel.LOW : riskLevel;
    if (effective == RiskLevel.LOW) {
      return false;
    }
    return EEA_COUNTRIES.contains(country.toUpperCase());
  }
}