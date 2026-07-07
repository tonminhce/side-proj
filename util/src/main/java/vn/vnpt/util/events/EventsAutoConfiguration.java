package vn.vnpt.util.events;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.core.DefaultEventPublicationRegistry;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.events.core.EventPublicationRepository;

/** Standalone Modulith event bridge: beans required by Modulith autoconfig when services
 *  exclude {@code UtilsAutoConfiguration} but still use Spring Modulith's EventPublicationRegistry. */
@Configuration
public class EventsAutoConfiguration {

  @Bean
  EventPublicationRegistry eventPublicationRegistry(EventPublicationRepository repository) {
    return new DefaultEventPublicationRegistry(repository, Clock.systemUTC());
  }
}
