package vn.vnpt.util.component.softdelete.annotation;

import java.lang.annotation.*;

/** Container cho phép khai báo nhiều {@link SoftUk} trên cùng một entity. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SoftUks {
  SoftUk[] value();
}
