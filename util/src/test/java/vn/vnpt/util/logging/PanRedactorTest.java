package vn.vnpt.util.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PanRedactor} — Story 3.3 / FR-29 / R-15 / ADR-23.
 *
 * <p>Asserts the regex redacts 13–19 digit runs and leaves shorter / non-numeric inputs alone.
 */
class PanRedactorTest {

  @Test
  void redact_replaces13to19digitFields() {
    assertThat(PanRedactor.redact("PAN=4111111111111111"))
        .isEqualTo("PAN=***REDACTED:PAN***");
    assertThat(PanRedactor.redact("track1=%B4242424242424242^DOE/JOHN"))
        .isEqualTo("track1=%B***REDACTED:PAN***^DOE/JOHN");
    assertThat(PanRedactor.redact("19-digit 1234567890123456789 here"))
        .contains("***REDACTED:PAN***");
  }

  @Test
  void redact_leavesShorterDigitsUntouched() {
    assertThat(PanRedactor.redact("phone=5551234")).isEqualTo("phone=5551234");
    assertThat(PanRedactor.redact("amount=1999")).isEqualTo("amount=1999");
    assertThat(PanRedactor.redact("uuid=1234567890")).isEqualTo("uuid=1234567890");
  }

  @Test
  void redact_leavesAlphanumericUntouched() {
    assertThat(PanRedactor.redact("order=42")).isEqualTo("order=42");
    assertThat(PanRedactor.redact("traceId=abc-123-def")).isEqualTo("traceId=abc-123-def");
  }

  @Test
  void redact_handlesNullAndEmpty() {
    assertThat(PanRedactor.redact(null)).isNull();
    assertThat(PanRedactor.redact("")).isEmpty();
  }

  @Test
  void redact_handlesMultipleMatchesInSingleMessage() {
    String input = "first=4111111111111111 second=5555555555554444 third=42";
    String expected = "first=***REDACTED:PAN*** second=***REDACTED:PAN*** third=42";
    assertThat(PanRedactor.redact(input)).isEqualTo(expected);
  }

  // Boundary tests (MEDIUM-8 fix)
  @Test
  void redact_leavesTwelveDigitsUntouched() {
    assertThat(PanRedactor.redact("ts=123456789012")).isEqualTo("ts=123456789012");
  }

  @Test
  void redact_catchesTwentyDigitRun() {
    // 20 contiguous digits → regex matches a 13–19 digit substring, replacing it.
    String result = PanRedactor.redact("12345678901234567890");
    assertThat(result).contains("***REDACTED:PAN***");
    assertThat(result).doesNotContain("1234567890123456789");
  }

  @Test
  void redact_handlesEmbeddedPanInLargerString() {
    assertThat(PanRedactor.redact("order-4111111111111111-123"))
        .isEqualTo("order-***REDACTED:PAN***-123");
  }
}