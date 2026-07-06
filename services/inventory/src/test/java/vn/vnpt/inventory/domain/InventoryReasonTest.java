package vn.vnpt.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Pure-JUnit pins for {@link InventoryReason#toColumnValue()} — Story 1.5 / AC #8.
 *
 * <p>The use case maps the enum to a lowercase {@code VARCHAR(64)} via {@code toColumnValue}.
 * The mapping is intentionally a code-side concern: adding a new reason type is a code change,
 * NOT a database migration. A regression that flips the case (e.g. {@code "Receive"} instead of
 * {@code "receive"}) silently breaks the {@code inventory_on_hand} view and any downstream
 * consumer that joins on the reason string.
 */
class InventoryReasonTest {

  @ParameterizedTest
  @EnumSource(InventoryReason.class)
  void toColumnValue_isLowercaseUnderscoreFree(InventoryReason reason) {
    String column = reason.toColumnValue();
    assertThat(column)
        .as("InventoryReason.%s column value", reason.name())
        .isEqualTo(reason.name().toLowerCase())
        .doesNotContain("_")
        .matches("^[a-z]+$");
  }
}