package vn.vnpt.util.annotation;

import java.lang.annotation.*;

@Target(value = {ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(ExcelExportForm.List.class)
public @interface ExcelExportForm {

  String sheetName() default "";

  boolean compareSheetName() default false;

  int sheetIndex();

  String cell() default "";

  String[] cellArray() default {};

  int startRow() default 0; // for list object

  Class<?>[] groups() default {};

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.FIELD})
  @Documented
  @interface List {
    ExcelExportForm[] value();
  }
}
