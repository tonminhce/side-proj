package vn.vnpt.inventory.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.vnpt.inventory.application.AllocateInventoryCommand;
import vn.vnpt.inventory.application.AllocateInventoryUseCase;
import vn.vnpt.inventory.domain.InventoryReservation;

/**
 * AllocateInventoryController — Story 1.8 / FR-11 (ALLOCATED) HTTP entry point.
 *
 * <p>{@code POST /api/inventory-allocations} returns {@code 201 Created`} on success.
 *
 * <p>Error mapping: {@link vn.vnpt.inventory.domain.exception.ReservationNotFoundException}
 * → HTTP 404 (added to {@code ReservationControllerExceptionHandler} in this story);
 * {@link IllegalArgumentException} → HTTP 400 (already mapped).
 */
@RestController
@RequestMapping("/api/inventory-allocations")
@RequiredArgsConstructor
@Validated
@Slf4j
public class AllocateInventoryController {

  private final AllocateInventoryUseCase allocateInventoryUseCase;

  @PostMapping
  public ResponseEntity<AllocateInventoryResponse> allocate(
      @RequestBody AllocateInventoryRequest request) {
    log.debug(
        "POST /api/inventory-allocations: reservationUuid={} sagaStepId={}",
        request.reservationUuid(),
        request.sagaStepId());

    InventoryReservation reservation =
        allocateInventoryUseCase.allocate(
            new AllocateInventoryCommand(request.reservationUuid(), request.sagaStepId()));

    return ResponseEntity.status(HttpStatus.CREATED)
        .body(AllocateInventoryResponse.from(reservation));
  }
}