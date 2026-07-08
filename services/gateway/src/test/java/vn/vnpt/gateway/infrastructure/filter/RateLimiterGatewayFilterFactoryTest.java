package vn.vnpt.gateway.infrastructure.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class RateLimiterGatewayFilterFactoryTest {

  @Mock ReactiveRedisTemplate<String, String> redis;

  private DefaultRedisScript<List> rateLimiterScript;
  private DefaultRedisScript<Long> binVelocityScript;
  private RateLimiterGatewayFilterFactory factory;
  private RateLimiterGatewayFilterFactory.Config config;

  @BeforeEach
  void setUp() {
    rateLimiterScript = new DefaultRedisScript<>();
    binVelocityScript = new DefaultRedisScript<>();
    factory = new RateLimiterGatewayFilterFactory(
        redis, rateLimiterScript, binVelocityScript, new SimpleMeterRegistry());
    config = new RateLimiterGatewayFilterFactory.Config();
  }

  @Test
  void rejectsRequestWithoutTrustedRealIp() {
    GatewayFilter filter = factory.apply(config);
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/payment/webhooks/stripe").build());
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.filter(exchange, chain(exchange, chainCalled)).block();

    assertThat(chainCalled).isFalse();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("0");
    assertThat(exchange.getResponse().getBodyAsString().block()).contains("missing X-Real-IP header");
  }

  @Test
  void forwardsAllowedRequestAndAddsRateLimitHeaders() {
    mockBinVelocityCount(1L);
    mockTokenBucket(List.of(1L, 99L, 0L));

    GatewayFilter filter = factory.apply(config);
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.post("/api/payment/webhooks/stripe")
            .header("X-Real-IP", "1.2.3.4")
            .header("X-Card-Bin", "424242")
            .header("X-Card-Last4", "4242")
            .build());
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.filter(exchange, chain(exchange, chainCalled)).block();

    assertThat(chainCalled).isTrue();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(exchange.getResponse().getHeaders().getFirst("RateLimit-Limit")).isEqualTo("100");
    assertThat(exchange.getResponse().getHeaders().getFirst("RateLimit-Remaining")).isEqualTo("99");
  }

  @Test
  void blocksWhenBinVelocityThresholdExceeded() {
    mockBinVelocityCount(11L);

    GatewayFilter filter = factory.apply(config);
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/payment/webhooks/stripe")
            .header("X-Real-IP", "1.2.3.4")
            .header("X-Card-Bin", "424242")
            .header("X-Card-Last4", "4242")
            .build());
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.filter(exchange, chain(exchange, chainCalled)).block();

    assertThat(chainCalled).isFalse();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(exchange.getResponse().getBodyAsString().block()).contains("BIN velocity exceeded");
  }

  @Test
  void failsClosedWhenRedisErrors() {
    doReturn(Flux.error(new RuntimeException("redis down")))
        .when(redis).execute(eq(binVelocityScript), anyList(), any(), any());

    GatewayFilter filter = factory.apply(config);
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/payment/webhooks/stripe")
            .header("X-Real-IP", "1.2.3.4")
            .header("X-Card-Bin", "424242")
            .header("X-Card-Last4", "4242")
            .build());
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.filter(exchange, chain(exchange, chainCalled)).block();

    assertThat(chainCalled).isFalse();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(exchange.getResponse().getBodyAsString().block()).contains("rate_limiter_unavailable");
  }

  @Test
  void blocksWhenTokenBucketExhausted() {
    // Token bucket Lua returns [allowed=0, remaining=0, retryAfter=37] when the bucket is dry.
    mockBinVelocityCount(1L);
    mockTokenBucket(List.of(0L, 0L, 37L));

    GatewayFilter filter = factory.apply(config);
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/payment/webhooks/stripe")
            .header("X-Real-IP", "1.2.3.4")
            .header("X-Card-Bin", "424242")
            .header("X-Card-Last4", "4242")
            .build());
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.filter(exchange, chain(exchange, chainCalled)).block();

    assertThat(chainCalled).isFalse();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("37");
    assertThat(exchange.getResponse().getHeaders().getFirst("RateLimit-Remaining")).isEqualTo("0");
    assertThat(exchange.getResponse().getBodyAsString().block()).contains("rate_limited");
  }

  @Test
  void usesXRealIpNotXForwardedFor() {
    // NFR-SEC-1: X-Forwarded-For is client-spoofable; the gateway must consult X-Real-IP only.
    // Request omits X-Real-IP and supplies only X-Forwarded-For — must be rejected as missing.
    // (No Redis stub needed: the filter short-circuits before reaching Redis.)

    GatewayFilter filter = factory.apply(config);
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/payment/webhooks/stripe")
            .header("X-Forwarded-For", "1.2.3.4")   // spoofable
            .header("X-Card-Bin", "424242")
            .header("X-Card-Last4", "4242")
            .build());
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.filter(exchange, chain(exchange, chainCalled)).block();

    assertThat(chainCalled).isFalse();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(exchange.getResponse().getBodyAsString().block()).contains("missing X-Real-IP header");
  }

  private void mockBinVelocityCount(long count) {
    doReturn((Flux) Flux.just(count)).when(redis).execute(eq(binVelocityScript), anyList(), any(), any());
  }

  private void mockTokenBucket(List<Long> result) {
    doReturn((Flux) Flux.just(result))
        .when(redis).execute(eq(rateLimiterScript), anyList(), any(), any(), any(), any(), any());
  }

  private static org.springframework.cloud.gateway.filter.GatewayFilterChain chain(
      MockServerWebExchange exchange, AtomicBoolean chainCalled) {
    return ignored -> {
      chainCalled.set(true);
      exchange.getResponse().setStatusCode(HttpStatus.NO_CONTENT);
      return Mono.empty();
    };
  }
}
