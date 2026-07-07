package vn.vnpt.payment;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit package-boundary guard for PaymentService — Story 3.1 / AC #7, #9. The {@code PaymentPort}
 * interface is the seam; the use case MUST NOT know the Stripe SDK exists.
 *
 * <p>Only the {@code application.usecase.. → infrastructure.stripe..} direction is forbidden;
 * the {@code infrastructure → application.port..} direction is the legal port-seam dependency that
 * makes the adapter implement the interface. The broad "infrastructure → application" rule would
 * flag every port implementation as a violation and is NOT what AC #7 describes.
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
}