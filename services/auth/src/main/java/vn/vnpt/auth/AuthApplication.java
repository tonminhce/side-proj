package vn.vnpt.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.ApplicationModule;
import vn.vnpt.util.events.ModulithOutboxPublisher;

/**
 * AuthService — Story 5.4 / FR-73, FR-75, FR-76 (solves AT-02). Owns the User aggregate +
 * the email + password auth + account lockout + JWT issuance. MFA + email-verify are deferred
 * to follow-up stories.
 */
@SpringBootApplication(scanBasePackages = "vn.vnpt.auth")
@ApplicationModule(displayName = "auth")
@Import(ModulithOutboxPublisher.ModulithBridgeSupport.class)
public class AuthApplication {

  public static void main(String[] args) {
    SpringApplication.run(AuthApplication.class, args);
  }
}