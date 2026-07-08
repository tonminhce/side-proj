package vn.vnpt.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.ApplicationModule;

/**
 * OrderService — Story 4.1 (FR-30, FR-31). Owns the append-only event log + immutable
 * price snapshot for orders. Per ADR-12 the saga is intra-process; this module is the
 * saga's Order participant (Story 4.2 wires the post-payment lifecycle).
 */
@SpringBootApplication(scanBasePackages = "vn.vnpt.order")
@ApplicationModule(displayName = "order")
public class OrderApplication {

  public static void main(String[] args) {
    SpringApplication.run(OrderApplication.class, args);
  }
}