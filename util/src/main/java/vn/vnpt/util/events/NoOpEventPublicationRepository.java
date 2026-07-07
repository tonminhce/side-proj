package vn.vnpt.util.events;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.modulith.events.core.TargetEventPublication;

/**
 * No-op {@link EventPublicationRepository} bean required by Spring Modulith autoconfig when
 * services own their own outbox (V001 canonical columns). See ADR-14 §3.2.
 */
public final class NoOpEventPublicationRepository implements EventPublicationRepository {

  public static final NoOpEventPublicationRepository INSTANCE = new NoOpEventPublicationRepository();

  @Override public TargetEventPublication create(TargetEventPublication publication) { return publication; }
  @Override public void markProcessing(UUID identifier) { }
  @Override public void markCompleted(TargetEventPublication publication, Instant completionDate) { }
  @Override public void markCompleted(Object event, PublicationTargetIdentifier identifier, Instant completionDate) { }
  @Override public void markCompleted(UUID identifier, Instant completionDate) { }
  @Override public void markFailed(UUID identifier) { }
  @Override public boolean markResubmitted(UUID identifier, Instant resubmissionDate) { return true; }
  @Override public List<TargetEventPublication> findIncompletePublications() { return Collections.emptyList(); }
  @Override public List<TargetEventPublication> findIncompletePublicationsPublishedBefore(Instant instant) { return Collections.emptyList(); }
  @Override public Optional<TargetEventPublication> findIncompletePublicationsByEventAndTargetIdentifier(Object event, PublicationTargetIdentifier targetIdentifier) { return Optional.empty(); }
  @Override public void deletePublications(List<UUID> identifiers) { }
  @Override public void deleteCompletedPublications() { }
  @Override public void deleteCompletedPublicationsBefore(Instant instant) { }
}
