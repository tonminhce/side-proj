package vn.vnpt.auth.infrastructure.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** Security wiring — Epic 5 follow-up. The blanket permitAll() left the
 *  /api/auth/service-token endpoint publicly callable (anonymous caller could
 *  mint a service-account JWT with arbitrary allowedRoles). Now: register + login
 *  + actuator/health are anonymously reachable; /api/auth/service-token is gated
 *  by {@link InternalTokenAuthFilter} (X-Internal-Token header) registered at
 *  HIGHEST_PRECEDENCE so it runs BEFORE Spring Security's denyAll chain. */
@Configuration
public class AuthSecurityConfig {

  @Bean
  FilterRegistrationBean<InternalTokenAuthFilter> internalTokenAuthFilterRegistration(
      @Value("${spring.profiles.active:prod}") String activeProfile,
      @Value("${auth.internal-token:}") String configured) {
    FilterRegistrationBean<InternalTokenAuthFilter> reg = new FilterRegistrationBean<>(
        new InternalTokenAuthFilter(activeProfile, configured));
    reg.addUrlPatterns("/api/auth/service-token");
    reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return reg;
  }

  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE + 1)
  SecurityFilterChain publicAuthEndpoints(HttpSecurity http) throws Exception {
    http
      .securityMatcher("/api/auth/register", "/api/auth/login", "/actuator/**", "/error")
      .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
      .csrf(csrf -> csrf.disable())
      .httpBasic(b -> b.disable())
      .formLogin(f -> f.disable())
      .logout(l -> l.disable())
      .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .oauth2ResourceServer(o -> o.disable());
    return http.build();
  }

  /** Catch-all chain. InternalTokenAuthFilter handles /api/auth/service-token
   *  upstream (HIGHEST_PRECEDENCE). All other paths fall through to denyAll(). */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE + 2)
  SecurityFilterChain defaultDeny(HttpSecurity http) throws Exception {
    http
      .securityMatcher("/**")
      .authorizeHttpRequests(auth -> auth.anyRequest().denyAll())
      .csrf(csrf -> csrf.disable())
      .httpBasic(b -> b.disable())
      .formLogin(f -> f.disable())
      .logout(l -> l.disable())
      .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .oauth2ResourceServer(o -> o.disable());
    return http.build();
  }
}