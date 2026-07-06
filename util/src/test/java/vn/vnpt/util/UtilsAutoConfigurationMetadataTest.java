package vn.vnpt.util;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;

/**
 * Guards the Spring Boot 4 autoconfig contract that downstream services depend on. Story 0.1 / R-01
 * removed the inherited {@code <parent>} pom; this test fails loudly if that cleanup accidentally
 * deletes the autoconfig metadata or the entry-point class.
 */
class UtilsAutoConfigurationMetadataTest {

  private static final String AUTOCONFIG_IMPORTS =
      "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

  @Test
  void autoconfigImportsFileRegistersUtilsAutoConfiguration() throws Exception {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(AUTOCONFIG_IMPORTS)) {
      assertNotNull(
          in, AUTOCONFIG_IMPORTS + " must be on the classpath (Spring Boot 4 autoconfig contract)");
      String contents;
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        contents = reader.lines().reduce("", (acc, line) -> acc + line + "\n");
      }
      assertTrue(
          contents.contains(UtilsAutoConfiguration.class.getName()),
          "autoconfig imports must register " + UtilsAutoConfiguration.class.getName());
    }
  }

  @Test
  void utilsAutoConfigurationClassIsAnnotatedAsSpringConfiguration() {
    assertNotNull(
        UtilsAutoConfiguration.class.getAnnotation(Configuration.class),
        UtilsAutoConfiguration.class.getName()
            + " must carry @Configuration for Spring to pick it up");
  }
}
