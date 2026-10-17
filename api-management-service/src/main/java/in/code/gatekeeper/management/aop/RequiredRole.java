package in.code.gatekeeper.management.aop;

import in.code.gatekeeper.management.enums.RoleName;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiredRole {

    RoleName[] value();
}
