package vn.vnpt.gateway.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.List;

/**
 * Gateway Redis config — Story 3.4 / FR-81. Loads the Lua scripts as Spring beans so the filter
 * chain can call them via {@code reactiveRedisTemplate.execute(script, keys, args)}.
 *
 * <p>StringRedisTemplate is auto-configured by Spring Boot when no primary RedisTemplate is
 * present in the context. util's RedisTemplate&lt;String, Object&gt; is {@code @Primary}, so we
 * rely on autoconfig to register the StringRedisTemplate alongside (different type, no conflict).
 */
@Configuration
public class GatewayRedisConfig {

  @Bean
  public DefaultRedisScript<List> rateLimiterScript() {
    DefaultRedisScript<List> script = new DefaultRedisScript<>();
    script.setLocation(new ClassPathResource("lua/rate-limiter.lua"));
    script.setResultType(List.class);
    return script;
  }

  @Bean
  public DefaultRedisScript<Long> binVelocityScript() {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setLocation(new ClassPathResource("lua/bin-velocity.lua"));
    script.setResultType(Long.class);
    return script;
  }
}