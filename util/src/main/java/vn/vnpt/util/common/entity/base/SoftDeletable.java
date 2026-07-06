package vn.vnpt.util.common.entity.base;

/**
 * Marker interface cho entity có cột {@code is_deleted}.
 *
 * <p>{@code SoftDeleteMetadataRegistry} bắt buộc entity gắn {@code @SoftUk} phải implement
 * interface này (để xây Criteria predicate {@code isDeleted = false} an toàn lúc runtime).
 * {@link RootEntity} đã implement sẵn — mọi entity kế thừa {@code RootEntity}/{@code BaseEntity}
 * tự động thỏa điều kiện này, không cần khai báo gì thêm.
 */
public interface SoftDeletable {
    Boolean getIsDeleted();
    void setIsDeleted(Boolean isDeleted);
}
