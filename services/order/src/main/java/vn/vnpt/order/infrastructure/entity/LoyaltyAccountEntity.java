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

/** Loyalty points account — Story 5.6 / FR-50. Keyed by customerId (which equals userId in v1). */
@Entity
@Table(name = "loyalty_account")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class LoyaltyAccountEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "customer_id", nullable = false, unique = true)
  private Long customerId;

  @Column(name = "points", nullable = false)
  private Long points;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;
}