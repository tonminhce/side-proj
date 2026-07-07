package vn.vnpt.checkout.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import vn.vnpt.checkout.application.port.OutboxPublisher;
import vn.vnpt.checkout.domain.Order;
import vn.vnpt.checkout.domain.OrderStatus;
import vn.vnpt.checkout.domain.ShippingAddress;
import vn.vnpt.checkout.domain.event.OrderCreatedEvent;
import vn.vnpt.checkout.domain.event.OrderFailedEvent;
import vn.vnpt.checkout.domain.event.OrderPaymentPendingEvent;
import vn.vnpt.checkout.domain.event.OrderStockReservedEvent;
import vn.vnpt.util.events.HmacEventSigner;
import vn.vnpt.util.events.JcsCanonicalJson;

/**
 * Story 2.5 / FR-22 + ADR-20 — HMAC signing, outbox topic strings, payload shape for the four
 * order.* events. Mirrors {@link CheckoutEventPublisherTest} verbatim.
 */
@ExtendWith(MockitoExtension.class)
class OrderEventPublisherTest {

  private static final String SECRET = "dev-only-secret-do-not-use-in-prod";

  @Mock OutboxPublisher outbox;
  private final ObjectMapper objectMapper = new ObjectMapper();

  private OrderEventPublisher newPublisher() {
    OrderEventPublisher p = new OrderEventPublisher(outbox, objectMapper);
    ReflectionTestUtils.setField(p, "checkoutServiceSecret", SECRET);
    return p;
  }

  private Order order(OrderStatus status) {
    Order o = Order.builder()
        .tenantId("default")
        .cartUuid(99L)
        .checkoutUuid(11L)
        .paymentIntentId("pi_abc")
        .shippingAddress(ShippingAddress.builder()
            .recipientName("A").phone("0").addressLine1("a")
            .city("HCM").province("HCM").country("VN").build())
        .status(status)
        .version(0L)
        .build();
    o.setUuid(42L);
    return o;
  }

  @Test
  void publishOrderCreated_writesCorrectTopicPayloadAndHmac() {
    OrderEventPublisher publisher = newPublisher();

    publisher.publishOrderCreated(order(OrderStatus.CREATED));

    ArgumentCaptor<OrderCreatedEvent> eventCaptor = ArgumentCaptor.forClass(OrderCreatedEvent.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> sigsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(outbox).append(eq("Order"), eq(42L), eq(OrderEventPublisher.ORDER_CREATED_TOPIC),
        eventCaptor.capture(), sigsCaptor.capture());

    OrderCreatedEvent signed = eventCaptor.getValue();
    assertThat(signed.getAggregateType()).isEqualTo("Order");
    assertThat(signed.getAggregateId()).isEqualTo(42L);
    assertThat(signed.getOrderUuid()).isEqualTo(42L);
    assertThat(signed.getCheckoutUuid()).isEqualTo(11L);
    assertThat(signed.getCartUuid()).isEqualTo(99L);
    assertThat(signed.getPaymentIntentId()).isEqualTo("pi_abc");
    assertThat(signed.getTenantId()).isEqualTo("default");
    assertThat(signed.getEventId()).isNotNull();
    assertThat(signed.getOccurredAt()).isNotNull();

    Map<String, String> sigs = sigsCaptor.getValue();
    assertThat(sigs).containsKey("hmac_sha256");
    assertThat(sigs.get("hmac_sha256")).isNotBlank();
  }

  @Test
  void publishOrderStockReserved_includesReservationUuidAndTopic() {
    OrderEventPublisher publisher = newPublisher();

    publisher.publishOrderStockReserved(order(OrderStatus.STOCK_RESERVED), 99L);

    ArgumentCaptor<OrderStockReservedEvent> eventCaptor = ArgumentCaptor.forClass(OrderStockReservedEvent.class);
    verify(outbox).append(eq("Order"), eq(42L), eq(OrderEventPublisher.ORDER_STOCK_RESERVED_TOPIC),
        eventCaptor.capture(), any(Map.class));
    assertThat(eventCaptor.getValue().getReservationUuid()).isEqualTo(99L);
  }

  @Test
  void publishOrderPaymentPending_carriesPaymentIntentId() {
    OrderEventPublisher publisher = newPublisher();

    publisher.publishOrderPaymentPending(order(OrderStatus.PAYMENT_PENDING));

    ArgumentCaptor<OrderPaymentPendingEvent> eventCaptor = ArgumentCaptor.forClass(OrderPaymentPendingEvent.class);
    verify(outbox).append(eq("Order"), eq(42L), eq(OrderEventPublisher.ORDER_PAYMENT_PENDING_TOPIC),
        eventCaptor.capture(), any(Map.class));
    assertThat(eventCaptor.getValue().getPaymentIntentId()).isEqualTo("pi_abc");
  }

  @Test
  void publishOrderFailed_carriesReasonAndSagaStep() {
    OrderEventPublisher publisher = newPublisher();
    Order failed = order(OrderStatus.FAILED);
    failed.setFailureReason("INSUFFICIENT_STOCK");
    failed.setFailureSagaStep("stock.reserve");

    publisher.publishOrderFailed(failed);

    ArgumentCaptor<OrderFailedEvent> eventCaptor = ArgumentCaptor.forClass(OrderFailedEvent.class);
    verify(outbox).append(eq("Order"), eq(42L), eq(OrderEventPublisher.ORDER_FAILED_TOPIC),
        eventCaptor.capture(), any(Map.class));
    OrderFailedEvent captured = eventCaptor.getValue();
    assertThat(captured.getFailureReason()).isEqualTo("INSUFFICIENT_STOCK");
    assertThat(captured.getFailureSagaStep()).isEqualTo("stock.reserve");
  }

  /** AC #7 — HMAC is computed over JCS-canonical payload per ADR-20. */
  @Test
  void publishOrderCreated_hmacVerifiesOverJcsCanonicalPayload() {
    OrderEventPublisher publisher = newPublisher();

    publisher.publishOrderCreated(order(OrderStatus.CREATED));

    ArgumentCaptor<OrderCreatedEvent> eventCaptor = ArgumentCaptor.forClass(OrderCreatedEvent.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> sigsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(outbox).append(any(String.class), anyLong(), any(String.class),
        eventCaptor.capture(), sigsCaptor.capture());

    OrderCreatedEvent signed = eventCaptor.getValue();
    String signature = sigsCaptor.getValue().get("hmac_sha256");

    OrderCreatedEvent unsigned = OrderCreatedEvent.builder()
        .eventId(signed.getEventId())
        .aggregateType(signed.getAggregateType())
        .aggregateId(signed.getAggregateId())
        .occurredAt(signed.getOccurredAt())
        .orderUuid(signed.getOrderUuid())
        .cartUuid(signed.getCartUuid())
        .checkoutUuid(signed.getCheckoutUuid())
        .paymentIntentId(signed.getPaymentIntentId())
        .tenantId(signed.getTenantId())
        .signatures(null)
        .build();

    @SuppressWarnings("unchecked")
    Map<String, Object> map = objectMapper.convertValue(unsigned, Map.class);
    String canonical = JcsCanonicalJson.serialize(map);
    assertThat(HmacEventSigner.verify(canonical, signature, SECRET)).isTrue();
  }

  /** Topic-string sanity — every event lands on a distinct topic. */
  @Test
  void topicStringsAreDistinct() {
    assertThat(OrderEventPublisher.ORDER_CREATED_TOPIC).isEqualTo("order.created");
    assertThat(OrderEventPublisher.ORDER_STOCK_RESERVED_TOPIC).isEqualTo("order.stock_reserved");
    assertThat(OrderEventPublisher.ORDER_PAYMENT_PENDING_TOPIC).isEqualTo("order.payment_pending");
    assertThat(OrderEventPublisher.ORDER_FAILED_TOPIC).isEqualTo("order.failed");
    assertThat(List.of(OrderEventPublisher.ORDER_CREATED_TOPIC,
        OrderEventPublisher.ORDER_STOCK_RESERVED_TOPIC,
        OrderEventPublisher.ORDER_PAYMENT_PENDING_TOPIC,
        OrderEventPublisher.ORDER_FAILED_TOPIC)).doesNotHaveDuplicates();
  }
}