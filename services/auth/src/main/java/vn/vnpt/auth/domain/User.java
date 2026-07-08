package vn.vnpt.auth.domain;

import java.time.LocalDateTime;

/** User aggregate root — Story 5.4. */
public record User(
    long id,
    String email,
    String passwordHash,
    Role role,
    boolean mfaEnrolled,
    int failedAttempts,
    LocalDateTime lockedUntil,
    Long customerId,
    boolean emailVerified,
    LocalDateTime createdAt,
    LocalDateTime lastLoginAt) {
}