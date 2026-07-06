package vn.vnpt.util.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Target(value = {ElementType.FIELD, ElementType.METHOD, ElementType.ANNOTATION_TYPE})
public @interface ExcelTitle {

  int beginCol() default -1;

  int endCol() default -1;

  int row() default -1;
}
