package vn.vnpt.checkout.infrastructure.outbox;

import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import vn.vnpt.checkout.application.port.OutboxPublisher;
import vn.vnpt.checkout.domain.Order;
import vn.vnpt.checkout.domain.event.OrderCreatedEvent;
import vn.vnpt.checkout.domain.event.OrderFailedEvent;
import vn.vnpt.checkout.domain.event.OrderPaymentPendingEvent;
import vn.vnpt.checkout.domain.event.OrderStockReservedEvent;
import vn.vnpt.util.common.SnowflakeIdGenerator;
import vn.vnpt.util.events.HmacEventSigner;
import vn.vnpt.util.events.JcsCanonicalJson;

/**
 * Order lifecycle event publisher — Story 2.5 / FR-22, FR-23 (ADR-12 / ADR-20 producer HMAC).
 *
 * <p>Mirrors {@link CheckoutEventPublisher} one-for-one: HMAC over JCS-canonical payload via
 * {@code util.HmacEventSigner} + {@code util.JcsCanonicalJson}; the signature lands in
 * {@code outbox.signatures} JSONB. The 4 events are: {@code order.created},
 * {@code order.stock_reserved}, {@code order.payment_pending}, {@code order.failed}.
 *
 * <p>All four methods go through {@link OutboxPublisher#append} which fires
 * {@code ApplicationEventPublisher.publishEvent(event)} synchronously, so any in-process
 * {@code @TransactionalEventListener} consumer sees the row in the same transaction.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventPublisher {

  public static final String ORDER_CREATED_TOPIC = "order.created";
  public static final String ORDER_STOCK_RESERVED_TOPIC = "order.stock_reserved";
  public static final String ORDER_PAYMENT_PENDING_TOPIC = "order.payment_pending";
  public static final String ORDER_FAILED_TOPIC = "order.failed";

  private final OutboxPublisher outbox;
  private final ObjectMapper objectMapper;

  @Value("${checkout.events.hmac-secret}")
  private String checkoutServiceSecret;

  public void publishOrderCreated(Order order) {
    Instant now = Instant.now();
    OrderCreatedEvent payload = OrderCreatedEvent.builder()
        .eventId(SnowflakeIdGenerator.generateId())
        .aggregateType("Order")
        .aggregateId(order.getUuid())
        .occurredAt(now)
        .orderUuid(order.getUuid())
        .cartUuid(order.getCartUuid())
        .checkoutUuid(order.getCheckoutUuid())
        .paymentIntentId(order.getPaymentIntentId())
        .tenantId(order.getTenantId())
        .build();
    Map<String, String> signatures = Map.of("hmac_sha256", sign(payload));
    outbox.append("Order", order.getUuid(), ORDER_CREATED_TOPIC, payload, signatures);
    log.debug("order.created published: order={} checkout={}", order.getUuid(), order.getCheckoutUuid());
  }

  public void publishOrderStockReserved(Order order, Long reservationUuid) {
    Instant now = Instant.now();
    OrderStockReservedEvent payload = OrderStockReservedEvent.builder()
        .eventId(SnowflakeIdGenerator.generateId())
        .aggregateType("Order")
        .aggregateId(order.getUuid())
        .occurredAt(now)
        .orderUuid(order.getUuid())
        .cartUuid(order.getCartUuid())
        .checkoutUuid(order.getCheckoutUuid())
        .reservationUuid(reservationUuid)
        .tenantId(order.getTenantId())
        .build();
    Map<String, String> signatures = Map.of("hmac_sha256", sign(payload));
    outbox.append("Order", order.getUuid(), ORDER_STOCK_RESERVED_TOPIC, payload, signatures);
    log.debug("order.stock_reserved published: order={} reservation={}", order.getUuid(), reservationUuid);
  }

  public void publishOrderPaymentPending(Order order) {
    Instant now = Instant.now();
    OrderPaymentPendingEvent payload = OrderPaymentPendingEvent.builder()
        .eventId(SnowflakeIdGenerator.generateId())
        .aggregateType("Order")
        .aggregateId(order.getUuid())
        .occurredAt(now)
        .orderUuid(order.getUuid())
        .cartUuid(order.getCartUuid())
        .checkoutUuid(order.getCheckoutUuid())
        .paymentIntentId(order.getPaymentIntentId())
        .tenantId(order.getTenantId())
        .build();
    Map<String, String> signatures = Map.of("hmac_sha256", sign(payload));
    outbox.append("Order", order.getUuid(), ORDER_PAYMENT_PENDING_TOPIC, payload, signatures);
    log.debug("order.payment_pending published: order={}", order.getUuid());
  }

  public void publishOrderFailed(Order order) {
    Instant now = Instant.now();
    OrderFailedEvent payload = OrderFailedEvent.builder()
        .eventId(SnowflakeIdGenerator.generateId())
        .aggregateType("Order")
        .aggregateId(order.getUuid())
        .occurredAt(now)
        .orderUuid(order.getUuid())
        .cartUuid(order.getCartUuid())
        .checkoutUuid(order.getCheckoutUuid())
        .failureSagaStep(order.getFailureSagaStep())
        .failureReason(order.getFailureReason())
        .tenantId(order.getTenantId())
        .build();
    Map<String, String> signatures = Map.of("hmac_sha256", sign(payload));
    outbox.append("Order", order.getUuid(), ORDER_FAILED_TOPIC, payload, signatures);
    log.debug("order.failed published: order={} reason={}", order.getUuid(), order.getFailureReason());
  }

  @SuppressWarnings("unchecked")
  private String sign(Object payload) {
    Map<String, Object> map = objectMapper.convertValue(payload, Map.class);
    return HmacEventSigner.sign(JcsCanonicalJson.serialize(map), checkoutServiceSecret);
  }
}