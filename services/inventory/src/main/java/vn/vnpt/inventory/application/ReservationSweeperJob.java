package vn.vnpt.inventory.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.vnpt.inventory.domain.ReservationStatus;
import vn.vnpt.inventory.infrastructure.repository.InventoryReservationRepository;

/**
 * ReservationSweeperJob — Story 1.6 / FR-9 (TTL auto-expiry).
 *
 * <p>Runs at a fixed delay (default 30s) and releases ACTIVE reservations whose
 * {@code expires_at < now()}. Each release is its own transaction (REQUIRES_NEW via
 * {@link ReleaseInventoryUseCase#releaseExpired(Long)}) so the batch survives a single bad
 * release.
 *
 * <p>Bounded to {@code inventory.reservation.sweeper-batch-size} per tick (default 100) — this
 * prevents a DB lock storm if 10k reservations expire at once. The next tick picks up the rest.
 *
 * <p>ponytail: global lock, per-account locks if throughput matters.
 */
@Component
@Slf4j
public class ReservationSweeperJob {

  private final InventoryReservationRepository reservationRepository;
  private final ReleaseInventoryUseCase releaseInventoryUseCase;
  private final Counter expiredCounter;

  @Value("${inventory.reservation.sweeper-batch-size:100}")
  private int batchSize;

  public ReservationSweeperJob(
      InventoryReservationRepository reservationRepository,
      ReleaseInventoryUseCase releaseInventoryUseCase,
      MeterRegistry meterRegistry) {
    this.reservationRepository = reservationRepository;
    this.releaseInventoryUseCase = releaseInventoryUseCase;
    this.expiredCounter =
        Counter.builder("inventory.reservation.sweeper.expired")
            .description("Number of reservations released by the sweeper")
            .register(meterRegistry);
  }

  @Scheduled(fixedDelayString = "${inventory.reservation.sweeper-interval-ms:30000}")
  public void sweepExpired() {
    Instant cutoff = Instant.now();
    var expired =
        reservationRepository
            .findByStatusAndExpiresAtBefore(ReservationStatus.ACTIVE, cutoff)
            .stream()
            .limit(batchSize)
            .toList();

    if (expired.isEmpty()) {
      return;
    }

    long start = System.currentTimeMillis();
    int released = 0;
    for (var reservation : expired) {
      try {
        // Each release is its own transaction (REQUIRES_NEW). A slow release doesn't poison
        // the batch.
        releaseInventoryUseCase.releaseExpired(reservation.getUuid());
        expiredCounter.increment();
        released++;
      } catch (Exception e) {
        // ponytail: log + continue. A single bad release shouldn't kill the sweeper.
        log.error(
            "Sweeper failed to release reservationUuid={}",
            reservation.getUuid(),
            e);
      }
    }

    log.info(
        "Sweeper released: count={} of batch={} duration_ms={}",
        released,
        expired.size(),
        System.currentTimeMillis() - start);
  }

  /** Exposed for tests — returns the configured batch size. */
  public int getBatchSize() {
    return batchSize;
  }
}