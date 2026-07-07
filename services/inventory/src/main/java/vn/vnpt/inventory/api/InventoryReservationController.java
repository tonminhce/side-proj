package vn.vnpt.inventory.api;

import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.vnpt.inventory.application.OnHandUseCase;
import vn.vnpt.inventory.application.ReserveInventoryCommand;
import vn.vnpt.inventory.application.ReserveInventoryUseCase;
import vn.vnpt.inventory.application.query.OnHandView;
import vn.vnpt.inventory.domain.InventoryReservation;
import vn.vnpt.inventory.domain.Region;

/**
 * InventoryReservationController — Story 1.6 / FR-9 HTTP entry point. Extended in Story 1.7 /
 * FR-10 with region-based dispatch (optional {@code shippingRegion} on POST) and a per-warehouse
 * breakdown GET endpoint.
 *
 * <p>{@code POST /api/inventory-reservations} returns {@code 201 Created} on success. The
 * controller calls {@link ReserveInventoryUseCase#reserve(ReserveInventoryCommand)} directly —
 * no service layer in between (the use case is already transactional).
 *
 * <p>{@code GET /api/inventory/variants/{variantId}/on-hand} returns the per-warehouse
 * breakdown. Delegates to {@link OnHandUseCase#findOnHand(Long)} — no new use-case method.
 *
 * <p>Cross-process callers (Story 10.x Kafka-to-HTTP adapters, admin UI write paths) use HTTP.
 * Intra-JVM callers (Story 2.5's checkout saga) call the use case directly via Spring's bean
 * lookup — NO HTTP round-trip for the saga.
 */
@RestController
@RequiredArgsConstructor
@Validated
@Slf4j
public class InventoryReservationController {

  private final ReserveInventoryUseCase reserveInventoryUseCase;
  private final OnHandUseCase onHandUseCase;

  @PostMapping("/api/inventory-reservations")
  public ResponseEntity<InventoryReservationResponse> reserve(
      @Valid @RequestBody ReserveInventoryRequest request) {

    log.debug(
        "POST /api/inventory-reservations: variantId={} warehouseId={} region={} qty={} sagaStepId={}",
        request.variantId(),
        request.warehouseId(),
        request.shippingRegion(),
        request.quantity(),
        request.sagaStepId());

    Duration ttl =
        request.ttlMinutes() != null ? Duration.ofMinutes(request.ttlMinutes()) : null;

    Region region = parseRegion(request.shippingRegion());

    InventoryReservation reservation =
        reserveInventoryUseCase.reserve(
            new ReserveInventoryCommand(
                request.variantId(),
                request.warehouseId(),
                region,
                request.quantity(),
                request.sagaStepId(),
                request.orderUuid(),
                ttl));

    return ResponseEntity.status(HttpStatus.CREATED)
        .body(InventoryReservationResponse.from(reservation));
  }

  /**
   * Per-warehouse breakdown — exposes the existing {@link OnHandUseCase#findOnHand(Long)}
   * (Story 1.5 multi-warehouse-capable read path) via HTTP. Empty list when the variant is
   * unseen across all warehouses.
   */
  @GetMapping("/api/inventory/variants/{variantId}/on-hand")
  public ResponseEntity<List<OnHandView>> getOnHand(@PathVariable Long variantId) {
    log.debug("GET /api/inventory/variants/{}/on-hand", variantId);
    List<OnHandView> breakdown = onHandUseCase.findOnHand(variantId);
    return ResponseEntity.ok(breakdown);
  }

  /**
   * Parse the inbound {@code shippingRegion} string into a {@link Region}. Returns {@code null}
   * when the caller did not supply a region (the use case enforces the dispatch XOR). Throws
   * {@link IllegalArgumentException} (mapped to 400) on unknown values — {@code Region.valueOf}
   * raises it natively.
   */
  private static Region parseRegion(String shippingRegion) {
    if (shippingRegion == null || shippingRegion.isBlank()) {
      return null;
    }
    try {
      return Region.valueOf(shippingRegion.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Unknown region: " + shippingRegion);
    }
  }
}