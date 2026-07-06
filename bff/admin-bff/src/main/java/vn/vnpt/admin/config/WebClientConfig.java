package vn.vnpt.admin.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/** WebClient bean for the catalog service outbound (Story 1.4 / FR-6). */
@Configuration
public class WebClientConfig {

  @Bean
  public WebClient catalogClient(@Value("${services.catalog.base-url}") String baseUrl) {
    return WebClient.builder().baseUrl(baseUrl).build();
  }
}