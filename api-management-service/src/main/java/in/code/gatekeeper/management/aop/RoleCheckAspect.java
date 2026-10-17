package in.code.gatekeeper.management.aop;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs before every method of a class or method annotated with @RequiredRole.
 * By this point the security filter has already verified the JWT and stored the caller's
 * ROLE_xxx authority in the SecurityContext; this aspect only decides whether that role is allowed.
 */
@Aspect
@Component
public class RoleCheckAspect {

    @Before("@within(in.code.gatekeeper.management.aop.RequiredRole) "
            + "|| @annotation(in.code.gatekeeper.management.aop.RequiredRole)")
    public void checkRole(JoinPoint joinPoint) {
        RequiredRole requiredRole = findRequiredRole(joinPoint);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Set<String> callerAuthorities = authentication == null ? Set.of() : authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        boolean allowed = Arrays.stream(requiredRole.value())
                .anyMatch(role -> callerAuthorities.contains("ROLE_" + role.name()));
        if (!allowed) {
            throw new AccessDeniedException("Requires role " + Arrays.toString(requiredRole.value()));
        }
    }

    // A method-level annotation overrides the class-level one
    private RequiredRole findRequiredRole(JoinPoint joinPoint) {
        RequiredRole onMethod = AnnotatedElementUtils.findMergedAnnotation(
                ((MethodSignature) joinPoint.getSignature()).getMethod(), RequiredRole.class);
        if (onMethod != null) {
            return onMethod;
        }
        return AnnotatedElementUtils.findMergedAnnotation(AopUtils.getTargetClass(joinPoint.getTarget()),
                RequiredRole.class);
    }
}
