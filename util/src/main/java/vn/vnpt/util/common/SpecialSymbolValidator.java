package vn.vnpt.util.common;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import vn.vnpt.util.annotation.SpecialSymbolConstraint;

public class SpecialSymbolValidator
    implements ConstraintValidator<SpecialSymbolConstraint, String> {
  private static final String SPECIAL_PATTERN =
      "^[a-zA-Z0-9_ÀÁÂÃÈÉÊÌÍÒÓÔÕÙÚĂĐĨŨƠàáâãèéêìíòóôõùúăđĩũơƯĂẠẢẤẦẨẪẬẮẰẲẴẶẸẺẼỀỀỂưăạảấầẩẫậắằẳẵặẹẻẽềềểỄỆỈỊỌỎỐỒỔỖỘỚỜỞỠỢỤỦỨỪễệỉịọỏốồổỗộớờởỡợụủứừỬỮỰỲỴÝỶỸửữựỳỵỷỹếẾ,-; ]*$";
  private Pattern pattern;
  private Matcher matcher;

  public SpecialSymbolValidator() {
    pattern = Pattern.compile(SPECIAL_PATTERN);
  }

  @Override
  public void initialize(SpecialSymbolConstraint constraintAnnotation) {
    constraintAnnotation.message();
  }

  @Override
  public boolean isValid(String attributed, ConstraintValidatorContext constraintValidatorContext) {
    if (attributed == null || attributed.isEmpty()) {
      return true;
    }
    matcher = pattern.matcher(attributed);
    return matcher.matches();
  }
}
