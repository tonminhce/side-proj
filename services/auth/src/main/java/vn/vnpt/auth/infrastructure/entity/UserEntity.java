package vn.vnpt.auth.infrastructure.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.vnpt.auth.domain.Role;

@Entity
@Table(name = "users")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class UserEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "email", nullable = false, length = 255, unique = true)
  private String email;

  @Column(name = "password_hash", nullable = false, length = 255)
  private String passwordHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 16)
  private Role role;

  @Column(name = "mfa_enrolled", nullable = false)
  private boolean mfaEnrolled;

  @Column(name = "mfa_secret", length = 64)
  private String mfaSecret;

  @Column(name = "failed_attempts", nullable = false)
  private int failedAttempts;

  @Column(name = "locked_until")
  private LocalDateTime lockedUntil;

  @Column(name = "customer_id")
  private Long customerId;

  @Column(name = "email_verified", nullable = false)
  private boolean emailVerified;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "last_login_at")
  private LocalDateTime lastLoginAt;
}