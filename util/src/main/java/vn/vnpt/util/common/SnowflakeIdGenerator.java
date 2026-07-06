/*
    Mặc định, Snowflake ID có cấu trúc:
    | Timestamp (41 bits) | Datacenter ID (5 bits) | Machine ID (5 bits) | Sequence (12 bits) |
    Có thể tạo 4096 ID/ms/máy (~4 triệu ID/giây trên 1000 máy).

    Với database đơn lẻ, ta có thể thay đổi thành:
    | Timestamp (48 bits) | Machine ID (3 bits) | Sequence (12 bits) |

    Nếu Bỏ datacenterId & machineId → chỉ cần 1 server.
    Tăng sequence từ 12 bits lên 15 bits → hỗ trợ 32,768 ID/ms.

    Lợi ích:
    + Tốc độ cao: Hỗ trợ 32,768 ID/ms.
    + Chạy tốt trên 1 database mà không cần cluster.
    + Dễ mở rộng: Nếu sau này cần phân tán, chỉ cần thêm datacenterId.

    Story 0.5 (ADR-22 / R-08): worker-id resolution is now profile-aware. In non-dev profiles
    (prod / staging), a missing or malformed POD_NAME throws WorkerIdMissingException at boot
    instead of silently falling back to SecureRandom. The dev profile keeps the SecureRandom
    fallback (with a WARN log line). Note: the static `workerId` field is unchanged; that
    static-state issue is tracked separately per local-docs/10 §6 #1.
*/
package vn.vnpt.util.common;

import java.security.SecureRandom;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SnowflakeIdGenerator {
  private static final long EPOCH = 1735689600000L; // 2025-01-01 00:00:00 UTC+7
  private static final long TIMESTAMP_BITS = 48L;
  private static final long SEQUENCE_BITS = 12L; // Cho phép 4096 ID mỗi mili-giây
  private static final long WORKER_BITS = 3L; // Tối đa 8 worker ID
  private static final long SEQUENCE_MASK = -1L ^ (-1L << SEQUENCE_BITS);

  //  -1L << x tạo ra số có tất cả các bit ngoài x bit đầu tiên là 1, khi XOR -1L sẽ tạo ra số có x
  // bit đều là 1.
  //  VD: -1L ^ (-1L << 5) = 31 (tương ứng 11111 trong nhị phân).

  private static long workerId;
  private static final AtomicLong sequence = new AtomicLong(0L);
  private static final AtomicLong lastTimestamp = new AtomicLong(-1L);

  // ponytail: explicit Logger field matches the rest of util/common (no Lombok @Slf4j here).
  private static final Logger log = LoggerFactory.getLogger(SnowflakeIdGenerator.class);

  public SnowflakeIdGenerator(long workerId) {
    SnowflakeIdGenerator.workerId = workerId;
  }

  public static synchronized long generateId() {
    long timestamp = System.currentTimeMillis();
    long lastTs = lastTimestamp.get();

    if (timestamp < lastTs) {
      throw new RuntimeException("Clock moved backwards. Refusing to generate id");
    }

    if (timestamp == lastTs) {
      long seq = sequence.incrementAndGet() & SEQUENCE_MASK;
      if (seq == 0) {
        timestamp = waitForNextMillis(lastTs);
      }
    } else {
      sequence.set(0L);
    }

    lastTimestamp.set(timestamp);
    return ((timestamp - EPOCH) << (WORKER_BITS + SEQUENCE_BITS))
        | (workerId << SEQUENCE_BITS)
        | sequence.get();
  }

  private static long waitForNextMillis(long lastTimestamp) {
    long timestamp = System.currentTimeMillis();
    while (timestamp <= lastTimestamp) {
      timestamp = System.currentTimeMillis();
    }
    return timestamp;
  }

  private long genEpoch() {
    ZonedDateTime zdt = ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC"));
    ZonedDateTime zdtPlus7 = zdt.withZoneSameInstant(ZoneId.of("Asia/Ho_Chi_Minh")); // UTC+7
    return zdtPlus7.toInstant().toEpochMilli();
  }

  /**
   * Reads {@code spring.profiles.active} from system properties first, then falls back to the
   * {@code SPRING_PROFILES_ACTIVE} environment variable (Spring's own precedence). Returns the
   * trimmed value, or an empty string when unset. Note: {@link #getWorkerIdFromPod()} is a static
   * method called from a {@code @Bean} factory with no injected {@code Environment}, so reading
   * system properties is the documented escape hatch.
   */
  private static String resolveActiveProfile() {
    String fromProperty = System.getProperty("spring.profiles.active");
    if (fromProperty != null && !fromProperty.isBlank()) {
      return fromProperty.trim();
    }
    String fromEnv = System.getenv("SPRING_PROFILES_ACTIVE");
    return fromEnv == null ? "" : fromEnv.trim();
  }

  /**
   * Returns {@code true} when the profile string is null/empty or contains {@code "dev"} (case
   * insensitive). The profile string is comma-separated; this is an intentional loose match per
   * Story 0.5 Subtask 2.2 (ponytail: don't parse CSV rigorously; R-08 is about deploy-time
   * detection, not profile-string parsing).
   */
  private static boolean isDevProfile(String profile) {
    return profile == null || profile.isEmpty() || profile.toLowerCase().contains("dev");
  }

  public static long getWorkerIdFromPod() {
    String podName = System.getenv("POD_NAME");
    String profile = resolveActiveProfile();

    // Branch A — POD_NAME present and matches replica-suffix pattern: deterministic worker id.
    if (podName != null && podName.matches(".*-(\\d+)$")) {
      return Long.parseLong(podName.replaceAll(".*-(\\d+)$", "$1")) % 8; // 0-7
    }

    // Branch B — dev profile (or unset): keep SecureRandom fallback, with a WARN log line.
    if (isDevProfile(profile)) {
      log.warn(
          "SnowflakeIdGenerator: POD_NAME not set in profile '{}' — falling back to SecureRandom",
          profile.isEmpty() ? "(unset)" : profile);
      return new SecureRandom().nextInt(8); // 0-7
    }

    // Branch C — non-dev profile (prod / staging): throw at boot per ADR-22 / R-08.
    throw new WorkerIdMissingException(
        "POD_NAME env var is required in profile '"
            + (profile.isEmpty() ? "(unset)" : profile)
            + "' for Snowflake worker-id (ADR-22). POD_NAME='"
            + (podName == null ? "null" : podName)
            + "'. Set POD_NAME (K8s downward API: fieldRef: metadata.name) OR run with"
            + " spring.profiles.active=dev for local dev.");
  }
}
