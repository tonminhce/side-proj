package vn.vnpt.util.common.entity.base;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.FieldNameConstants;
import lombok.extern.slf4j.Slf4j;
import vn.vnpt.util.common.DatetimeUtil;
import vn.vnpt.util.common.SnowflakeIdGenerator;

import java.io.Serial;

@EqualsAndHashCode(callSuper = true)
@MappedSuperclass
@Data
@Slf4j
@FieldNameConstants
public abstract class BaseEntity extends RootEntity {
    @Serial
    private static final long serialVersionUID = 2583527292636377209L;

    @Id
    @Column(name = "uuid", nullable = false, updatable = false, unique = true)
    protected Long uuid;

    @PrePersist
    public void prePersist() {
        try {
            if (this.uuid == null) {
                this.uuid = SnowflakeIdGenerator.generateId();
            }
            this.createdAt = DatetimeUtil.getCurrentLocalDateTime();

            if (this.createdBy == null) {
                this.createdBy = resolveAccountId();
            }

            // if (this.isActive == null) {
            // this.isActive = true;
            // }

            this.isDeleted = false;
        } catch (Exception e) {
            log.error("Error in prePersist: {}", e.getMessage(), e);
        }
    }
}