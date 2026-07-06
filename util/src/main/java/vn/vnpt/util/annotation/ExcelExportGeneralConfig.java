package vn.vnpt.util.annotation;

import java.lang.annotation.*;

@Target(value = {ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(ExcelExportGeneralConfig.List.class)
public @interface ExcelExportGeneralConfig {

  String exportNameCombine() default "";

  String exportName();

  String template();

  int startRow() default 1;

  boolean hasSampleRow() default false;

  boolean isEvaluateFormula() default true;

  int[] lockSheetIndex() default {};

  String styleTemplateName() default "";

  Class<?>[] groups() default {};

  boolean dynamicTitle() default false;

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE})
  @Documented
  @interface List {
    ExcelExportGeneralConfig[] value();
  }
}
