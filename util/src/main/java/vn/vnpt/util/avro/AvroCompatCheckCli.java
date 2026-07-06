package vn.vnpt.util.avro;

import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;

/**
 * CLI entry point invoked by the GitHub Actions Avro compat step (Story 0.4 / Subtask 5.4).
 *
 * <p>Usage:
 *
 * <pre>
 *   java vn.vnpt.util.avro.AvroCompatCheckCli previous.avsc proposed.avsc
 * </pre>
 *
 * <p>Prints the {@link AvroCompatCheck.CompatResult} on stdout and exits non-zero on any {@code
 * INCOMPATIBLE_*} outcome (so the GH Actions step fails the build).
 *
 * <p>Ponytail: 30 lines, no flag parsing, no JSON, no Apicurio REST call. The live registration
 * path lands in Story 1.3.
 */
public final class AvroCompatCheckCli {

  private AvroCompatCheckCli() {}

  public static void main(String[] args) {
    if (args.length != 2) {
      System.err.println("Usage: AvroCompatCheckCli <previous.avsc> <proposed.avsc>");
      System.exit(2);
    }

    AvroCompatCheck.CompatResult result;
    try (Reader previous = new FileReader(args[0]);
        Reader proposed = new FileReader(args[1])) {
      result = AvroCompatCheck.check(previous, proposed);
    } catch (IOException e) {
      System.err.println("Failed to read schema files: " + e.getMessage());
      System.exit(2);
      return;
    }

    System.out.println("avro-compat: " + result.name());
    if (result != AvroCompatCheck.CompatResult.COMPATIBLE) {
      System.exit(1);
    }
  }
}
