package vn.vnpt.gateway.lua;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
 * Direct Lua-script tests for bin-velocity.lua — Story 3.4 / FR-81 / R-05 / ADR-24.
 *
 * <p>Uses a minimal test config to bypass Spring Cloud Gateway autoconfig.
 */
@SpringBootTest(classes = {BinVelocityLuaScriptTest.TestConfig.class})
class BinVelocityLuaScriptTest {

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
    DefaultRedisScript<Long> binVelocityScript() {
      DefaultRedisScript<Long> script = new DefaultRedisScript<>();
      script.setLocation(new ClassPathResource("lua/bin-velocity.lua"));
      script.setResultType(Long.class);
      return script;
    }
  }

  @Autowired StringRedisTemplate redis;
  @Autowired DefaultRedisScript<Long> script;

  @Test
  void script_initiallyZeroCount() {
    String key = "rl:bin:test:" + System.nanoTime();
    long count = redis.execute(script, List.of(key),
        String.valueOf(System.currentTimeMillis()), "60000");
    assertThat(count).isEqualTo(1L);
  }

  @Test
  void script_incrementsOnEachCall() {
    String key = "rl:bin:test:" + System.nanoTime();
    long now = System.currentTimeMillis();
    redis.execute(script, List.of(key), String.valueOf(now), "60000");
    redis.execute(script, List.of(key), String.valueOf(now + 100), "60000");
    long count = redis.execute(script, List.of(key), String.valueOf(now + 200), "60000");
    assertThat(count).isGreaterThanOrEqualTo(3L);
  }

  @Test
  void script_returnsCountAboveThreshold() {
    String key = "rl:bin:test:" + System.nanoTime();
    long now = System.currentTimeMillis();
    for (int i = 0; i < 11; i++) {
      redis.execute(script, List.of(key), String.valueOf(now + i), "60000");
    }
    long count = redis.execute(script, List.of(key), String.valueOf(now + 100), "60000");
    assertThat(count).isGreaterThanOrEqualTo(11L);
  }
}