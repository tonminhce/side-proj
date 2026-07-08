package vn.vnpt.order.infrastructure.entity;

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

/** Loyalty points accrual — Story 5.6. One row per orderUuid (unique). */
@Entity
@Table(name = "loyalty_accrual")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class LoyaltyAccrualEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "order_uuid", nullable = false, unique = true)
  private Long orderUuid;

  @Column(name = "customer_id", nullable = false)
  private Long customerId;

  @Column(name = "points", nullable = false)
  private Integer points;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;
}