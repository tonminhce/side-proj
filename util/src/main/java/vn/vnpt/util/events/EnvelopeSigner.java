package vn.vnpt.util.events;

import tools.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

/** Build the HMAC envelope for an outbox event per ADR-20. See ADR-20 §envelope. */
public final class EnvelopeSigner {

  private EnvelopeSigner() {}

  /** Return Map.of() if signerFn is null (unsigned event). */
  public static Map<String, String> build(
      BiFunction<String, String, String> signerFn,
      long eventId,
      String eventType,
      String aggregateType,
      long aggregateId,
      String payloadJson,
      String serviceName) {
    if (signerFn == null) {
      return Map.of();
    }
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("event_id", eventId);
    envelope.put("event_type", eventType);
    envelope.put("aggregate_type", aggregateType);
    envelope.put("aggregate_id", aggregateId);
    envelope.put("payload_sha256", canonicalize(payloadJson));
    String hmac = signerFn.apply(JcsCanonicalJson.serialize(envelope), serviceName);
    Map<String, String> out = new LinkedHashMap<>();
    out.put("service", serviceName);
    out.put("hmac_sha256", hmac);
    return out;
  }

  private static String canonicalize(String payloadJson) {
    try {
      return JcsCanonicalJson.serialize(new ObjectMapper().readValue(payloadJson, Map.class));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to canonicalize payload", e);
    }
  }
}
