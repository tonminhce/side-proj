package vn.vnpt.payment.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.vnpt.payment.infrastructure.security.DevHmacKeyProvider;
import vn.vnpt.util.events.HmacEventSigner;
import vn.vnpt.util.events.JcsCanonicalJson;

/**
 * Unit tests for the HMAC-signed envelope produced by
 * {@link PaymentModulithOutboxPublisher#signaturesFor} — Story 3.5 / FR-82 / ADR-20.
 */
class PaymentModulithOutboxPublisherHmacTest {

  @Test
  void signaturesFor_returnsMapWithServicePaymentAndBase64UrlHmac() {
    DevHmacKeyProvider key = new DevHmacKeyProvider();
    key.init();
    PaymentModulithOutboxPublisher publisher =
        new PaymentModulithOutboxPublisher(null, null, null, key);

    Map<String, String> sigs = publisher.signaturesFor(
        42L, "payment.captured", "Payment", 1L, "{\"amount\":1999}");

    assertThat(sigs).containsKeys("service", "hmac_sha256", "key_id");
    assertThat(sigs.get("service")).isEqualTo("payment");
    assertThat(sigs.get("key_id")).isEqualTo("v1");
    // base64url HMAC-SHA-256 = 32 bytes raw → 43 chars base64url-no-padding
    assertThat(sigs.get("hmac_sha256")).hasSize(43);
    assertThat(sigs.get("hmac_sha256")).matches("^[A-Za-z0-9_-]+$");
  }

  @Test
  void signaturesFor_signatureVerifiesViaHmacEventSigner() {
    DevHmacKeyProvider key = new DevHmacKeyProvider();
    key.init();
    String secret = key.currentSecret();
    PaymentModulithOutboxPublisher publisher =
        new PaymentModulithOutboxPublisher(null, null, null, key);

    long eventId = 123L;
    String eventType = "payment.captured";
    String aggregateType = "Payment";
    long aggregateId = 7L;
    String payloadJson = "{\"order\":42}";
    Map<String, String> sigs = publisher.signaturesFor(eventId, eventType, aggregateType, aggregateId, payloadJson);

    // Recompute the canonical envelope the same way the publisher does.
    String canonical = JcsCanonicalJson.serialize(Map.of(
        "event_id", eventId,
        "event_type", eventType,
        "aggregate_type", aggregateType,
        "aggregate_id", aggregateId,
        "payload", payloadJson));
    boolean verified = HmacEventSigner.verify(canonical, sigs.get("hmac_sha256"), secret);
    assertThat(verified).isTrue();
  }

  @Test
  void signaturesFor_changesWhenSecretChanges() {
    DevHmacKeyProvider keyA = new DevHmacKeyProvider();
    keyA.init();
    DevHmacKeyProvider keyB = new DevHmacKeyProvider();
    keyB.init();
    PaymentModulithOutboxPublisher pubA =
        new PaymentModulithOutboxPublisher(null, null, null, keyA);
    PaymentModulithOutboxPublisher pubB =
        new PaymentModulithOutboxPublisher(null, null, null, keyB);

    Map<String, String> sigA = pubA.signaturesFor(1L, "x", "y", 2L, "z");
    Map<String, String> sigB = pubB.signaturesFor(1L, "x", "y", 2L, "z");
    assertThat(sigA.get("hmac_sha256")).isNotEqualTo(sigB.get("hmac_sha256"));
  }
}