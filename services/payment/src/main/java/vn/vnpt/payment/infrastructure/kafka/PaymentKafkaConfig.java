package vn.vnpt.payment.infrastructure.kafka;

import jakarta.annotation.PreDestroy;
import java.util.Properties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Kafka producer wiring for the payment→order event bridge — Story 4.1 follow-up.
 *
 * <p>Hand-rolled {@link KafkaProducer} bean (no spring-kafka). The producer is keyed on
 * {@code aggregateType + ":" + aggregateId} so all events for the same payment intent land on the
 * same partition (8 partitions for the {@code payment.events} topic — enough for parallel
 * consumers).
 *
 * <p>Disabled in {@code @SpringBootTest} contexts (the integration test uses an embedded producer
 * stub). Production path: enabled by default.
 */
@Configuration
@EnableConfigurationProperties(PaymentBridgeKafkaProperties.class)
@Profile("!test")
public class PaymentKafkaConfig {

  private KafkaProducer<String, String> producer;

  @Bean
  public KafkaProducer<String, String> paymentEventProducer(PaymentBridgeKafkaProperties props) {
    Properties cfg = new Properties();
    cfg.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, props.getBootstrapServers());
    cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    cfg.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    // At-least-once delivery: acks=all + retries; idempotence protects against duplicates on retry.
    cfg.put(ProducerConfig.ACKS_CONFIG, "all");
    cfg.put(ProducerConfig.RETRIES_CONFIG, 5);
    cfg.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    cfg.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
    cfg.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
    cfg.put(ProducerConfig.LINGER_MS_CONFIG, 10);
    cfg.put(ProducerConfig.CLIENT_ID_CONFIG, "payment-bridge-producer");
    this.producer = new KafkaProducer<>(cfg);
    return this.producer;
  }

  @PreDestroy
  public void close() {
    if (producer != null) {
      producer.close();
    }
  }
}
