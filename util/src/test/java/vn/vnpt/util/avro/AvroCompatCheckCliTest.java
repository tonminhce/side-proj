package vn.vnpt.util.avro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * CLI exit-code contract for the GitHub Actions Avro compat step (Story 0.4 / AC #6).
 *
 * <p>The workflow invokes this CLI as a JUnit-discoverable test target via {@code mvn -pl util test
 * -Dtest=AvroCompatCheckCliTest}; if the wiring drifts and the test no longer runs, the workflow
 * step also fails to find a matching test and the build breaks. These tests pin the contract so
 * that wiring + behaviour fail loudly in CI.
 */
class AvroCompatCheckCliTest {

  private static final String V1 =
      "{"
          + "\"type\":\"record\","
          + "\"name\":\"ProductEvent\","
          + "\"namespace\":\"vn.vnpt.catalog.events\","
          + "\"fields\":["
          + "  {\"name\":\"id\",\"type\":\"string\"},"
          + "  {\"name\":\"sku\",\"type\":\"string\"}"
          + "]}";

  private static final String V2_COMPATIBLE =
      "{"
          + "\"type\":\"record\","
          + "\"name\":\"ProductEvent\","
          + "\"namespace\":\"vn.vnpt.catalog.events\","
          + "\"fields\":["
          + "  {\"name\":\"id\",\"type\":\"string\"},"
          + "  {\"name\":\"sku\",\"type\":\"string\"},"
          + "  {\"name\":\"displayName\",\"type\":[\"null\",\"string\"],\"default\":null}"
          + "]}";

  private static final String V2_INCOMPATIBLE_BACKWARD =
      "{"
          + "\"type\":\"record\","
          + "\"name\":\"ProductEvent\","
          + "\"namespace\":\"vn.vnpt.catalog.events\","
          + "\"fields\":["
          + "  {\"name\":\"id\",\"type\":\"string\"},"
          + "  {\"name\":\"sku\",\"type\":\"string\"},"
          + "  {\"name\":\"newRequired\",\"type\":\"string\"}"
          + "]}";

  private static final String V2_INCOMPATIBLE_FORWARD =
      "{"
          + "\"type\":\"record\","
          + "\"name\":\"ProductEvent\","
          + "\"namespace\":\"vn.vnpt.catalog.events\","
          + "\"fields\":["
          + "  {\"name\":\"id\",\"type\":\"string\"}"
          + "]}";

  // Type change int -> string is both backward AND forward incompatible (reaches
  // INCOMPATIBLE_BOTH).
  private static final String V2_INCOMPATIBLE_BOTH =
      "{"
          + "\"type\":\"record\","
          + "\"name\":\"ProductEvent\","
          + "\"namespace\":\"vn.vnpt.catalog.events\","
          + "\"fields\":["
          + "  {\"name\":\"id\",\"type\":\"string\"},"
          + "  {\"name\":\"sku\",\"type\":\"int\"}"
          + "]}";

  private record Captured(int exit, String out, String err) {}

  private static Captured runCli(String[] args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int exit = AvroCompatCheckCli.run(args, new PrintStream(out), new PrintStream(err));
    return new Captured(exit, out.toString(), err.toString());
  }

  private static Path writeSchema(Path dir, String name, String content) throws IOException {
    Path file = dir.resolve(name);
    Files.writeString(file, content);
    return file;
  }

  @Test
  void compatibleSchemaExitsZero(@TempDir Path tmp) throws IOException {
    Path previous = writeSchema(tmp, "v1.avsc", V1);
    Path proposed = writeSchema(tmp, "v2.avsc", V2_COMPATIBLE);

    Captured c = runCli(new String[] {previous.toString(), proposed.toString()});

    assertEquals(AvroCompatCheckCli.EXIT_COMPATIBLE, c.exit);
    assertTrue(c.out.contains("avro-compat: COMPATIBLE"), "stdout was: " + c.out);
    assertEquals("", c.err);
  }

  @Test
  void backwardIncompatibleSchemaExitsOne(@TempDir Path tmp) throws IOException {
    Path previous = writeSchema(tmp, "v1.avsc", V1);
    Path proposed = writeSchema(tmp, "v2.avsc", V2_INCOMPATIBLE_BACKWARD);

    Captured c = runCli(new String[] {previous.toString(), proposed.toString()});

    assertEquals(AvroCompatCheckCli.EXIT_INCOMPATIBLE, c.exit);
    assertTrue(c.out.contains("avro-compat: INCOMPATIBLE_BACKWARD"), "stdout was: " + c.out);
    assertEquals("", c.err);
  }

  @Test
  void forwardIncompatibleSchemaExitsOne(@TempDir Path tmp) throws IOException {
    Path previous = writeSchema(tmp, "v1.avsc", V1);
    Path proposed = writeSchema(tmp, "v2.avsc", V2_INCOMPATIBLE_FORWARD);

    Captured c = runCli(new String[] {previous.toString(), proposed.toString()});

    assertEquals(AvroCompatCheckCli.EXIT_INCOMPATIBLE, c.exit);
    assertTrue(c.out.contains("avro-compat: INCOMPATIBLE_FORWARD"), "stdout was: " + c.out);
    assertEquals("", c.err);
  }

  @Test
  void bothIncompatibleSchemaExitsOne(@TempDir Path tmp) throws IOException {
    Path previous = writeSchema(tmp, "v1.avsc", V1);
    Path proposed = writeSchema(tmp, "v2.avsc", V2_INCOMPATIBLE_BOTH);

    Captured c = runCli(new String[] {previous.toString(), proposed.toString()});

    assertEquals(AvroCompatCheckCli.EXIT_INCOMPATIBLE, c.exit);
    assertTrue(c.out.contains("avro-compat: INCOMPATIBLE_BOTH"), "stdout was: " + c.out);
  }

  @Test
  void wrongArgCountExitsUsageCodeAndPrintsUsage() {
    Captured c = runCli(new String[] {"only-one-arg"});

    assertEquals(AvroCompatCheckCli.EXIT_USAGE, c.exit);
    assertEquals("", c.out);
    assertTrue(c.err.contains("Usage:"), "stderr was: " + c.err);
  }

  @Test
  void missingFileExitsUsageCode(@TempDir Path tmp) {
    Path existing = tmp.resolve("v1.avsc");
    Path missing = tmp.resolve("does-not-exist.avsc");

    Captured c = runCli(new String[] {existing.toString(), missing.toString()});

    assertEquals(AvroCompatCheckCli.EXIT_USAGE, c.exit);
    assertEquals("", c.out);
    assertTrue(c.err.contains("Failed to read schema files"), "stderr was: " + c.err);
    assertFalse(c.err.isBlank(), "stderr should not be blank on IO error");
  }
}
