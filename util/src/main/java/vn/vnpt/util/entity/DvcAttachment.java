package vn.vnpt.util.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.vnpt.util.common.entity.base.BaseEntity;

@Entity
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Table(name = "DVC_ATTACHMENT")
@AttributeOverrides({
  @AttributeOverride(name = "uuid", column = @Column(name = "UUID", nullable = false))
})
public class DvcAttachment extends BaseEntity {
  @Column(name = "DVC_APP_UUID")
  private Long dvcAppUuid;

  @Column(name = "DVC_ID_GIAY_TO")
  private String dvcIdGiayTo;

  @Column(name = "DVC_MA_THANH_PHAN")
  private String dvcMaThanhPhan;

  @Column(name = "DVC_TEN_GIAY_TO")
  private String dvcTenGiayTo;

  @Column(name = "DVC_TEN_LOAI_GIAY_TO")
  private String dvcTenLoaiGiayTo;

  @Column(name = "DVC_HASH")
  private String dvcHash;

  @Column(name = "MINIO_BUCKET")
  private String minioBucket;

  @Column(name = "MINIO_OBJECT_NAME")
  private String minioObjectName;

  @Column(name = "FILE_TYPE")
  private String fileType;
}
