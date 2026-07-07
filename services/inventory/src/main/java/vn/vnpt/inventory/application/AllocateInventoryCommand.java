package vn.vnpt.inventory.application;

/**
 * Command for {@link AllocateInventoryUseCase#allocate(AllocateInventoryCommand)}.
 *
 * @param reservationUuid Snowflake id of the {@code InventoryReservation} to promote
 * @param sagaStepId ADR-11 idempotency key for the saga step
 */
public record AllocateInventoryCommand(Long reservationUuid, String sagaStepId) {}