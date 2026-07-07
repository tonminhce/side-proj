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
import vn.vnpt.inventory.application.ShipInventoryCommand;
import vn.vnpt.inventory.application.ShipInventoryUseCase;
import vn.vnpt.inventory.domain.InventoryLedgerEntry;

/**
 * ShipInventoryController — Story 1.8 / FR-11 (SHIPPED) HTTP entry point.
 *
 * <p>{@code POST /api/inventory-shipments} returns {@code 201 Created} on success.
 *
 * <p>Error mapping: {@link vn.vnpt.inventory.domain.exception.InsufficientStockException}
 * → HTTP 409 (Story 1.6's {@code ReservationControllerExceptionHandler});
 * {@link vn.vnpt.inventory.domain.exception.WarehouseNotFoundException} → HTTP 404;
 * {@link IllegalArgumentException} → HTTP 400.
 */
@RestController
@RequestMapping("/api/inventory-shipments")
@RequiredArgsConstructor
@Validated
@Slf4j
public class ShipInventoryController {

  private final ShipInventoryUseCase shipInventoryUseCase;

  @PostMapping
  public ResponseEntity<ShipInventoryResponse> ship(@RequestBody ShipInventoryRequest request) {
    log.debug(
        "POST /api/inventory-shipments: variantId={} warehouseId={} qty={} sagaStepId={}",
        request.variantId(),
        request.warehouseId(),
        request.quantity(),
        request.sagaStepId());

    InventoryLedgerEntry entry =
        shipInventoryUseCase.ship(
            new ShipInventoryCommand(
                request.variantId(),
                request.warehouseId(),
                request.quantity(),
                request.sagaStepId()));

    return ResponseEntity.status(HttpStatus.CREATED).body(ShipInventoryResponse.from(entry));
  }
}