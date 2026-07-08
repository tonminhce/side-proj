package vn.vnpt.customer.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** System-UTC Clock for the customer service — Story 5.2 (exportedAt + forgottenAt). */
@Configuration
public class ClockConfig {
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}