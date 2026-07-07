package vn.vnpt.payment.application.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.NullNode;

/**
 * Trust-boundary validation for {@link StripeWebhookEvent} — Story 3.2 / AC #7. The {@code id} and
 * {@code type} guards are load-bearing: a missing {@code id} would let {@code INSERT ... ON
 * CONFLICT DO NOTHING} treat the dedup key as NULL and Postgres's NULLS-DISTINCT behavior would
 * defeat the dedup contract (FR-26 + NFR-IDEM-1).
 */
class StripeWebhookEventTest {

  @Test
  void id_mustNotBeNull() {
    assertThatThrownBy(() -> new StripeWebhookEvent(null, "payment_intent.succeeded", false, NullNode.getInstance(), 1700000000L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("id must not be null or blank");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "\t"})
  void id_mustNotBeBlank(String blank) {
    assertThatThrownBy(() -> new StripeWebhookEvent(blank, "payment_intent.succeeded", false, NullNode.getInstance(), 1700000000L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("id must not be null or blank");
  }

  @Test
  void id_lengthMustBe1to128_acceptsBoundaryLow() {
    StripeWebhookEvent event = new StripeWebhookEvent("e", "x", false, NullNode.getInstance(), 0L);
    assertThat(event.id()).isEqualTo("e");
  }

  @Test
  void id_lengthMustBe1to128_rejectsLength129() {
    String tooLong = "evt_" + "a".repeat(125);   // 129 chars total
    assertThatThrownBy(() -> new StripeWebhookEvent(tooLong, "x", false, NullNode.getInstance(), 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("length must be 1..128");
  }

  @Test
  void type_mustNotBeNull() {
    assertThatThrownBy(() -> new StripeWebhookEvent("evt_abc", null, false, NullNode.getInstance(), 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("type must not be null or blank");
  }

  @Test
  void livemodeCaptured() {
    StripeWebhookEvent live = new StripeWebhookEvent("evt_live", "payment_intent.succeeded", true, NullNode.getInstance(), 1700000000L);
    StripeWebhookEvent test = new StripeWebhookEvent("evt_test", "payment_intent.succeeded", false, NullNode.getInstance(), 1700000000L);
    assertThat(live.livemode()).isTrue();
    assertThat(test.livemode()).isFalse();
  }

  @Test
  void createdPassedThrough() {
    StripeWebhookEvent event = new StripeWebhookEvent("evt_abc", "payment_intent.succeeded", false, NullNode.getInstance(), 1700000123L);
    assertThat(event.created()).isEqualTo(1700000123L);
  }

  @Test
  void dataJsonNodeCaptured() {
    JsonNode data = NullNode.getInstance();
    StripeWebhookEvent event = new StripeWebhookEvent("evt_abc", "payment_intent.succeeded", false, data, 0L);
    assertThat(event.data()).isSameAs(data);
  }
}