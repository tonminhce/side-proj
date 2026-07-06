package vn.vnpt.admin;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit boundary test for the admin BFF (Story 1.4 / Subtask 5.7).
 *
 * <p>The BFF depends on the catalog's {@code application.query..} DTOs (legitimate — the
 * WebClient response is typed {@code Page<ProductSummary>}), but MUST NOT depend on the
 * catalog's {@code infrastructure..} classes — the HTTP boundary is the contract.
 */
class AdminBffPackageBoundaryTest {

  @Test
  void bff_doesNotDependOnCatalogInfrastructure() {
    noClasses()
        .that()
        .resideInAPackage("vn.vnpt.admin..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("vn.vnpt.catalog.infrastructure..")
        .because("BFF depends on catalog via HTTP boundary, not on catalog internals.")
        .check(
            new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("vn.vnpt.admin", "vn.vnpt.catalog.application.query"));
  }
}