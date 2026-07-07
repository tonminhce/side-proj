package vn.vnpt.inventory.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.inventory.domain.Warehouse;
import vn.vnpt.inventory.infrastructure.repository.WarehouseRepository;
import vn.vnpt.util.component.softdelete.validator.UkValidator;

/**
 * CreateWarehouseUseCase — Story 1.8 / FR-12 (DI-09 fix).
 *
 * <p>Persists a new {@link Warehouse} after running {@link UkValidator#validate(Object)} so
 * the application-layer {@code @SoftUk} invariant fires BEFORE the {@code repository.save}.
 * On conflict the validator throws {@link vn.vnpt.util.exception.InvalidInputException} (mapped
 * to HTTP 400 by util's {@code ApiExceptionHandle}).
 *
 * <p>The {@code tenantId} is set to {@code "default"} via the entity's {@code @PrePersist}
 * hook (mirrors the {@code InventoryLedgerEntry.onPrePersist} pattern).
 */
@Service
@Transactional
@RequiredArgsConstructor
public class CreateWarehouseUseCase {

  private final WarehouseRepository warehouseRepository;
  private final UkValidator ukValidator;

  public Warehouse create(CreateWarehouseCommand cmd) {
    if (cmd.code() == null || cmd.code().isBlank()) {
      throw new IllegalArgumentException("code is required");
    }
    if (cmd.region() == null) {
      throw new IllegalArgumentException("region is required");
    }

    Warehouse warehouse =
        Warehouse.builder()
            .code(cmd.code())
            .displayName(cmd.displayName())
            .region(cmd.region())
            .tenantId("default")
            .build();

    // ponytail: this is the v1 single-tenant mitigation for DI-09. The runtime UK check
    // replaces the use case's missing application-layer guard. Story 8.x multi-tenant
    // activation tightens the DB constraint and removes this app-layer check.
    ukValidator.validate(warehouse);

    return warehouseRepository.save(warehouse);
  }
}