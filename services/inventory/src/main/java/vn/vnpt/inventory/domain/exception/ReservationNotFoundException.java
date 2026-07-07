package vn.vnpt.inventory.domain.exception;

/**
 * Thrown by {@link vn.vnpt.inventory.application.AllocateInventoryUseCase} when the
 * supplied {@code reservationUuid} does not match any persisted reservation. Mapped to
 * HTTP 404 by {@code ReservationControllerExceptionHandler}. Story 1.8 / FR-11 (ALLOCATED).
 */
public class ReservationNotFoundException extends RuntimeException {

  public ReservationNotFoundException(Long reservationUuid) {
    super("Reservation not found: uuid=" + reservationUuid);
  }
}