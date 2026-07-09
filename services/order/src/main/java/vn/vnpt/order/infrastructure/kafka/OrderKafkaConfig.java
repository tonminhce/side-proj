package vn.vnpt.order.infrastructure.kafka;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Kafka consumer wiring for the payment→order event bridge — Story 4.1 follow-up.
 *
 * <p>Hand-rolled {@link KafkaConsumer} bean (no spring-kafka). The consumer is created at
 * startup; the {@link PaymentEventKafkaListener} (also in this package) drives its poll loop on a
 * daemon thread. Offsets are committed manually after each batch so a crash mid-batch replays the
 * unprocessed records.
 *
 * <p>Disabled in {@code @SpringBootTest} contexts; tests inject mock envelopes directly.
 */
@Configuration
@EnableConfigurationProperties(OrderBridgeKafkaProperties.class)
@Profile("!test")
public class OrderKafkaConfig {

  private KafkaConsumer<String, String> consumer;

  @Bean
  public KafkaConsumer<String, String> paymentEventConsumer(OrderBridgeKafkaProperties props) {
    Properties cfg = new Properties();
    cfg.put("bootstrap.servers", props.getBootstrapServers());
    cfg.put("group.id", props.getGroupId());
    cfg.put("key.deserializer", StringDeserializer.class.getName());
    cfg.put("value.deserializer", StringDeserializer.class.getName());
    // Read from the start on first run; subsequent runs resume from committed offsets.
    cfg.put("auto.offset.reset", "earliest");
    // Manual commit so we control when the offset advances (after successful HMAC verify +
    // saga dispatch).
    cfg.put("enable.auto.commit", "false");
    cfg.put("client.id", "order-bridge-consumer");
    KafkaConsumer<String, String> c = new KafkaConsumer<>(cfg);
    c.subscribe(List.of(props.getTopic()));
    this.consumer = c;
    return c;
  }

  @PreDestroy
  public void close() {
    if (consumer != null) {
      consumer.close();
    }
  }
}
