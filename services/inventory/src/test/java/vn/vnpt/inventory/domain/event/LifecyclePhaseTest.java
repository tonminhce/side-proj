package vn.vnpt.inventory.domain.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit test for {@link LifecyclePhase}. Story 1.8 / FR-11.
 */
class LifecyclePhaseTest {

  @Test
  void wireValue_returnsUpperSnakeString() {
    assertThat(LifecyclePhase.RESERVED.wireValue()).isEqualTo("RESERVED");
    assertThat(LifecyclePhase.RELEASED.wireValue()).isEqualTo("RELEASED");
    assertThat(LifecyclePhase.ALLOCATED.wireValue()).isEqualTo("ALLOCATED");
    assertThat(LifecyclePhase.SHIPPED.wireValue()).isEqualTo("SHIPPED");
    assertThat(LifecyclePhase.ADJUSTED.wireValue()).isEqualTo("ADJUSTED");
  }

  @Test
  void parseFromWireValue_returnsEnum() {
    assertThat(LifecyclePhase.parseFromWireValue("RESERVED")).isEqualTo(LifecyclePhase.RESERVED);
    assertThat(LifecyclePhase.parseFromWireValue("reserved")).isEqualTo(LifecyclePhase.RESERVED);
    assertThat(LifecyclePhase.parseFromWireValue("Released")).isEqualTo(LifecyclePhase.RELEASED);
    assertThat(LifecyclePhase.parseFromWireValue("ALLOCATED")).isEqualTo(LifecyclePhase.ALLOCATED);
    assertThat(LifecyclePhase.parseFromWireValue("SHIPPED")).isEqualTo(LifecyclePhase.SHIPPED);
    assertThat(LifecyclePhase.parseFromWireValue("ADJUSTED")).isEqualTo(LifecyclePhase.ADJUSTED);
  }

  @Test
  void parseFromWireValue_returnsNullForUnknown() {
    assertThat(LifecyclePhase.parseFromWireValue("UNKNOWN")).isNull();
    assertThat(LifecyclePhase.parseFromWireValue("")).isNull();
    assertThat(LifecyclePhase.parseFromWireValue(null)).isNull();
  }
}