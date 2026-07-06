package vn.vnpt.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit test for {@link ReservationStatus#isTerminal()} — Story 1.6 / FR-9.
 *
 * <p>The sweeper + release path use this to short-circuit duplicate work on already-released
 * reservations.
 */
class ReservationStatusTest {

  @Test
  void isTerminal_returnsCorrectValues() {
    assertThat(ReservationStatus.ACTIVE.isTerminal()).isFalse();
    assertThat(ReservationStatus.RELEASED.isTerminal()).isTrue();
    assertThat(ReservationStatus.COMMITTED.isTerminal()).isTrue();
  }
}