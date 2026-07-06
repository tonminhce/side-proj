package vn.vnpt.inventory.api;

import jakarta.validation.Valid;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.vnpt.inventory.application.ReserveInventoryCommand;
import vn.vnpt.inventory.application.ReserveInventoryUseCase;
import vn.vnpt.inventory.domain.InventoryReservation;

/**
 * InventoryReservationController — Story 1.6 / FR-9 HTTP entry point.
 *
 * <p>{@code POST /api/inventory-reservations} returns {@code 201 Created} on success. The
 * controller calls {@link ReserveInventoryUseCase#reserve(ReserveInventoryCommand)} directly —
 * no service layer in between (the use case is already transactional).
 *
 * <p>Cross-process callers (Story 10.x Kafka-to-HTTP adapters, admin UI write paths) use HTTP.
 * Intra-JVM callers (Story 2.5's checkout saga) call the use case directly via Spring's bean
 * lookup — NO HTTP round-trip for the saga.
 */
@RestController
@RequestMapping("/api/inventory-reservations")
@RequiredArgsConstructor
@Validated
@Slf4j
public class InventoryReservationController {

  private final ReserveInventoryUseCase reserveInventoryUseCase;

  @PostMapping
  public ResponseEntity<InventoryReservationResponse> reserve(
      @Valid @RequestBody ReserveInventoryRequest request) {

    log.debug(
        "POST /api/inventory-reservations: variantId={} warehouseId={} qty={} sagaStepId={}",
        request.variantId(),
        request.warehouseId(),
        request.quantity(),
        request.sagaStepId());

    Duration ttl =
        request.ttlMinutes() != null ? Duration.ofMinutes(request.ttlMinutes()) : null;

    InventoryReservation reservation =
        reserveInventoryUseCase.reserve(
            new ReserveInventoryCommand(
                request.variantId(),
                request.warehouseId(),
                request.quantity(),
                request.sagaStepId(),
                request.orderUuid(),
                ttl));

    return ResponseEntity.status(HttpStatus.CREATED)
        .body(InventoryReservationResponse.from(reservation));
  }
}