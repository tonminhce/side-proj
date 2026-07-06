package vn.vnpt.util.common;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.expression.SecurityExpressionRoot;
import org.springframework.security.access.expression.method.MethodSecurityExpressionOperations;
import org.springframework.security.core.Authentication;

@Slf4j
public class CustomSecurityExpressionRoot extends SecurityExpressionRoot
    implements MethodSecurityExpressionOperations {
  @Getter @Setter private Object filterObject;
  @Getter @Setter private Object returnObject;
  private Object target;

  public CustomSecurityExpressionRoot(Authentication authentication) {
    super(authentication);
  }

  public Object getThis() {
    return this.target;
  }

  public void setThis(Object target) {
    this.target = target;
  }
}
