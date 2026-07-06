/*
 * Thrown when Snowflake worker-id cannot be derived from POD_NAME in non-dev profiles. Per ADR-22,
 * collisions must surface at deploy time. See RISK-REGISTER.md R-08 (OP-05 root cause) for the
 * underlying risk this exception is wired to mitigate.
 */
package vn.vnpt.util.common;

/**
 * Signals that {@link SnowflakeIdGenerator#getWorkerIdFromPod()} cannot derive a deterministic
 * worker id in a profile where {@code SecureRandom} fallback is intentionally disallowed (i.e. any
 * profile other than {@code dev}). The exception message carries the active Spring profile, the
 * observed {@code POD_NAME} value, and a one-line remediation hint per Story 0.5 AC #7.
 */
public class WorkerIdMissingException extends RuntimeException {

  public WorkerIdMissingException(String message) {
    super(message);
  }
}
