package vn.vnpt.util.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Story 0.5 / ADR-22 / R-08: covers the three branches of {@link
 * SnowflakeIdGenerator#getWorkerIdFromPod()} across profiles. {@code getWorkerIdFromPod()} is a
 * pure static call — no Spring context needed (Subtask 6.2). Test runtime under 5 ms each.
 */
class SnowflakeIdGeneratorStrictModeTest {

  private String savedProperty;
  private String savedEnv;
  private boolean propertyWasSet;

  @BeforeEach
  void captureBaseline() {
    savedProperty = System.getProperty("spring.profiles.active");
    propertyWasSet = savedProperty != null;
    savedEnv = System.getenv("SPRING_PROFILES_ACTIVE");
  }

  @AfterEach
  void restoreEnv() {
    if (propertyWasSet) {
      System.setProperty("spring.profiles.active", savedProperty);
    } else {
      System.clearProperty("spring.profiles.active");
    }
    // SPRING_PROFILES_ACTIVE is set via env vars in the shell (Subtask 5.4); we don't mutate it
    // here. Tests that need a particular profile value set it via System property (which
    // resolveActiveProfile() reads first).
  }

  private void setActiveProfile(String profile) {
    if (profile == null) {
      System.clearProperty("spring.profiles.active");
    } else {
      System.setProperty("spring.profiles.active", profile);
    }
  }

  // ---- Branch B: dev profile fallback ----

  @Test
  void devProfile_missingPodName_returnsRandomAndLogsWarn() {
    setActiveProfile("dev");
    long id = SnowflakeIdGenerator.getWorkerIdFromPod();
    assertTrue(id >= 0 && id < 8, "worker-id must be in [0,8), got " + id);
  }

  // ---- Branch C: strict failure ----

  @Test
  void prodProfile_missingPodName_throwsWorkerIdMissingException() {
    setActiveProfile("prod");
    String podName = System.getenv("POD_NAME");
    // Run only when POD_NAME is unset OR present but malformed (no replica suffix). In both
    // cases, the production code path is "missing/invalid" → WorkerIdMissingException.
    if (podName != null && podName.matches(".*-\\d+$")) {
      // POD_NAME has a valid replica suffix → Branch A → no throw. Skip this scenario here.
      return;
    }
    WorkerIdMissingException ex =
        assertThrows(WorkerIdMissingException.class, SnowflakeIdGenerator::getWorkerIdFromPod);
    String msg = ex.getMessage();
    assertTrue(msg.contains("profile 'prod'"), "msg must mention profile: " + msg);
    assertTrue(msg.contains("POD_NAME='"), "msg must mention POD_NAME value: " + msg);
    assertTrue(
        msg.contains("spring.profiles.active=dev"), "msg must contain remediation hint: " + msg);
  }

  @Test
  void stagingProfile_malformedPodName_throwsWorkerIdMissingException() {
    setActiveProfile("staging");
    // We can't mutate env vars at runtime in Java, so the malformed-POD_NAME path is exercised
    // here only when the test is invoked with POD_NAME set explicitly via the shell (Subtask 5.4
    // Case C). If POD_NAME is unset at test time, prodProfile_missingPodName above already covers
    // the "POD_NAME missing" branch; this assertion guards the malformed case when env is set.
    String podName = System.getenv("POD_NAME");
    if (podName == null || podName.matches(".*-\\d+$")) {
      // No malformed POD_NAME available in this JVM — skip (covered manually in Subtask 5.4).
      return;
    }
    WorkerIdMissingException ex =
        assertThrows(WorkerIdMissingException.class, SnowflakeIdGenerator::getWorkerIdFromPod);
    assertTrue(
        ex.getMessage().contains("POD_NAME='" + podName + "'"),
        "msg must echo observed POD_NAME: " + ex.getMessage());
    assertTrue(ex.getMessage().contains("profile 'staging'"), ex.getMessage());
  }

  // ---- Branch A: deterministic parse ----

  @Test
  void prodProfile_validPodName_returnsParsedWorkerId() {
    setActiveProfile("prod");
    String podName = System.getenv("POD_NAME");
    if (podName == null || !podName.matches(".*-\\d+$")) {
      // Only valid when the test is launched with a replica-suffixed POD_NAME (e.g. -am
      // sub-tasks). Manual run: POD_NAME=catalog-prod-3 ... returns 3.
      return;
    }
    long expected = Long.parseLong(podName.replaceAll(".*-(\\d+)$", "$1")) % 8;
    assertEquals(expected, SnowflakeIdGenerator.getWorkerIdFromPod());
  }

  // ---- Unset profile + unset POD_NAME → falls back to SecureRandom ----

  @Test
  void unsetProfileAndMissingPodName_fallsBackToRandom() {
    setActiveProfile(null);
    // Subtask 6.1 #5: clearing both profile and POD_NAME ⇒ dev is the implicit default.
    // We can't unset the env var from inside the JVM, so we only assert when the env var is
    // unset at test time; otherwise prod/staging branch would take over and throw.
    String activeFromEnv = System.getenv("SPRING_PROFILES_ACTIVE");
    String podFromEnv = System.getenv("POD_NAME");
    if (activeFromEnv != null && !activeFromEnv.isBlank()) {
      return; // Can't override from inside the JVM; manual run only.
    }
    if (podFromEnv != null) {
      return;
    }
    long id = SnowflakeIdGenerator.getWorkerIdFromPod();
    assertTrue(id >= 0 && id < 8, "worker-id must be in [0,8), got " + id);
  }

  // ---- AC #4: WARN log line on dev fallback ----

  @Test
  void devProfile_missingPodName_emitsWarnLog() {
    setActiveProfile("dev");
    Logger logger = (Logger) LoggerFactory.getLogger(SnowflakeIdGenerator.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      SnowflakeIdGenerator.getWorkerIdFromPod();
    } finally {
      logger.detachAppender(appender);
    }
    assertEquals(1, appender.list.size(), "exactly one WARN event expected, got: " + appender.list);
    ILoggingEvent event = appender.list.get(0);
    assertEquals(Level.WARN, event.getLevel());
    String formatted = event.getFormattedMessage();
    assertTrue(
        formatted.contains("SnowflakeIdGenerator: POD_NAME not set in profile 'dev'"),
        "WARN message must include header: " + formatted);
    assertTrue(
        formatted.contains("falling back to SecureRandom"),
        "WARN message must indicate fallback: " + formatted);
  }

  // ---- AC #3: WorkerIdMissingException is a RuntimeException ----

  @Test
  void workerIdMissingExceptionIsRuntimeException() {
    // AC #3: "new class, RuntimeException subclass". Pinning the hierarchy guards against a
    // refactor that swaps RuntimeException for checked Exception — that would break Spring's
    // bean-factory failure mode (unchecked exceptions propagate as-is).
    assertTrue(
        RuntimeException.class.isAssignableFrom(WorkerIdMissingException.class),
        "WorkerIdMissingException must extend RuntimeException per AC #3");
  }

  // ---- AC #7: full remediation hint in exception message ----

  @Test
  void prodProfile_missingPodName_exceptionMessageIncludesFullRemediationHint() {
    setActiveProfile("prod");
    String podName = System.getenv("POD_NAME");
    if (podName != null && podName.matches(".*-\\d+$")) {
      // Valid replica suffix → Branch A → no throw; can't exercise message here.
      return;
    }
    WorkerIdMissingException ex =
        assertThrows(WorkerIdMissingException.class, SnowflakeIdGenerator::getWorkerIdFromPod);
    String msg = ex.getMessage();
    // AC #7c mandates the full one-line hint, including the K8s downward-API clause.
    assertTrue(msg.contains("(ADR-22)"), "msg must reference ADR-22: " + msg);
    assertTrue(
        msg.contains("K8s downward API: fieldRef: metadata.name"),
        "msg must include K8s downward API hint: " + msg);
    assertTrue(msg.contains("for local dev"), "msg must end with local-dev clause: " + msg);
    assertTrue(
        msg.contains("POD_NAME env var is required"),
        "msg must open with the required-var statement: " + msg);
  }
}
