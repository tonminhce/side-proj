package vn.vnpt.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Admin BFF — staff-facing Backend-for-Frontend (Story 1.4 / FR-6, FR-74).
 *
 * <p>Routes {@code /bff/admin/*} → {@code /api/admin/*} on backend services, gated by RBAC
 * (Story 1.4 ships the placeholder {@code X-User-Roles} header filter in {@code dev}/{@code test}
 * profiles; Story 5.5 replaces it with util's {@code CustomSecurityExpressionHandler}).
 */
@SpringBootApplication
@ComponentScan("vn.vnpt.admin")
public class AdminBffApplication {

  public static void main(String[] args) {
    SpringApplication.run(AdminBffApplication.class, args);
  }
}