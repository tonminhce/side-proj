package vn.vnpt.inventory.infrastructure.config;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import vn.vnpt.util.component.softdelete.registry.SoftDeleteMetadataRegistry;
import vn.vnpt.util.component.softdelete.validator.UkValidator;
import vn.vnpt.util.exception.ApiExceptionHandle;

/**
 * Inventory-local config to ensure the {@link UkValidator} + {@link SoftDeleteMetadataRegistry}
 * beans are registered even when {@code vn.vnpt.util.UtilsAutoConfiguration} is excluded (the
 * test profile excludes it due to a pre-existing dual-bean bug in util's autoconfig).
 *
 * <p>Story 1.8 / FR-12 binds the {@code @SoftUk} audit on {@link
 * vn.vnpt.inventory.domain.Warehouse} via this config — the validator runs in {@link
 * vn.vnpt.inventory.application.CreateWarehouseUseCase#create}.
 *
 * <p>Ponytail: we DEFINE the soft-delete beans here (not just @Import them) because the test
 * profile excludes util's UtilsAutoConfiguration. {@link ApiExceptionHandle} is also
 * {@link ComponentScan scanned} from util's exception package so {@code @SoftUk} violations
 * map to HTTP 400.
 */
@Configuration
@ComponentScan(basePackages = "vn.vnpt.util.exception")
@Slf4j
public class SoftDeleteConfig {

  @Bean
  public SoftDeleteMetadataRegistry softDeleteMetadataRegistry(EntityManagerFactory emf) {
    log.info("[Story 1.8] registering SoftDeleteMetadataRegistry locally");
    return new SoftDeleteMetadataRegistry(emf);
  }

  @Bean
  public UkValidator ukValidator(
      SoftDeleteMetadataRegistry registry, ObjectProvider<MeterRegistry> meterRegistryProvider) {
    return new UkValidator(registry, meterRegistryProvider);
  }
}