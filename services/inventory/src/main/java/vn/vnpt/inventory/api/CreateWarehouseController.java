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
import vn.vnpt.inventory.application.CreateWarehouseCommand;
import vn.vnpt.inventory.application.CreateWarehouseUseCase;
import vn.vnpt.inventory.domain.Region;
import vn.vnpt.inventory.domain.Warehouse;

/**
 * CreateWarehouseController — Story 1.8 / FR-12 HTTP entry point.
 *
 * <p>{@code POST /api/inventory-warehouses} returns {@code 201 Created} on success.
 *
 * <p>Error mapping: {@link vn.vnpt.util.exception.InvalidInputException} (thrown by
 * {@code UkValidator} on {@code @SoftUk} conflict) → HTTP 400 (util's
 * {@code ApiExceptionHandle}). {@link IllegalArgumentException} → HTTP 400 (Story 1.6's
 * {@link ReservationControllerExceptionHandler}).
 */
@RestController
@RequestMapping("/api/inventory-warehouses")
@RequiredArgsConstructor
@Validated
@Slf4j
public class CreateWarehouseController {

  private final CreateWarehouseUseCase createWarehouseUseCase;

  @PostMapping
  public ResponseEntity<CreateWarehouseResponse> create(
      @RequestBody CreateWarehouseRequest request) {
    log.debug(
        "POST /api/inventory-warehouses: code={} displayName={} region={}",
        request.code(),
        request.displayName(),
        request.region());

    Region region = parseRegion(request.region());

    Warehouse saved =
        createWarehouseUseCase.create(
            new CreateWarehouseCommand(request.code(), request.displayName(), region));

    return ResponseEntity.status(HttpStatus.CREATED).body(CreateWarehouseResponse.from(saved));
  }

  private static Region parseRegion(String s) {
    if (s == null || s.isBlank()) {
      throw new IllegalArgumentException("region is required");
    }
    try {
      return Region.valueOf(s.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Unknown region: " + s);
    }
  }
}