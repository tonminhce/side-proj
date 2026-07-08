package vn.vnpt.order.infrastructure.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Order price snapshot — Story 4.1 / FR-31. Immutable after {@code order.placed}. The use case
 * layer enforces INSERT-only (no setter is exposed via the repository; the entity has setters for
 * builder use only).
 *
 * <p>Natural key is the {@code order_uuid} (no surrogate ID column).
 */
@Entity
@Table(name = "order_price_snapshot")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "orderUuid")
public class OrderPriceSnapshot {

  @Id
  @Column(name = "order_uuid", nullable = false)
  private Long orderUuid;

  @Column(name = "list_price_cents", nullable = false)
  private Long listPriceCents;

  @Column(name = "promo_codes")
  private String promoCodes;

  @Column(name = "tax_cents", nullable = false)
  private Long taxCents;

  @Column(name = "shipping_cents", nullable = false)
  private Long shippingCents;

  @Column(name = "total_cents", nullable = false)
  private Long totalCents;

  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  @Column(name = "captured_at", nullable = false)
  private LocalDateTime capturedAt;

  // Story 4.4 / FR-34: address JSONB column for the edit-after-pay use case.
  // @JdbcTypeCode(SqlTypes.JSON) tells Hibernate to map the String field to the jsonb column
  // type (the Postgres driver handles the cast). The column is INSERT-only at the application
  // layer; price data stays immutable per FR-31.
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "address_json", columnDefinition = "jsonb")
  private String addressJson;
}