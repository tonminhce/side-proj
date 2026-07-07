package vn.vnpt.payment.infrastructure.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Permits {@code /webhooks/**} and {@code /actuator/health} anonymously — Story 3.2 / AC #7.
 *
 * <p>The webhook endpoint is public; per {@code architecture.md:1048} NFR-SEC-2, mTLS at the
 * ingress is the only auth in v1 (HMAC signature verification lands in Story 3.5). Story 5.x adds
 * proper RBAC; this config keeps the rest of the surface locked behind authentication.
 */
@Configuration
public class PaymentSecurityConfig {

  @Bean
  SecurityFilterChain paymentSecurityFilterChain(HttpSecurity http) throws Exception {
    return http
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/webhooks/**", "/actuator/health", "/actuator/info").permitAll()
            .anyRequest().authenticated())
        .csrf(csrf -> csrf.disable())
        .build();
  }
}