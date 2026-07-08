package vn.vnpt.customer.infrastructure.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** Permits all — v1 single-tenant (RBAC lands in Story 5.4). Mirrors services/payment's
 *  PaymentSecurityConfig from Story 3.2. */
@Configuration
public class CustomerSecurityConfig {

  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  SecurityFilterChain permitAll(HttpSecurity http) throws Exception {
    http
      .securityMatcher("/**")
      .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
      .csrf(csrf -> csrf.disable())
      .httpBasic(b -> b.disable())
      .formLogin(f -> f.disable())
      .logout(l -> l.disable())
      .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .oauth2ResourceServer(o -> o.disable());
    return http.build();
  }
}