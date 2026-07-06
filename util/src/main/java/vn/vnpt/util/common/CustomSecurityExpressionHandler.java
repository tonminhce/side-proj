package vn.vnpt.util.common;

import org.aopalliance.intercept.MethodInvocation;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionOperations;
import org.springframework.security.core.Authentication;

import java.util.Objects;


public class CustomSecurityExpressionHandler extends DefaultMethodSecurityExpressionHandler {
    public CustomSecurityExpressionHandler() {
    }

    @Override
    protected MethodSecurityExpressionOperations createSecurityExpressionRoot(Authentication authentication, MethodInvocation invocation) {
        StandardEvaluationContext context = (StandardEvaluationContext) super.createEvaluationContext(authentication, invocation);
        CustomSecurityExpressionRoot root = new CustomSecurityExpressionRoot(authentication);
        root.setThis(invocation.getThis());
        root.setPermissionEvaluator(this.getPermissionEvaluator());
        root.setTrustResolver(this.getTrustResolver());
        root.setRoleHierarchy(Objects.requireNonNull(this.getRoleHierarchy()));
        root.setDefaultRolePrefix(this.getDefaultRolePrefix());
        context.setRootObject(root);
        return root;
    }
}
