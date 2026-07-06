package vn.vnpt.util.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Target(value = {ElementType.TYPE})
public @interface ExcelImportConfig {

  int sheetIndex() default 0;

  int startColumnIndex() default 0;

  int headerRowIndex() default 0;

  int dataStartRowIndex() default 0;
}
