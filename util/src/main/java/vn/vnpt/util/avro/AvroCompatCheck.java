package vn.vnpt.util.avro;

import io.apicurio.registry.content.ContentHandle;
import io.apicurio.registry.rules.compatibility.AvroCompatibilityChecker;
import io.apicurio.registry.rules.compatibility.CompatibilityLevel;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Collections;
import java.util.List;

/**
 * Avro schema compatibility checker used by the CI gate (Story 0.4 / NFR-MIG-2).
 *
 * <p>Wraps Apicurio's {@link AvroCompatibilityChecker} so downstream code can ask a single
 * question: <em>is this new schema compatible with the prior one</em>? Both {@link
 * CompatibilityLevel#BACKWARD backward} and {@link CompatibilityLevel#FORWARD forward}
 * compatibility are checked — ADR-15 / NFR-MIG-2 require strict dual-direction compat for the
 * event-driven architecture.
 *
 * <p>Sprint 0 ships this producer + CLI hook; the wire-up of the schema registration path is
 * deferred to Story 1.3 (per the Dev Notes plan).
 */
public final class AvroCompatCheck {

  private AvroCompatCheck() {}

  /**
   * Check whether {@code proposed} is compatible with {@code previous} in BOTH backward and forward
   * directions.
   *
   * @param previous the previously-registered Avro schema (as a character stream)
   * @param proposed the newly-proposed Avro schema (as a character stream)
   * @return a {@link CompatResult} that distinguishes which direction (if any) failed
   */
  public static CompatResult check(Reader previous, Reader proposed) {
    String previousSchema = readAll(previous);
    String proposedSchema = readAll(proposed);

    ContentHandle existing = ContentHandle.create(previousSchema);
    ContentHandle candidate = ContentHandle.create(proposedSchema);
    List<ContentHandle> existingList = Collections.singletonList(existing);

    AvroCompatibilityChecker checker = new AvroCompatibilityChecker();

    boolean backwardOk =
        checker
            .testCompatibility(
                CompatibilityLevel.BACKWARD, existingList, candidate, Collections.emptyMap())
            .isCompatible();
    boolean forwardOk =
        checker
            .testCompatibility(
                CompatibilityLevel.FORWARD, existingList, candidate, Collections.emptyMap())
            .isCompatible();

    if (backwardOk && forwardOk) {
      return CompatResult.COMPATIBLE;
    }
    if (!backwardOk && forwardOk) {
      return CompatResult.INCOMPATIBLE_BACKWARD;
    }
    if (backwardOk && !forwardOk) {
      return CompatResult.INCOMPATIBLE_FORWARD;
    }
    return CompatResult.INCOMPATIBLE_BOTH;
  }

  private static String readAll(Reader reader) {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader br = new BufferedReader(reader)) {
      String line;
      while ((line = br.readLine()) != null) {
        sb.append(line).append('\n');
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("Failed to read Avro schema content", e);
    }
    return sb.toString();
  }

  /** Outcome of a dual-direction Avro compatibility check. */
  public enum CompatResult {
    COMPATIBLE,
    INCOMPATIBLE_BACKWARD,
    INCOMPATIBLE_FORWARD,
    INCOMPATIBLE_BOTH
  }
}
