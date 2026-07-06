package vn.vnpt.util.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Target(value = { ElementType.FIELD, ElementType.ANNOTATION_TYPE })
public @interface ExcelImport {

    int index() default 0;

}
