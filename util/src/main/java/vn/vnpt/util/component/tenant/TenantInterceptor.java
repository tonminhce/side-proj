package vn.vnpt.util.component.tenant;

import org.jetbrains.annotations.NotNull;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.ui.ModelMap;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.WebRequestInterceptor;
import vn.vnpt.util.config.tenant.TenantStorage;

@Component
public class TenantInterceptor implements WebRequestInterceptor {

    private final Environment environment;
    private static final String TENANT_HEADER = "X-Tenant";

    public TenantInterceptor(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void preHandle(WebRequest request) {
        TenantStorage.setCurrentTenant(request.getHeader(TENANT_HEADER));
        TenantStorage.setSchema(environment.getProperty("tenants.datasources." + TenantStorage.getCurrentTenant() + ".schema"));
    }

    @Override
    public void postHandle(@NotNull WebRequest webRequest, ModelMap modelMap) {
        TenantStorage.clear();
    }

    @Override
    public void afterCompletion(@NotNull WebRequest webRequest, Exception e) {

    }
}
