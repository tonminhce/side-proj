package vn.vnpt.util.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Target(value = { ElementType.FIELD, ElementType.METHOD, ElementType.ANNOTATION_TYPE })
public @interface ExcelExport {

    String columnLabel() default "";

    int index() default -1;

    int[] indexArray() default {};

    int startRow() default 0;

    boolean isDropdownList() default false;

    boolean isNotMergeCell() default false;

    boolean isCompare() default false;
}

