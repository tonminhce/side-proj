package vn.vnpt.gateway.lua;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Direct Lua-script tests for rate-limiter.lua — Story 3.4 / FR-81 / R-05 / ADR-13.
 *
 * <p>Uses a minimal test config (TestConfig) that wires ONLY StringRedisTemplate + the script
 * beans, bypassing the full GatewayApplication autoconfig chain (JPA, Spring Cloud Gateway,
 * etc.) which would otherwise fail due to missing classpath dependencies.
 */
@SpringBootTest(classes = {RateLimiterLuaScriptTest.TestConfig.class})
class RateLimiterLuaScriptTest {

  @DynamicPropertySource
  static void redisProps(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", () -> System.getenv().getOrDefault("REDIS_HOST", "localhost"));
    registry.add("spring.data.redis.port", () -> System.getenv().getOrDefault("REDIS_PORT", "6379"));
  }

  @Configuration
  static class TestConfig {
    @Bean
    RedisConnectionFactory redisConnectionFactory() {
      String host = System.getenv().getOrDefault("REDIS_HOST", "localhost");
      int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));
      return new LettuceConnectionFactory(host, port);
    }

    @Bean
    StringRedisTemplate stringRedisTemplate(RedisConnectionFactory cf) {
      return new StringRedisTemplate(cf);
    }

    @Bean
    DefaultRedisScript<List> rateLimiterScript() {
      DefaultRedisScript<List> script = new DefaultRedisScript<>();
      script.setLocation(new ClassPathResource("lua/rate-limiter.lua"));
      script.setResultType(List.class);
      return script;
    }
  }

  @Autowired StringRedisTemplate redis;
  @Autowired DefaultRedisScript<List> script;

  @BeforeEach
  void setUp() {
    // No-op; per-test key isolation handled inline.
  }

  @Test
  void script_initiallyBucketHasCapacity() {
    String key = "rl:test:rate:" + System.nanoTime();
    List<Long> result = redis.execute(script, List.of(key),
        "10", "1.0", "60", "1", "1");

    assertThat(result.get(0)).isEqualTo(1L);
    assertThat(result.get(1)).isLessThanOrEqualTo(9L);
    assertThat(result.get(3)).isEqualTo(0L);
  }

  @Test
  void script_consumesTokensOnEachCall() {
    String key = "rl:test:rate:" + System.nanoTime();
    redis.execute(script, List.of(key), "5", "0.5", "60", "1", "1");
    redis.execute(script, List.of(key), "5", "0.5", "60", "1", "1");
    List<Long> third = redis.execute(script, List.of(key), "5", "0.5", "60", "1", "1");

    assertThat(third.get(0)).isEqualTo(1L);
  }

  @Test
  void script_blocksWhenTokensBelowCost() {
    String key = "rl:test:rate:" + System.nanoTime();
    for (int i = 0; i < 2; i++) {
      redis.execute(script, List.of(key), "2", "0", "60", "1", "1");
    }
    List<Long> blocked = redis.execute(script, List.of(key), "2", "0", "60", "1", "1");

    assertThat(blocked.get(0)).isEqualTo(0L);
  }
}