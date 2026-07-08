package vn.vnpt.customer.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** ObjectMapper bean — Story 5.3 (VnAddressCatalog JSON loading). */
@Configuration
public class JacksonConfig {
  @Bean
  public ObjectMapper objectMapper() {
    return new ObjectMapper();
  }
}