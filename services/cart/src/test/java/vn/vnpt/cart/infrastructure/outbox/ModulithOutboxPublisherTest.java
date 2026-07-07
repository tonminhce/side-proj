package vn.vnpt.cart.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import vn.vnpt.cart.domain.Cart;
import vn.vnpt.cart.domain.CartStatus;
import vn.vnpt.cart.domain.event.CartMergedEvent;

/**
 * Outbox persistence + in-process fan-out — Story 2.1 / ADR-04, ADR-14.
 *
 * <p>Verifies: (1) the {@code outbox} table receives a row with JSONB payload + signatures, (2) the
 * {@link ApplicationEventPublisher} fires so future in-process listeners (Story 2.2's
 * {@code cart.line.added}) can subscribe.
 */
@ExtendWith(MockitoExtension.class)
class ModulithOutboxPublisherTest {

  @Mock JdbcTemplate jdbcTemplate;
  @Mock ApplicationEventPublisher applicationEventPublisher;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void append_persistsOutboxRow_andPublishesInProcessEvent() {
    ModulithOutboxPublisher publisher =
        new ModulithOutboxPublisher(jdbcTemplate, objectMapper, applicationEventPublisher);

    CartMergedEvent event =
        CartMergedEvent.builder()
            .eventId(1L)
            .aggregateType("Cart")
            .aggregateId(200L)
            .guestCartId("g-1")
            .userId("u-1")
            .sourceCartUuid(100L)
            .targetCartUuid(200L)
            .mergedLinesCount(2)
            .build();

    publisher.append("Cart", 200L, "cart.merged", event, Map.of("hmac_sha256", "fake"));

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object> aggTypeCaptor = ArgumentCaptor.forClass(Object.class);
    ArgumentCaptor<Object> aggIdCaptor = ArgumentCaptor.forClass(Object.class);
    ArgumentCaptor<Object> eventTypeCaptor = ArgumentCaptor.forClass(Object.class);
    ArgumentCaptor<Object> eventIdCaptor = ArgumentCaptor.forClass(Object.class);
    ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
    ArgumentCaptor<Object> sigsCaptor = ArgumentCaptor.forClass(Object.class);
    verify(jdbcTemplate)
        .update(
            sqlCaptor.capture(),
            aggTypeCaptor.capture(),
            aggIdCaptor.capture(),
            eventTypeCaptor.capture(),
            eventIdCaptor.capture(),
            payloadCaptor.capture(),
            sigsCaptor.capture());

    assertThat(sqlCaptor.getValue()).contains("INSERT INTO outbox").contains("::jsonb");
    assertThat(aggTypeCaptor.getValue()).isEqualTo("Cart");
    assertThat(aggIdCaptor.getValue()).isEqualTo(200L);
    assertThat(eventTypeCaptor.getValue()).isEqualTo("cart.merged");
    assertThat(eventIdCaptor.getValue()).isInstanceOf(Long.class);
    String payload = (String) payloadCaptor.getValue();
    assertThat(payload).contains("\"aggregateType\":\"Cart\"").contains("\"userId\":\"u-1\"");
    String sigs = (String) sigsCaptor.getValue();
    assertThat(sigs).contains("\"hmac_sha256\"").contains("fake");

    // In-process listener fires.
    verify(applicationEventPublisher).publishEvent(eq(event));
  }

  @Test
  void append_nullOrEmptySignatures_serializesToEmptyJsonObject() {
    ModulithOutboxPublisher publisher =
        new ModulithOutboxPublisher(jdbcTemplate, objectMapper, applicationEventPublisher);

    CartMergedEvent payload = CartMergedEvent.builder().aggregateId(1L).build();
    publisher.append("Cart", 1L, "cart.merged", payload, null);
    publisher.append("Cart", 2L, "cart.merged", payload, Map.of());

    ArgumentCaptor<String> sigsCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate, org.mockito.Mockito.times(2))
        .update(anyString(), any(), any(), any(), any(), any(), sigsCaptor.capture());
    assertThat(sigsCaptor.getAllValues()).allMatch(s -> "{}".equals(s));
  }

  @Test
  void append_usesSourceCartUuidAsEventAggregateId() {
    // ponytail: aggregateId is the TARGET (user-bound) cart's uuid — that's the merge sink.
    // The publisher routes through the same 5-arg contract regardless of source/target shape.
    ModulithOutboxPublisher publisher =
        new ModulithOutboxPublisher(jdbcTemplate, objectMapper, applicationEventPublisher);
    when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(1);

    Cart source = Cart.builder().tenantId("default").guestCartId("g-1").status(CartStatus.ANONYMOUS).build();
    source.setUuid(100L);
    Cart target = Cart.builder().tenantId("default").userId("u-1").status(CartStatus.ACTIVE).build();
    target.setUuid(200L);

    publisher.append("Cart", target.getUuid(), "cart.merged",
        CartMergedEvent.builder().aggregateId(target.getUuid()).build(), Map.of());

    verify(jdbcTemplate).update(anyString(), eq("Cart"), eq(200L), eq("cart.merged"),
        any(), any(), any());
  }
}