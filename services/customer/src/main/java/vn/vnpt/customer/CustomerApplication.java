package vn.vnpt.customer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import vn.vnpt.util.events.ModulithOutboxPublisher;

/**
 * CustomerService — Story 5.1 / FR-45, FR-47. Owns the Customer aggregate + the Address book.
 * Per ADR-03 the service has its own `customer_db`; per FR-45 the Customer is separate from
 * the auth User (auth lands in Story 5.4). No Modulith outbox bridge in v1 (no events emitted
 * from Customer in this story; saga integration lands with Story 5.x).
 */
@SpringBootApplication(scanBasePackages = "vn.vnpt.customer")
@Import(ModulithOutboxPublisher.ModulithBridgeSupport.class)
public class CustomerApplication {

  public static void main(String[] args) {
    SpringApplication.run(CustomerApplication.class, args);
  }
}