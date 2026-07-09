package vn.vnpt.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import vn.vnpt.gateway.infrastructure.config.GatewayBinVelocityProperties;

/**
 * Gateway — Story 3.4 / FR-81 / R-05 / ADR-13 + ADR-24. Reactive Spring Cloud Gateway filter
 * chain; rate-limiter + BIN velocity check via Redis Lua scripts. Out of scope: full RBAC +
 * per-endpoint cost + per-route IP per local-docs/04 (deferred to follow-up gateway story).
 *
 * <p>{@code GatewayNoLoadBalancerClientAutoConfiguration} is excluded by FQCN — it imports
 * {@code org.springframework.cloud.client.loadbalancer.reactive.ReactiveLoadBalancer} which
 * only ships with {@code spring-cloud-starter-loadbalancer}. We don't run a service registry
 * in v1; routes use direct {@code uri: http://...} so the LB filter is unnecessary.
 */
@SpringBootApplication(scanBasePackages = {"vn.vnpt.gateway"})
@EnableAutoConfiguration(excludeName = {
    "org.springframework.cloud.gateway.config.GatewayNoLoadBalancerClientAutoConfiguration",
    "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
    "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
    "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
    "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
})
@EnableConfigurationProperties(GatewayBinVelocityProperties.class)
public class GatewayApplication {

  public static void main(String[] args) {
    SpringApplication.run(GatewayApplication.class, args);
  }
}