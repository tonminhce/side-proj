package vn.vnpt.payment.application.port;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link ThreeDSecureDecision} — Story 3.5 follow-up / FR-27 (PSD2 RTS).
 *
 * <p>Truth table for the 3DS step-up decision:
 * <pre>
 *   requires(country, amountCents, riskLevel) =
 *     country is in EEA   AND   amountCents >= 3000   AND   riskLevel in {ELEVATED, HIGHEST}
 * </pre>
 *
 * <p>The decision is a pure function — no Spring context, no Stripe SDK mock. The boundary cases
 * (low-value exemption, non-EEA, LOW risk, null inputs) are the interesting ones for the contract.
 */
class ThreeDSecureDecisionTest {

  @ParameterizedTest
  @CsvSource({
      // EEA + elevated + above floor → require 3DS
      "DE, 5000,  ELEVATED, true",
      "FR, 3000,  ELEVATED, true",   // exactly at floor
      "IT, 9999,  HIGHEST,  true",
      "NO, 3000,  HIGHEST,  true",   // EEA includes NO (Norway)
      // EEA + LOW → skip
      "DE, 5000,  LOW,      false",
      "FR, 9999,  LOW,      false",
      // EEA + below floor → skip (low-value exemption)
      "DE, 2999,  ELEVATED, false",
      "FR, 100,   HIGHEST,  false",
      // Non-EEA → skip regardless of amount/risk
      "US, 5000,  ELEVATED, false",
      "VN, 9999,  HIGHEST,  false",
      "JP, 3000,  ELEVATED, false",
  })
  void shouldRequire_countryAmountRiskMatrix(String country, long amount, RiskLevel risk, boolean expected) {
    assertThat(ThreeDSecureDecision.shouldRequire(country, amount, risk)).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "USA", "1", "D", "DE ", "DÉ"})
  void shouldRequire_rejectsMalformedCountry(String badCountry) {
    assertThat(ThreeDSecureDecision.shouldRequire(badCountry, 5000L, RiskLevel.HIGHEST)).isFalse();
  }

  @Test
  void shouldRequire_nullCountryIsUnknownAndSkips() {
    assertThat(ThreeDSecureDecision.shouldRequire(null, 5000L, RiskLevel.HIGHEST)).isFalse();
  }

  @ParameterizedTest
  @EnumSource(value = RiskLevel.class, names = {"ELEVATED", "HIGHEST"})
  void shouldRequire_eeaListIsUppercaseInvariant(RiskLevel risk) {
    // Lowercase country code should still resolve to EEA match.
    assertThat(ThreeDSecureDecision.shouldRequire("de", 5000L, risk)).isTrue();
    assertThat(ThreeDSecureDecision.shouldRequire("fr", 5000L, risk)).isTrue();
  }

  @Test
  void shouldRequire_nullRiskTreatedAsLow() {
    assertThat(ThreeDSecureDecision.shouldRequire("DE", 5000L, null)).isFalse();
  }
}