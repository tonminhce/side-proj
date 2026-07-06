package vn.vnpt.util.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.RoundingMode;

@Target(value = { ElementType.FIELD })
@Retention(RetentionPolicy.RUNTIME)
public @interface ValueDefinition {

    int decimalScale() default 0;

    RoundingMode roundingMode() default RoundingMode.HALF_EVEN;

    String dateFormat() default "";
}
