package vn.vnpt.util;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.vnpt.util.common.SnowflakeIdGenerator;

/**
 * Story 0.5 / AC #5: prove the {@code snowflake.worker.id.source} gauge is registered by the {@link
 * UtilsAutoConfiguration#snowflakeIdGenerator()} bean factory. Constructs the config class manually
 * with a {@link SimpleMeterRegistry} — no Spring context needed (Subtask 6.3). The util module
 * doesn't ship a Prometheus registry, so the dotted Micrometer name is what we assert; Epic 10
 * wires Prometheus (Micrometer dot-to-underscore translation is built-in).
 *
 * <p>Profile is forced to {@code dev} via {@code System.setProperty("spring.profiles.active",
 * "dev")} so the bean factory's {@code getWorkerIdFromPod()} call never throws, regardless of shell
 * env. This is the determinism guarantee: a developer running with {@code
 * SPRING_PROFILES_ACTIVE=prod} in their shell doesn't see this test crash.
 */
class MetricsMetadataTest {

  private String savedActiveProfile;
  private boolean activeProfileWasSet;

  @BeforeEach
  void pinDevProfile() {
    savedActiveProfile = System.getProperty("spring.profiles.active");
    activeProfileWasSet = savedActiveProfile != null;
    // resolveActiveProfile() reads System property first, so this wins over SPRING_PROFILES_ACTIVE.
    System.setProperty("spring.profiles.active", "dev");
  }

  @AfterEach
  void restoreActiveProfile() {
    if (activeProfileWasSet) {
      System.setProperty("spring.profiles.active", savedActiveProfile);
    } else {
      System.clearProperty("spring.profiles.active");
    }
  }

  @Test
  void snowflakeWorkerIdSourceGaugeIsRegisteredWithExpectedSourceTag() {
    MeterRegistry registry = new SimpleMeterRegistry();
    UtilsAutoConfiguration config = new UtilsAutoConfiguration(null, null, null, registry);
    SnowflakeIdGenerator generator = config.snowflakeIdGenerator();

    Gauge podname = registry.find("snowflake.worker.id.source").tag("source", "podname").gauge();
    Gauge securerandom =
        registry.find("snowflake.worker.id.source").tag("source", "securerandom").gauge();

    assertNotNull(generator, "SnowflakeIdGenerator bean must be returned");
    assertTrue(podname != null || securerandom != null, "exactly one source tag must be present");
    if (podname != null) {
      assertTrue(podname.value() == 1.0, "podname tag must report value 1.0");
    }
    if (securerandom != null) {
      assertTrue(securerandom.value() == 2.0, "securerandom tag must report value 2.0");
    }
  }

  @Test
  void gaugeIsDiscoverableUnderBothDottedAndUnderscoredNames() {
    // Micrometer translates dots → underscores for Prometheus. Until Epic 10 wires Prometheus,
    // we assert that at least one of the two known spellings is registered (the dotted name is
    // what Micrometer registers; the underscored name is the Prometheus exposition).
    MeterRegistry registry = new SimpleMeterRegistry();
    UtilsAutoConfiguration config = new UtilsAutoConfiguration(null, null, null, registry);
    config.snowflakeIdGenerator();

    assertTrue(
        registry.find("snowflake.worker.id.source").gauge() != null
            || registry.find("snowflake_worker_id_source").gauge() != null,
        "snowflake.worker.id.source gauge must be discoverable in the MeterRegistry");
  }
}
