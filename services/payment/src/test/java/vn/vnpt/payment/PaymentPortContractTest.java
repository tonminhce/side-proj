package vn.vnpt.payment;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit package-boundary guards for PaymentService — Story 3.1 / AC #7, #9, Story 3.2 / AC #11,
 * and Story 3.3 / AC #6, #9. The {@code PaymentPort} interface is the seam; the use case MUST NOT
 * know the Stripe SDK or the JPA entities / repositories exist. Plus Story 3.3 forbids any
 * request-body logger (R-15 mitigation, FR-29).
 *
 * <p>Only the {@code application.usecase.. → infrastructure.{stripe,entity,repository}..} directions
 * are forbidden; the {@code infrastructure → application.port..} direction is the legal port-seam
 * dependency that makes the adapter implement the interface. A blanket "infrastructure →
 * application" rule would flag every port implementation as a violation and is NOT what these ACs
 * describe.
 */
class PaymentPortContractTest {

  @Test
  void application_usecase_mayNotImportStripeAdapter() {
    noClasses()
        .that()
        .resideInAPackage("vn.vnpt.payment.application.usecase..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("vn.vnpt.payment.infrastructure.stripe..")
        .because(
            "The use case talks to the port interface, not the Stripe SDK adapter —"
                + " the port seam is the whole point of FR-25 testability.")
        .check(
            new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("vn.vnpt.payment"));
  }

  @Test
  void application_usecase_mayNotImportJpaEntities() {
    noClasses()
        .that()
        .resideInAPackage("vn.vnpt.payment.application.usecase..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("vn.vnpt.payment.infrastructure.entity..")
        .because(
            "The use case depends on the WebhookDedupPort interface (FR-26 / ADR-21), not the"
                + " JPA entity — keeps the dedup contract mockable without a Hibernate Session.")
        .check(
            new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("vn.vnpt.payment"));
  }

  @Test
  void application_usecase_mayNotImportJpaRepositories() {
    noClasses()
        .that()
        .resideInAPackage("vn.vnpt.payment.application.usecase..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("vn.vnpt.payment.infrastructure.repository..")
        .because(
            "The use case depends on the WebhookDedupPort interface (FR-26 / ADR-21), not the"
                + " Spring Data repository — the broader rule that catalog already enforces.")
        .check(
            new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("vn.vnpt.payment"));
  }

  // Story 3.3 (R-15, FR-29): deny-list the request-body logger. AbstractRequestLoggingFilter is
  // Spring's default body-logger; any subclass would re-introduce PAN-shaped bytes into logs.
  @Test
  void noRequestBodyLogger_subclassesAbstractRequestLoggingFilter() {
    noClasses()
        .that()
        .resideInAPackage("vn.vnpt.payment..")
        .should()
        .beAssignableTo(org.springframework.web.filter.AbstractRequestLoggingFilter.class)
        .because(
            "R-15 / FR-29: request-body loggers are deny-listed by design. The Stripe Elements"
                + " iframe keeps PAN off our servers; this rule prevents backdoor logging.")
        .check(
            new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("vn.vnpt.payment"));
  }
}