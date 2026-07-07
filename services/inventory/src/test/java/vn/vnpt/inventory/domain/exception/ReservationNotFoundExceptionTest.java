package vn.vnpt.inventory.domain.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit pin for {@link ReservationNotFoundException} — Story 1.8 / FR-11 (ALLOCATED).
 *
 * <p>Asymmetric with {@link InsufficientStockExceptionTest}: Story 1.6 authored a
 * 1-case unit test for {@link InsufficientStockException} (which has 4-tuple storage).
 * Story 1.8 adds a similar exception for the allocation path; pinning the message format
 * here ensures the HTTP 404 response body (built by
 * {@code ReservationControllerExceptionHandler}) stays stable for the saga's diagnostic
 * logging.
 */
class ReservationNotFoundExceptionTest {

  @Test
  void constructor_storesUuidInMessage() {
    ReservationNotFoundException ex = new ReservationNotFoundException(9_999_999L);

    assertThat(ex.getMessage()).contains("9999999");
    assertThat(ex.getMessage()).contains("not found");
    assertThat(ex).isInstanceOf(RuntimeException.class);
  }

  @Test
  void constructor_withNullUuid_doesNotThrow() {
    // Defensive — the use case only constructs with a known-non-null uuid, but a future
    // refactor that passes null should not NPE at construction time.
    ReservationNotFoundException ex = new ReservationNotFoundException(null);

    assertThat(ex.getMessage()).contains("null");
  }
}