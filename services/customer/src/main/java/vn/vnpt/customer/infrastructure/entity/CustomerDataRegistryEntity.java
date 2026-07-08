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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Cross-service PDPD data registry — Story 5.2. */
@Entity
@Table(name = "customer_data_registry")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class CustomerDataRegistryEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "service_name", nullable = false, length = 64)
  private String serviceName;

  @Column(name = "table_name", nullable = false, length = 64)
  private String tableName;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "columns", nullable = false, columnDefinition = "jsonb")
  private String columns;

  @Column(name = "format", nullable = false, length = 16)
  private String format;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;
}