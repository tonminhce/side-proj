package vn.vnpt.cart.infrastructure.config;

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
 * Cart-local config that registers the {@link UkValidator} + {@link SoftDeleteMetadataRegistry}
 * beans (Story 2.1; mirrors inventory's {@code SoftDeleteConfig}).
 *
 * <p>We DEFINE the soft-delete beans here (not just {@code @Import} them) because the test profile
 * excludes util's {@code UtilsAutoConfiguration} (pre-existing dual-bean bug). {@link
 * ApiExceptionHandle} is {@link ComponentScan scanned} from util's exception package so
 * {@code @SoftUk} violations ({@code InvalidInputException}) map to HTTP 400.
 *
 * <p>The validator runs in {@code GetOrCreateCartUseCase.getOrCreate(...)} before {@code save},
 * enforcing the {@code @SoftUk(fields = {"tenantId","userId"})} invariant on {@code Cart} (AC #8).
 */
@Configuration
@ComponentScan(basePackages = "vn.vnpt.util.exception")
@Slf4j
public class SoftDeleteConfig {

  @Bean
  public SoftDeleteMetadataRegistry softDeleteMetadataRegistry(EntityManagerFactory emf) {
    log.info("[Story 2.1] registering SoftDeleteMetadataRegistry locally");
    return new SoftDeleteMetadataRegistry(emf);
  }

  @Bean
  public UkValidator ukValidator(
      SoftDeleteMetadataRegistry registry, ObjectProvider<MeterRegistry> meterRegistryProvider) {
    return new UkValidator(registry, meterRegistryProvider);
  }
}
