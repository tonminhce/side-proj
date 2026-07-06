package vn.vnpt.util.component.softdelete.annotation;

import java.lang.annotation.*;

/**
 * Khai báo một nhóm khóa duy nhất (unique key) trên entity, chỉ tính trên các bản ghi còn sống
 * ({@code is_deleted = false}). Bản ghi đã soft-delete không tính vào kiểm tra trùng, nên giá trị
 * có thể tái sử dụng sau khi bản ghi cũ bị xóa mềm.
 *
 * <pre>{@code
 * @Entity
 * @Table(name = "process_template")
 * @SoftUk(name = "process_name_per_enterprise", fields = {"enterpriseId", "name"})
 * public class ProcessTemplate extends BaseEntity { ... }
 * }</pre>
 *
 * <p>Gắn nhiều {@code @SoftUk} trên cùng entity được hỗ trợ nhờ {@link SoftUks}.
 *
 * @see vn.vnpt.util.component.softdelete.validator.UkValidator
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Repeatable(SoftUks.class)
public @interface SoftUk {

  /** Tên logic của nhóm UK — dùng trong thông báo lỗi. */
  String name();

  /** Tên các field Java (không phải tên cột) tạo thành khóa duy nhất. */
  String[] fields();

  /**
   * Tên cột DB tương ứng (tùy chọn) — phải khớp 1:1 độ dài với {@link #fields()} nếu khai báo. Nếu
   * để rỗng, tên cột được suy ra từ field theo quy tắc camelCase → snake_case.
   */
  String[] columns() default {};
}
