package vn.vnpt.util.logging;

import java.util.regex.Pattern;

/**
 * PAN (Primary Account Number) redactor — Story 3.3 / FR-29 / R-15 / ADR-23.
 *
 * <p>Replaces any 13–19 digit run with {@code ***REDACTED:PAN***}. Stable marker so reviewers can
 * grep for accidental PAN leaks. Covers PAN, track-1, and track-2 magnetic stripe data per PCI-DSS
 * scope definitions.
 *
 * <p>This is the <b>defense-in-depth backstop</b>; the primary PCI control is "PAN never enters
 * our JVM" via the Stripe Elements iframe (FR-29). A regex redactor alone is not PCI-DSS-compliant
 * on its own.
 */
public final class PanRedactor {

  private static final Pattern PAN_PATTERN = Pattern.compile("\\d{13,19}");

  private static final String REDACTED = "***REDACTED:PAN***";

  private PanRedactor() {}

  /** Returns {@code input} with any 13–19 digit run replaced by {@code ***REDACTED:PAN***}. */
  public static String redact(String input) {
    if (input == null || input.isEmpty()) {
      return input;
    }
    return PAN_PATTERN.matcher(input).replaceAll(REDACTED);
  }
}