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
*/
package vn.vnpt.util.common;

import java.security.SecureRandom;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicLong;

public class SnowflakeIdGenerator {
    private static final long EPOCH = 1735689600000L; // 2025-01-01 00:00:00 UTC+7
    private static final long TIMESTAMP_BITS = 48L;
    private static final long SEQUENCE_BITS = 12L; // Cho phép 4096 ID mỗi mili-giây
    private static final long WORKER_BITS = 3L;  // Tối đa 8 worker ID
    private static final long SEQUENCE_MASK = -1L ^ (-1L << SEQUENCE_BITS);

//  -1L << x tạo ra số có tất cả các bit ngoài x bit đầu tiên là 1, khi XOR -1L sẽ tạo ra số có x bit đều là 1.
//  VD: -1L ^ (-1L << 5) = 31 (tương ứng 11111 trong nhị phân).

    private static long workerId;
    private static final AtomicLong sequence = new AtomicLong(0L);
    private static final AtomicLong lastTimestamp = new AtomicLong(-1L);

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
        return ((timestamp - EPOCH) << (WORKER_BITS + SEQUENCE_BITS)) |
                (workerId << SEQUENCE_BITS) |
                sequence.get();
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

    public static long getWorkerIdFromPod() {
        String podName = System.getenv("POD_NAME"); // Lấy POD_NAME từ biến môi trường

        if (podName == null || !podName.matches(".*-(\\d+)$")) {
            return new SecureRandom().nextInt(8); // Nếu không có, sinh workerId random (0-7)
        }

        return Long.parseLong(podName.replaceAll(".*-(\\d+)$", "$1")) % 8; // Giới hạn workerId từ 0-7
    }
}
