package vn.vnpt.checkout.infrastructure.security;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/** Dev-only: permit /api/** + tell Hibernate to scan inventory entities. */
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

  @Bean
  LocalContainerEntityManagerFactoryBean entityManagerFactory(
      EntityManagerFactoryBuilder builder, DataSource dataSource) {
    Map<String, Object> jpaProps = new HashMap<>();
    jpaProps.put("hibernate.hbm2ddl.auto", "validate");
    jpaProps.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
    jpaProps.put("hibernate.archive.scanner.detect", "class,package");
    return builder
        .dataSource(dataSource)
        .packages("vn.vnpt.checkout", "vn.vnpt.inventory")
        .properties(jpaProps)
        .build();
  }
}
