package vn.vnpt.order;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit package-boundary guards for OrderService — Story 4.1 follow-up, FR-30 / ADR-14.
 * The 3 order-local rules mirror {@code services/payment/.../PaymentPortContractTest} and are
 * the order module's slice of the repo-wide boundary contract. The request-body-logger deny-list
 * is already enforced repo-wide by {@code util/.../archunit/RequestBodyLoggerDenyListTest.java}
 * (Story 3.3), so we do not duplicate it here.
 *
 * <p>Only the {@code application.usecase.. → infrastructure.{entity,repository,outbox}..} directions
 * are forbidden; the {@code infrastructure → application.port..} direction is the legal port-seam
 * dependency.
 */
class OrderPortContractTest {

  @Test
  void application_usecase_mayNotImportJpaEntities() {
    noClasses()
        .that()
        .resideInAPackage("vn.vnpt.order.application.usecase..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("vn.vnpt.order.infrastructure.entity..")
        .because(
            "The use case depends on the AppendOrderTransitionCommand record (a port DTO), not"
                + " the JPA entity — keeps the saga contract mockable without a Hibernate Session.")
        .check(
            new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("vn.vnpt.order"));
  }

  @Test
  void application_usecase_mayNotImportJpaRepositories() {
    noClasses()
        .that()
        .resideInAPackage("vn.vnpt.order.application.usecase..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("vn.vnpt.order.infrastructure.repository..")
        .because(
            "The use case depends on the AppendOrderTransitionCommand record + port, not the"
                + " Spring Data repository — the broader rule payment already enforces.")
        .check(
            new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("vn.vnpt.order"));
  }

  @Test
  void application_usecase_mayNotImportOutboxInfrastructure() {
    noClasses()
        .that()
        .resideInAPackage("vn.vnpt.order.application.usecase..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("vn.vnpt.order.infrastructure.outbox..")
        .because(
            "The use case calls the OrderTransitionAppender port, not the Modulith outbox"
                + " publisher directly — the port seam is what makes the cross-service Kafka"
                + " bridge (Story 4.1 follow-up) substitutable in tests.")
        .check(
            new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("vn.vnpt.order"));
  }
}
