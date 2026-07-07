package vn.vnpt.inventory.application;

import vn.vnpt.inventory.domain.Region;

/**
 * Command for {@link CreateWarehouseUseCase#create(CreateWarehouseCommand)} — Story 1.8 / FR-12.
 *
 * @param code the admin-managed slug (e.g., {@code "DN-01"}); {@code @SoftUk} enforces uniqueness
 *     per {@code (tenantId, code)}
 * @param displayName human-readable name for the admin UI
 * @param region the dispatch region (NORTH/SOUTH/CENTRAL)
 */
public record CreateWarehouseCommand(String code, String displayName, Region region) {}