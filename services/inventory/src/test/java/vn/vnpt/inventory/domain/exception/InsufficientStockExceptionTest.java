package vn.vnpt.inventory.domain.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit test for {@link InsufficientStockException} — Story 1.6 / FR-9.
 *
 * <p>Pins the constructor's 4-tuple for diagnostic logging + the 409 mapping (handled by
 * {@code ReservationControllerExceptionHandler}).
 */
class InsufficientStockExceptionTest {

  @Test
  void constructor_storesAllFourFields() {
    InsufficientStockException ex =
        new InsufficientStockException(100L, 1L, 5L, 2L);

    assertThat(ex.getVariantId()).isEqualTo(100L);
    assertThat(ex.getWarehouseId()).isEqualTo(1L);
    assertThat(ex.getRequested()).isEqualTo(5L);
    assertThat(ex.getAvailable()).isEqualTo(2L);
    assertThat(ex.getMessage())
        .contains("variantId=100")
        .contains("warehouseId=1")
        .contains("requested=5")
        .contains("available=2");
  }
}