package vn.vnpt.util.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Repository-wide request-body logger deny-list — Story 3.3 / FR-29 / R-15 / ADR-23.
 *
 * <p>Three mechanisms that could log request bodies (any one is a PCI backdoor):
 * <ol>
 *   <li>Subclassing {@link org.springframework.web.filter.AbstractRequestLoggingFilter}</li>
 *   <li>Implementing {@link org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdvice}
 *       (intercept-and-log body before deserialization)</li>
 *   <li>Calling Spring's body-reading methods ({@code getInputStream()}, {@code getReader()},
 *       {@code readAllBytes()}) from anywhere other than the documented webhook + web packages</li>
 * </ol>
 *
 * <p>Lives in {@code util/archunit/} so the rule scopes across {@code vn.vnpt.*} (every service),
 * not just payment. The payment-service-local ArchUnit test is a redundant secondary guard.
 */
class RequestBodyLoggerDenyListTest {

  @Test
  void noClass_subclassesAbstractRequestLoggingFilter() {
    noClasses()
        .that()
        .resideInAPackage("vn.vnpt..")
        .should()
        .beAssignableTo(org.springframework.web.filter.AbstractRequestLoggingFilter.class)
        .because("R-15 / FR-29: request-body loggers are deny-listed by design; the Stripe"
            + " Elements iframe keeps PAN off our servers; this rule prevents backdoor logging.")
        .check(new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("vn.vnpt"));
  }

  @Test
  void noClass_implementsRequestBodyAdvice() {
    noClasses()
        .that()
        .resideInAPackage("vn.vnpt..")
        .and().doNotHaveFullyQualifiedName(
            "org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdvice")
        .should()
        .beAssignableTo(org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdvice.class)
        .because("R-15 / FR-29: RequestBodyAdvice can intercept bodies before deserialization;"
            + " any implementation is a potential PAN leak path.")
        .check(new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("vn.vnpt"));
  }
}