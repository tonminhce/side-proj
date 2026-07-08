package vn.vnpt.gateway.infrastructure.filter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * RateLimiter + BIN velocity filter — Story 3.4 / FR-81 / R-05 / ADR-13 + ADR-24.
 *
 * <p>Keys: {@code (IP, cardFingerprint, ASN)} per ADR-24. {@code cardFingerprint = sha256(BIN || last4)};
 * BIN and last4 arrive via {@code X-Card-Bin} + {@code X-Card-Last4} headers (the BFF strips PAN
 * before the request reaches the gateway, per R-15).
 *
 * <p>IP source: {@code X-Real-IP} only (per NFR-SEC-1 — gateway owns the trusted hop header;
 * {@code X-Forwarded-For} is client-spoofable). Validated against {@link #IP_PATTERN}.
 *
 * <p>ASN: stub {@code "AS0_VN"} for v1. TODO MaxMind GeoLite2 lookup.
 *
 * <p>Fail-closed: a Redis error short-circuits to HTTP 429 + Retry-After + counter increment
 * (NFR-AVAIL-4: payment endpoints must not fall open during a card-testing attack).
 *
 * <p>// TODO follow-up gateway story: full RBAC + per-endpoint cost + per-route IP per local-docs/04.
 */
@Component
public class RateLimiterGatewayFilterFactory
    extends AbstractGatewayFilterFactory<RateLimiterGatewayFilterFactory.Config> {

  private static final Logger log = LoggerFactory.getLogger(RateLimiterGatewayFilterFactory.class);
  private static final Pattern IP_PATTERN = Pattern.compile("^[\\d.]{7,45}$");
  private static final Pattern BIN_PATTERN = Pattern.compile("^\\d{6}$");
  private static final Pattern LAST4_PATTERN = Pattern.compile("^\\d{4}$");

  private final ReactiveRedisTemplate<String, String> redis;
  private final DefaultRedisScript<List> rateLimiterScript;
  private final DefaultRedisScript<Long> binVelocityScript;
  private final Counter allowedCounter;
  private final Counter tokenBucketBlockedCounter;
  private final Counter binVelocityBlockedCounter;
  private final Counter errorCounter;
  private final AtomicLong lastBinVelocityCount = new AtomicLong(0);

  public RateLimiterGatewayFilterFactory(
      ReactiveRedisTemplate<String, String> redis,
      @Qualifier("rateLimiterScript") DefaultRedisScript<List> rateLimiterScript,
      @Qualifier("binVelocityScript") DefaultRedisScript<Long> binVelocityScript,
      MeterRegistry meterRegistry) {
    super(Config.class);
    this.redis = redis;
    this.rateLimiterScript = rateLimiterScript;
    this.binVelocityScript = binVelocityScript;
    this.allowedCounter = Counter.builder("gateway.ratelimit.allowed")
        .tag("route", "payment").register(meterRegistry);
    this.tokenBucketBlockedCounter = Counter.builder("gateway.ratelimit.blocked")
        .tag("route", "payment").tag("reason", "tokenbucket").register(meterRegistry);
    this.binVelocityBlockedCounter = Counter.builder("gateway.ratelimit.blocked")
        .tag("route", "payment").tag("reason", "bin_velocity").register(meterRegistry);
    this.errorCounter = Counter.builder("gateway.ratelimit.error")
        .tag("route", "payment").register(meterRegistry);
    meterRegistry.gauge("gateway.bin_velocity.count",
        lastBinVelocityCount, AtomicLong::get);
  }

  public static class Config {
    private int capacity = 100;
    private double refillPerSec = 10.0;
    private int ttlSec = 60;
    private int cost = 1;
    private int windowMinutes = 60;
    private int binVelocityMaxAttempts = 10;

    public int getCapacity() { return capacity; }
    public void setCapacity(int v) { this.capacity = v; }
    public double getRefillPerSec() { return refillPerSec; }
    public void setRefillPerSec(double v) { this.refillPerSec = v; }
    public int getttlSec() { return ttlSec; }
    public void setTtlSec(int v) { this.ttlSec = v; }
    public int getCost() { return cost; }
    public void setCost(int v) { this.cost = v; }
    public int getWindowMinutes() { return windowMinutes; }
    public void setWindowMinutes(int v) { this.windowMinutes = v; }
    public int getBinVelocityMaxAttempts() { return binVelocityMaxAttempts; }
    public void setBinVelocityMaxAttempts(int v) { this.binVelocityMaxAttempts = v; }
  }

  @Override
  public GatewayFilter apply(Config config) {
    return (exchange, chain) -> {
      String ip = ipFrom(exchange);
      if (ip == null) {
        // HIGH-3 / NFR-SEC-1: missing X-Real-IP is a trust-boundary violation — reject.
        return shortCircuit(exchange, HttpStatus.BAD_REQUEST,
            "missing X-Real-IP header", 0);
      }
      String bin = headerOrEmpty(exchange, "X-Card-Bin");
      String last4 = headerOrEmpty(exchange, "X-Card-Last4");
      String fingerprint = fingerprint(bin, last4, ip);
      String asn = "AS0_VN";  // TODO MaxMind GeoLite2

      // BIN velocity check (runs first — cheaper; if exceeded, no need to query the token bucket).
      if (BIN_PATTERN.matcher(bin).matches()) {
        long windowMs = (long) config.windowMinutes * 60_000L;
        long nowMs = System.currentTimeMillis();
        long windowStart = (nowMs / windowMs) * windowMs;
        String binKey = "rl:bin:" + bin + ":" + windowStart;
        return redis.execute(binVelocityScript, List.of(binKey), String.valueOf(nowMs), String.valueOf(windowMs))
            .next()
            .flatMap(countObj -> {
              long count = ((Number) countObj).longValue();
              lastBinVelocityCount.set(count);
              if (count > config.binVelocityMaxAttempts) {
                binVelocityBlockedCounter.increment();
                return shortCircuit(exchange, HttpStatus.TOO_MANY_REQUESTS,
                    "BIN velocity exceeded", (long) (windowMs / 1000));
              }
              return tokenBucketCheck(exchange, chain, config, ip, fingerprint, asn);
            })
            .onErrorResume(e -> {
              // HIGH-3: fail-closed on Redis errors (NFR-AVAIL-4).
              errorCounter.increment();
              log.warn("Redis error in BIN velocity check, failing closed: {}", e.getMessage());
              return shortCircuit(exchange, HttpStatus.TOO_MANY_REQUESTS,
                  "rate_limiter_unavailable", 60);
            });
      }
      return tokenBucketCheck(exchange, chain, config, ip, fingerprint, asn);
    };
  }

  private Mono<Void> tokenBucketCheck(
      org.springframework.web.server.ServerWebExchange exchange,
      org.springframework.cloud.gateway.filter.GatewayFilterChain chain,
      Config config, String ip, String fingerprint, String asn) {
    List<String> keys = new ArrayList<>(3);
    keys.add("rl:ip:" + ip);
    keys.add("rl:fp:" + fingerprint);
    keys.add("rl:asn:" + asn);

    return redis.execute(rateLimiterScript, keys,
            String.valueOf(config.capacity),
            String.valueOf(config.refillPerSec),
            String.valueOf(config.ttlSec),
            String.valueOf(config.cost),
            String.valueOf(keys.size()))
        .next()
        .flatMap(resultObj -> {
          @SuppressWarnings("unchecked")
          List<Long> result = (List<Long>) resultObj;
          long allowed = result.get(0);
          long remaining = result.get(1);
          long retryAfter = result.get(2);

          ServerHttpResponse response = exchange.getResponse();
          response.getHeaders().add("RateLimit-Limit", String.valueOf(config.capacity));
          response.getHeaders().add("RateLimit-Remaining", String.valueOf(Math.max(0, remaining)));
          response.getHeaders().add("RateLimit-Reset", String.valueOf(retryAfter));

          if (allowed == 0L) {
            tokenBucketBlockedCounter.increment();
            response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            response.getHeaders().add("Retry-After", String.valueOf(retryAfter));
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            DataBuffer body = response.bufferFactory().wrap(
                ("{\"error\":\"rate_limited\",\"retry_after\":" + retryAfter + "}").getBytes(StandardCharsets.UTF_8));
            return response.writeWith(Mono.just(body));
          }
          allowedCounter.increment();
          return chain.filter(exchange);
        })
        .onErrorResume(e -> {
          // HIGH-3: fail-closed on Redis errors (NFR-AVAIL-4).
          errorCounter.increment();
          log.warn("Redis error in token bucket check, failing closed: {}", e.getMessage());
          return shortCircuit(exchange, HttpStatus.TOO_MANY_REQUESTS,
              "rate_limiter_unavailable", 60);
        });
  }

  private static Mono<Void> shortCircuit(
      org.springframework.web.server.ServerWebExchange exchange,
      HttpStatus status, String message, long retryAfterSec) {
    ServerHttpResponse response = exchange.getResponse();
    response.setStatusCode(status);
    response.getHeaders().add("Retry-After", String.valueOf(retryAfterSec));
    response.getHeaders().add("RateLimit-Limit", "BIN_VELOCITY");
    response.getHeaders().add("RateLimit-Remaining", "0");
    response.getHeaders().add("RateLimit-Reset", String.valueOf(retryAfterSec));
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    DataBuffer body = response.bufferFactory().wrap(
        ("{\"error\":\"" + message + "\",\"retry_after\":" + retryAfterSec + "}")
            .getBytes(StandardCharsets.UTF_8));
    return response.writeWith(Mono.just(body));
  }

  private static String ipFrom(org.springframework.web.server.ServerWebExchange exchange) {
    HttpHeaders headers = exchange.getRequest().getHeaders();
    String real = headers.getFirst("X-Real-IP");
    if (real == null || real.isBlank()) {
      return null;
    }
    return IP_PATTERN.matcher(real).matches() ? real : null;
  }

  private static String headerOrEmpty(
      org.springframework.web.server.ServerWebExchange exchange, String name) {
    String v = exchange.getRequest().getHeaders().getFirst(name);
    return (v == null || v.isBlank()) ? "" : v;
  }

  private static String fingerprint(String bin, String last4, String ip) {
    // Per MEDIUM-3 fix: only hash if BIN matches the 6-digit format AND last4 matches 4 digits.
    // Otherwise use a per-IP "anon" key to prevent cross-request collisions.
    if (!BIN_PATTERN.matcher(bin).matches() || !LAST4_PATTERN.matcher(last4).matches()) {
      return "anon:" + ip;
    }
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest((bin + last4).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      return "anon:" + ip;
    }
  }
}