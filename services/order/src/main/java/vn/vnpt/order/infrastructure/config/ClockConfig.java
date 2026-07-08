package vn.vnpt.order.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provides a system-UTC {@link Clock} for the order service — Story 4.4 / FR-34.
 *  Test code can override this bean with a {@code Clock.fixed(...)} for deterministic
 *  edit-window testing. */
@Configuration
public class ClockConfig {
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}