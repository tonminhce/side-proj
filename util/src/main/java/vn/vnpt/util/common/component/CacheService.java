package vn.vnpt.util.common.component;

import java.io.Serializable;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class CacheService {
  private final RedisTemplate<String, Object> redisTemplate;

  public <E, D extends Serializable> D getOrCache(
      String redisKey, Function<E, D> mapper, Supplier<E> supplier) {
    return getOrCache(redisKey, mapper, supplier, null);
  }

  public <E, D extends Serializable> D getOrCache(
      String redisKey, Function<E, D> mapper, Supplier<E> supplier, Duration ttl) {
    try {
      Object cachedValue = redisTemplate.opsForValue().get(redisKey);
      if (cachedValue != null) {
        @SuppressWarnings("unchecked")
        D result = (D) cachedValue;
        return result;
      }
      log.debug("Cache MISS for key: {}", redisKey);
      Object entity = supplier.get();
      if (entity == null) {
        return null;
      }
      E result = null;
      if (entity instanceof Optional) {
        result = ((Optional<E>) entity).orElse(null);
      } else {
        result = (E) entity;
      }
      // Map
      D dto = mapper.apply(result);
      if (ttl != null) {
        redisTemplate.opsForValue().set(redisKey, dto, ttl);
        log.debug("Cached with TTL {} for key: {}", ttl, redisKey);
      } else {
        redisTemplate.opsForValue().set(redisKey, dto);
        log.debug("Cached without TTL for key: {}", redisKey);
      }
      return dto;
    } catch (Exception e) {
      throw new RuntimeException("Cache operation failed", e);
    }
  }

  public boolean isExtInfoCached(String userExternalId) {
    return redisTemplate.hasKey("user_ext:" + userExternalId);
  }
}
