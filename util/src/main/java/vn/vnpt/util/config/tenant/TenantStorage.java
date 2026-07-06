package vn.vnpt.util.config.tenant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;

public class TenantStorage {
  private static final String TENANT_HEADER = "X-Tenant";

  private static final ThreadLocal<String> currentTenant = new ThreadLocal<>();
  private static final ThreadLocal<String> currentSchema = new ThreadLocal<>();

  public static void setCurrentTenant(String tenantId) {
    currentTenant.set(tenantId);
  }

  public static void setSchema(String schema) {
    currentSchema.set(schema);
  }

  public static String getCurrentTenant() {
    return currentTenant.get();
  }

  public static String getCurrentSchema() {
    return currentSchema.get();
  }

  public static void clear() {
    currentTenant.remove();
    currentSchema.remove();
  }

  public static void setCurrentSchema(HttpServletRequest request, Environment environment) {
    String tenant = request.getHeader(TENANT_HEADER);
    currentTenant.set(tenant);
    String schema = environment.getProperty("tenants.datasources." + tenant + ".schema");
    currentSchema.set(schema);
  }
}
