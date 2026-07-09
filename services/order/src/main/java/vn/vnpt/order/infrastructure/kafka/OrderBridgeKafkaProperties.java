package vn.vnpt.order.infrastructure.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the payment→order event bridge — order side (consumer). Binds
 * {@code order.bridge.kafka.*} from application.yml.
 *
 * <p>Defaults: localhost:9092, group-id "order-payment-bridge", poll-timeout 500ms. The
 * group-id is stable across restarts so committed offsets resume.
 */
@ConfigurationProperties(prefix = "order.bridge.kafka")
public class OrderBridgeKafkaProperties {

  private String bootstrapServers = "localhost:9092";
  private String topic = "payment.events";
  private String groupId = "order-payment-bridge";
  private long pollTimeoutMs = 500;

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

  public String getGroupId() {
    return groupId;
  }

  public void setGroupId(String groupId) {
    this.groupId = groupId;
  }

  public long getPollTimeoutMs() {
    return pollTimeoutMs;
  }

  public void setPollTimeoutMs(long pollTimeoutMs) {
    this.pollTimeoutMs = pollTimeoutMs;
  }
}
