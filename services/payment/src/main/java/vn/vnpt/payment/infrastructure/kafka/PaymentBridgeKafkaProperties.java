package vn.vnpt.payment.infrastructure.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the payment→order event bridge — payment side. Binds
 * {@code payment.bridge.kafka.*} from application.yml.
 *
 * <p>Defaults: localhost:9092, batch-size 50, poll-interval 500ms. These match the values used by
 * {@code dev/docker-compose.yml} (Kafka KRaft single-node) and the order service's symmetric
 * consumer properties.
 */
@ConfigurationProperties(prefix = "payment.bridge.kafka")
public class PaymentBridgeKafkaProperties {

  /** Kafka bootstrap servers (comma-separated host:port pairs). */
  private String bootstrapServers = "localhost:9092";

  /** Kafka topic to publish to. Must match the order-side consumer subscription. */
  private String topic = "payment.events";

  /** Max rows fetched per poll. */
  private int batchSize = 50;

  /** Poll interval in milliseconds — the @Scheduled fixedDelay between outbox scans. */
  private long pollIntervalMs = 500;

  public String getBootstrapServers() {
    return bootstrapServers;
  }

  public void setBootstrapServers(String bootstrapServers) {
    this.bootstrapServers = bootstrapServers;
  }

  public String getTopic() {
    return topic;
  }

  public void setTopic(String topic) {
    this.topic = topic;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
  }

  public long getPollIntervalMs() {
    return pollIntervalMs;
  }

  public void setPollIntervalMs(long pollIntervalMs) {
    this.pollIntervalMs = pollIntervalMs;
  }
}
