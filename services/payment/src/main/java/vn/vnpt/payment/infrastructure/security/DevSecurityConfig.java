package vn.vnpt.payment.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Dev-only: permit /api/** so curl smoke tests work without JWT.
 *  Replaced in prod by a real oauth2-resource-server SecurityFilterChain (Story 5.4+). */
@Configuration
@Profile("dev")
public class DevSecurityConfig {

  @Bean
  @Order(0)
  SecurityFilterChain devApi(HttpSecurity http) throws Exception {
    http.securityMatcher("/api/**")
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .csrf(csrf -> csrf.disable());
    return http.build();
  }
}
