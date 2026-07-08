package vn.vnpt.customer.infrastructure.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * Customer aggregate root (JPA) — Story 5.1 / FR-45. References auth User by
 * {@code userId} (no FK at the DB level yet — auth service lands in Story 5.4).
 */
@Entity
@Table(name = "customer")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class CustomerEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "display_name", nullable = false, length = 255)
  private String displayName;

  @Column(name = "email", length = 255)
  private String email;

  @Column(name = "phone", length = 32)
  private String phone;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;
}