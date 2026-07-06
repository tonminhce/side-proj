package vn.vnpt.util.avro;

import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
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
 * <p>{@link #run(String[], PrintStream, PrintStream)} is the testable seam; {@link #main(String[])}
 * is a 4-line wrapper that wires it to real stdio + {@link System#exit(int)}.
 *
 * <p>Ponytail: 30 lines, no flag parsing, no JSON, no Apicurio REST call. The live registration
 * path lands in Story 1.3.
 */
public final class AvroCompatCheckCli {

  /** Exit code: 0 = compatible, 1 = incompatible, 2 = usage/IO error. */
  static final int EXIT_COMPATIBLE = 0;

  static final int EXIT_INCOMPATIBLE = 1;
  static final int EXIT_USAGE = 2;

  private AvroCompatCheckCli() {}

  /**
   * Run the CLI against two schema files. Returns the process exit code instead of calling {@link
   * System#exit} so tests can assert against it.
   *
   * @param args exactly two args: previous and proposed schema paths
   * @param out stream for the verdict line (mimics stdout)
   * @param err stream for usage / IO error messages (mimics stderr)
   * @return 0 on COMPATIBLE, 1 on any INCOMPATIBLE_*, 2 on usage or IO error
   */
  public static int run(String[] args, PrintStream out, PrintStream err) {
    if (args.length != 2) {
      err.println("Usage: AvroCompatCheckCli <previous.avsc> <proposed.avsc>");
      return EXIT_USAGE;
    }

    AvroCompatCheck.CompatResult result;
    try (Reader previous = new FileReader(args[0]);
        Reader proposed = new FileReader(args[1])) {
      result = AvroCompatCheck.check(previous, proposed);
    } catch (IOException e) {
      err.println("Failed to read schema files: " + e.getMessage());
      return EXIT_USAGE;
    }

    out.println("avro-compat: " + result.name());
    if (result != AvroCompatCheck.CompatResult.COMPATIBLE) {
      return EXIT_INCOMPATIBLE;
    }
    return EXIT_COMPATIBLE;
  }

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }
}
