package vn.vnpt.customer.infrastructure.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Address entity — Story 5.1 / FR-47. Owned by the Customer aggregate; FK to {@code customer.id}
 * with cascade-delete (deleting a Customer removes its addresses).
 */
@Entity
@Table(name = "address")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class AddressEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "customer_id", nullable = false)
  private CustomerEntity customer;

  @Column(name = "line1", nullable = false, length = 512)
  private String line1;

  @Column(name = "province_code", nullable = false, length = 16)
  private String provinceCode;

  @Column(name = "district_code", nullable = false, length = 16)
  private String districtCode;

  @Column(name = "commune_code", nullable = false, length = 16)
  private String communeCode;

  @Column(name = "is_default", nullable = false)
  private boolean isDefault;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;
}