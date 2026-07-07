package vn.vnpt.payment.application.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Trust-boundary validation for {@link AuthorizePaymentCommand} — Story 3.1 / review-fix.
 *
 * <p>Negative {@code amountCents} and malformed {@code currency} are rejected at construction
 * (4xx-shaped client errors) so the saga compensator sees an {@link IllegalArgumentException}
 * and stops retrying instead of looping on a transient-failure signal.
 */
class AuthorizePaymentCommandTest {

  @Test
  void rejectsNegativeAmountCents() {
    assertThatThrownBy(() -> new AuthorizePaymentCommand(42L, -1L, "VND", null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("amountCents");
  }

  @ParameterizedTest
  @ValueSource(strings = {"vnd", "V1D", "USDX", "", "V"})
  void rejectsNonIso4217Currency(String bad) {
    assertThatThrownBy(() -> new AuthorizePaymentCommand(42L, 100L, bad, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("currency");
  }

  @Test
  void acceptsValidCommand() {
    AuthorizePaymentCommand cmd = new AuthorizePaymentCommand(42L, 100L, "VND", "cus_x", null);
    assertThat(cmd.orderUuid()).isEqualTo(42L);
    assertThat(cmd.currency()).isEqualTo("VND");
  }
}