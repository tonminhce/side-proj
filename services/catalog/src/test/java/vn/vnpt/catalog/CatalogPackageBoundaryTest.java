package vn.vnpt.catalog;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Modulith package-boundary enforcement for CatalogService (Story 1.1 / AC #10, Story 1.2 /
 * AC #16).
 *
 * <p>Rules:
 *
 * <ol>
 *   <li>{@link #catalog_doesNotDependOnSiblingServices()} — no class under {@code
 *       vn.vnpt.catalog..} may depend on a sibling service's package. CatalogService communicates
 *       with siblings via Kafka events (ADR-01, ADR-04) — Java imports are forbidden (ADR-03).
 *   <li>{@link #catalog_doesNotDependOnUtilTenantPackage()} — v1 is single-tenant per
 *       architecture-detail.md line 76; {@code @ComponentScan(basePackages = "vn.vnpt.catalog")}
 *       intentionally excludes util's {@code config.tenant} package. A refactor that broadens the
 *       scan would silently pull {@code TenantInterceptor} / {@code TenantStorage} beans into the
 *       catalog context.
 *   <li>{@link #domain_doesNotDependOnInfrastructure()} (Story 1.2) — the DDD dependency
 *       direction is {@code infrastructure → domain}, not the other way around. Domain code
 *       pulls in entities only; the application layer pulls in ports; infrastructure is
 *       allowed to know both. A reverse dependency would let the domain layer reach into
 *       {@code ModulithOutboxPublisher} directly, bypassing {@code OutboxPublisher}.
 * </ol>
 *
 * <p>util ({@code vn.vnpt.util..}) outside the tenant package IS allowed — it's the shared library.
 *
 * <p>Story 1.1 shipped a single production class ({@link CatalogApplication}). Story 1.2 adds the
 * domain/application/infrastructure split and the new entities. The rules run as regression
 * guards against future code that drifts the layering.
 *
 * <p>Idiom mirrors {@code util/.../archunit/ModulithPackageBoundaryTest.java} (Story 0.4), scoped
 * to catalog.
 */
class CatalogPackageBoundaryTest {

  @Test
  void catalog_doesNotDependOnSiblingServices() {
    noClasses()
        .that()
        .resideInAPackage("vn.vnpt.catalog..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "vn.vnpt.inventory..",
            "vn.vnpt.cart..",
            "vn.vnpt.checkout..",
            "vn.vnpt.payment..",
            "vn.vnpt.order..",
            "vn.vnpt.fulfillment..",
            "vn.vnpt.returns..",
            "vn.vnpt.customer..",
            "vn.vnpt.search..",
            "vn.vnpt.notification..",
            "vn.vnpt.admin..",
            "vn.vnpt.pricing..",
            "vn.vnpt.invoice..")
        .because(
            "CatalogService communicates with sibling services via Kafka events"
                + " (ADR-01, ADR-04), NOT Java imports (ADR-03).")
        .check(
            new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("vn.vnpt.catalog"));
  }

  @Test
  void catalog_doesNotDependOnUtilTenantPackage() {
    noClasses()
        .that()
        .resideInAPackage("vn.vnpt.catalog..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("vn.vnpt.util.config.tenant..")
        .because(
            "v1 is single-tenant per architecture-detail.md line 76; @ComponentScan(basePackages"
                + " = \"vn.vnpt.catalog\") excludes util's tenant beans. A broader scan would"
                + " silently pull TenantInterceptor/TenantStorage into the catalog context.")
        .check(
            new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("vn.vnpt.catalog"));
  }

  @Test
  void domain_doesNotDependOnInfrastructure() {
    noClasses()
        .that()
        .resideInAPackage("vn.vnpt.catalog.domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("vn.vnpt.catalog.infrastructure..")
        .because("DDD layering: domain depends on nothing; infrastructure depends on domain.")
        .check(
            new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("vn.vnpt.catalog"));
  }

  @Test
  void application_doesNotDependOnInfrastructure() {
    noClasses()
        .that()
        .resideInAPackage("vn.vnpt.catalog.application..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("vn.vnpt.catalog.infrastructure..")
        .because(
            "DDD layering: application depends on ports in application.port, not on"
                + " infrastructure adapters. UpdateProductUseCase / UpdatePriceUseCase /"
                + " CreateProductUseCase inject ProductRepository / VariantRepository /"
                + " OutboxPublisher (all ports), NOT ModulithOutboxWriter or"
                + " ModulithOutboxPublisher.")
        .check(
            new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("vn.vnpt.catalog"));
  }
}