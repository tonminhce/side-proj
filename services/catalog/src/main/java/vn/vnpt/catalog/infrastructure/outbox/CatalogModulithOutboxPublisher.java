package vn.vnpt.catalog.infrastructure.outbox;

import tools.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.vnpt.catalog.application.port.OutboxPublisher;
import vn.vnpt.util.events.HmacEventSigner;
import vn.vnpt.util.events.JcsCanonicalJson;
import vn.vnpt.util.events.ModulithOutboxPublisher;
import vn.vnpt.util.hash.HashUtil;

/** Catalog outbox publisher — signs with HMAC per ADR-20. */
@Component
public class CatalogModulithOutboxPublisher extends ModulithOutboxPublisher implements OutboxPublisher {

  private final String hmacSecret;
  private final String serviceName = "catalog";

  public CatalogModulithOutboxPublisher(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      ApplicationEventPublisher applicationEventPublisher,
      @Value("${catalog.events.hmac-secret}") String hmacSecret) {
    super(jdbcTemplate, objectMapper, applicationEventPublisher);
    this.hmacSecret = hmacSecret;
  }

  @Override
  protected Map<String, String> signaturesFor(
      long eventId, String eventType, String aggregateType, long aggregateId, String payloadJson) {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("event_id", eventId);
    envelope.put("event_type", eventType);
    envelope.put("aggregate_type", aggregateType);
    envelope.put("aggregate_id", aggregateId);
    envelope.put("payload_sha256", canonicalSha256(payloadJson));
    String hmac = HmacEventSigner.sign(JcsCanonicalJson.serialize(envelope), hmacSecret);
    return Map.of("service", serviceName, "hmac_sha256", hmac);
  }

  private String canonicalSha256(String payloadJson) {
    try {
      return HashUtil.sha256Hex(JcsCanonicalJson.serialize(objectMapper.readValue(payloadJson, Map.class)));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to canonicalize payload", e);
    }
  }
}
