package vn.vnpt.inventory.api;

/**
 * Request body for {@code POST /api/inventory-warehouses}.
 *
 * @param code admin-managed slug; uniqueness enforced via {@code @SoftUk(name =
 *     "warehouse_code_per_tenant", fields = {"tenantId", "code"})}
 * @param displayName human-readable name for the admin UI
 * @param region dispatch region (NORTH/SOUTH/CENTRAL, case-insensitive on input)
 */
public record CreateWarehouseRequest(String code, String displayName, String region) {}