package vn.vnpt.util.annotation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;
import vn.vnpt.util.common.SpecialSymbolValidator;

@Documented
@Constraint(validatedBy = SpecialSymbolValidator.class)
@Target({ElementType.METHOD, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface SpecialSymbolConstraint {
  String message() default "Dữ liệu đầu vào chứ các ký hiệu không hợp lệ";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
