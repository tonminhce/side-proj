package vn.vnpt.util.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Forbidden-dependency-pattern enforcement (Story 0.4 / AC #5).
 *
 * <p>Per architecture line 884: cross-module access must go through the {@code application/} public
 * API only. This rule asserts that no class outside {@code vn.vnpt..api..} imports {@code
 * org.springframework.web.bind.annotation.RestController} — i.e. only the API layer exposes Spring
 * MVC endpoints; everything else must call into {@code application/} instead of binding HTTP routes
 * directly.
 *
 * <p>The rule fires only after Epic 1+ adds real code; in Sprint 0 it asserts a stable negative (no
 * offending imports anywhere) so the JUnit wiring + ArchUnit runtime are exercised on every PR.
 */
class ForbiddenDependencyPatternsTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("vn.vnpt");

  @Test
  void restControllerImportsAreRestrictedToApiPackage() {
    ArchRule rule =
        noClasses()
            .that()
            .resideOutsideOfPackage("vn.vnpt..api..")
            .should()
            .dependOnClassesThat()
            .areAssignableTo(org.springframework.web.bind.annotation.RestController.class);

    rule.check(CLASSES);
  }
}
