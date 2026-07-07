package vn.vnpt.payment.infrastructure.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.core.DefaultEventPublicationRegistry;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.events.core.EventPublicationRepository;

/**
 * Provides the Modulith {@link EventPublicationRegistry} directly — Story 3.1 (no events yet).
 *
 * <p>Spring Modulith's {@code EventPublicationAutoConfiguration} only creates the registry when an
 * {@link EventPublicationRepository} bean exists in the context, and only when its
 * {@code JdbcEventPublicationAutoConfiguration} is on the classpath (which we exclude per the
 * catalog / checkout / cart convention so the V001 canonical {@code outbox} table is the authority).
 * Story 3.1 ships no outbox writes, so we provide an empty in-memory registry directly — the
 * {@link ConditionalOnMissingBean} guard means a future Story 3.5 upgrade (real outbox + JDBC
 * repository) will override this with the production wiring.
 */
@Configuration
public class PaymentModulithConfig {

  @Bean
  @ConditionalOnMissingBean
  EventPublicationRegistry eventPublicationRegistry() {
    return new DefaultEventPublicationRegistry(new InMemoryEventPublicationRepository(), java.time.Clock.systemUTC());
  }

  /** In-memory no-op repo. Story 3.5 replaces this with the JDBC-backed implementation. */
  private static final class InMemoryEventPublicationRepository implements EventPublicationRepository {
    @Override public org.springframework.modulith.events.core.TargetEventPublication create(
        org.springframework.modulith.events.core.TargetEventPublication publication) {
      return publication;
    }
    @Override public void markProcessing(java.util.UUID identifier) {}
    @Override public void markCompleted(
        org.springframework.modulith.events.core.TargetEventPublication publication, java.time.Instant date) {}
    @Override public void markCompleted(Object event,
        org.springframework.modulith.events.core.PublicationTargetIdentifier id, java.time.Instant date) {}
    @Override public void markCompleted(java.util.UUID identifier, java.time.Instant date) {}
    @Override public void markFailed(java.util.UUID identifier) {}
    @Override public boolean markResubmitted(java.util.UUID identifier, java.time.Instant date) { return true; }
    @Override public java.util.List<org.springframework.modulith.events.core.TargetEventPublication> findIncompletePublications() {
      return java.util.Collections.emptyList();
    }
    @Override public java.util.List<org.springframework.modulith.events.core.TargetEventPublication> findIncompletePublicationsPublishedBefore(
        java.time.Instant instant) {
      return java.util.Collections.emptyList();
    }
    @Override public java.util.Optional<org.springframework.modulith.events.core.TargetEventPublication> findIncompletePublicationsByEventAndTargetIdentifier(
        Object event, org.springframework.modulith.events.core.PublicationTargetIdentifier id) {
      return java.util.Optional.empty();
    }
    @Override public void deletePublications(java.util.List<java.util.UUID> ids) {}
    @Override public void deleteCompletedPublications() {}
    @Override public void deleteCompletedPublicationsBefore(java.time.Instant instant) {}
  }
}