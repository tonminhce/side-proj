package vn.vnpt.util.common.entity.base;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.FieldNameConstants;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.ColumnDefault;
import vn.vnpt.util.common.CommonUtil;
import vn.vnpt.util.common.DatetimeUtil;
import vn.vnpt.util.common.ScheduleContext;
import java.io.Serial;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

@MappedSuperclass
@Data
@Slf4j
@FieldNameConstants
public class RootEntity implements Serializable, SoftDeletable {
    @Serial
    private static final long serialVersionUID = 2583527292636377207L;

    @Column(name = "id", nullable = false, insertable = false, updatable = false, unique = true)
    protected Long id;

    @Size(max = 36)
    @NotNull
    @Column(name = "created_by", nullable = false, updatable = false, length = 36)
    protected String createdBy;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    protected LocalDateTime createdAt;

    @Size(max = 36)
    @Column(name = "updated_by", insertable = false, length = 36)
    protected String updatedBy;

    @Column(name = "updated_at", insertable = false)
    protected LocalDateTime updatedAt;

    @Size(max = 36)
    @Column(name = "deleted_by", insertable = false, length = 36)
    protected String deletedBy;

    @Column(name = "deleted_at", insertable = false)
    protected LocalDateTime deletedAt;

    @NotNull
    @ColumnDefault("b'1'")
    @Column(name = "is_active", nullable = false)
    protected Boolean isActive = true;

    @NotNull
    @ColumnDefault("b'0'")
    @Column(name = "is_deleted", nullable = false)
    protected Boolean isDeleted = false;

    @PreUpdate
    public void preUpdate() {
        try {
            if (createdAt == null) {
                createdAt = DatetimeUtil.getCurrentLocalDateTime();
                if (this.createdBy == null) {
                    this.createdBy = resolveAccountId();
                }
            }
            if (Boolean.TRUE.equals(this.isDeleted)) {
                this.deletedAt = DatetimeUtil.getCurrentLocalDateTime();
                this.deletedBy = resolveAccountId();
            } else {
                this.updatedAt = DatetimeUtil.getCurrentLocalDateTime();
                if (this.updatedBy == null) {
                    this.updatedBy = resolveAccountId();
                }
            }
        } catch (Exception e) {
            log.error("Error in preUpdate: {}", e.getMessage(), e);
        }
    }

    protected String resolveAccountId() {
        try {
            if (ScheduleContext.isFromScheduler()) {
                return "AUTO_SCHEDULE";
            } else {
                String loggedAccountId = CommonUtil.getLoggedAccountId();
                return loggedAccountId != null && !loggedAccountId.isEmpty() ? loggedAccountId : "unknown";
            }
        } catch (Exception e) {
            log.warn("Unable to resolve account ID, fallback to 'unknown'", e);
            return "unknown";
        }
    }

    @PostLoad
    public void postLoad() {
        if (this.createdAt != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy");
            this.createdAtFormatted = sdf
                    .format(Date.from(this.createdAt.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant()));
        }
    }

    @Transient
    private String createdAtFormatted;
}
