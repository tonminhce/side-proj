package vn.vnpt.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.modulith.ApplicationModule;

/**
 * PaymentService — Story 3.1 (FR-25, ADR-11, ADR-21). v1 ships the stable idempotency-key contract
 * (DI-02 root cause) and a test-double Stripe adapter; real Stripe SDK wiring lands in Story 3.3.
 *
 * <p>Architectural references:
 *
 * <ul>
 *   <li><b>ADR-01 (Modulith outbox)</b> — {@code @ApplicationModule} marks this class as a logical
 *       module boundary. Outbox table per service; CDC propagates read-side projections.
 *   <li><b>ADR-03 (database-per-service)</b> — PaymentService owns {@code payment_db}; no
 *       cross-database joins (architecture.md line 212).
 *   <li><b>ADR-11 (idempotency-key strategy)</b> — every outbound payment call uses
 *       {@code IdempotencyKey.forOrderStep(orderUuid, "payment.authorize")} so Kafka redelivery /
 *       Modulith outbox bridge redelivery / saga-recovery re-derivation all hit Stripe with the
 *       same key. Story 3.1 ships the contract; Story 3.3 wires the real SDK call.
 *   <li><b>ADR-21 (webhook dedup)</b> — handled in Story 3.2 (FR-26).
 * </ul>
 *
 * <p>v1 is single-tenant; {@code tenant_id = 'default'} on every payment table per
 * architecture-detail.md line 76. Multi-tenant disposition lands in Epic 5.
 */
@SpringBootApplication
@ComponentScan(basePackages = "vn.vnpt.payment")
@ApplicationModule(displayName = "payment")
public class PaymentApplication {

  public static void main(String[] args) {
    SpringApplication.run(PaymentApplication.class, args);
  }
}