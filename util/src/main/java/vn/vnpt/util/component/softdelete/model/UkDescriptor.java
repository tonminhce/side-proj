package vn.vnpt.util.component.softdelete.model;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Mô tả 1 nhóm {@code @SoftUk} đã được resolve lúc startup bởi {@code SoftDeleteMetadataRegistry}.
 *
 * @param entity entity Java class khai báo nhóm UK này
 * @param table tên bảng DB (từ {@code @Table} hoặc fallback lowercase simple name)
 * @param name tên logic của nhóm UK, lấy từ {@code @SoftUk#name()}
 * @param javaFields field Java đã {@code setAccessible(true)}, theo đúng thứ tự khai báo
 * @param columns tên cột snake_case tương ứng, cùng thứ tự với {@code javaFields}
 */
public record UkDescriptor(
    Class<?> entity, String table, String name, List<Field> javaFields, List<String> columns) {
  public UkDescriptor {
    javaFields = List.copyOf(javaFields);
    columns = List.copyOf(columns);
  }
}
