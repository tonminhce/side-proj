package vn.vnpt.payment.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Stable idempotency-key factory for outbound payment calls — ADR-11 + NFR-IDEM-2 + FR-25 (DI-02 root
 * cause). Per architecture §6.2: per-service artifact at {@code services/<each>/infrastructure/}.
 */
public final class IdempotencyKey {

  private static final HexFormat HEX = HexFormat.of();

  private IdempotencyKey() {}

  /**
   * Lowercase-hex SHA-256 of {@code orderUuid + ":" + sagaStep}; stable across calls (FR-25, NFR-IDEM-2).
   *
   * @throws IllegalArgumentException if {@code sagaStep} is null or blank.
   */
  public static String forOrderStep(long orderUuid, String sagaStep) {
    if (sagaStep == null || sagaStep.isBlank()) {
      throw new IllegalArgumentException("sagaStep must not be null or blank");
    }
    String input = orderUuid + ":" + sagaStep;
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      return HEX.formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is mandatory in every JRE; treat absence as a JVM bug, not a runtime condition.
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}