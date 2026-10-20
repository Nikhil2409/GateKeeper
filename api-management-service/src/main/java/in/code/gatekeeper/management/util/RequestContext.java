package in.code.gatekeeper.management.util;

import in.code.gatekeeper.management.config.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

// Who is making the current change and under which correlation id ("system" outside a user request)
public final class RequestContext {

    private RequestContext() {
    }

    public static String username() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || !authentication.isAuthenticated() ? "system" : authentication.getName();
    }

    public static String correlationId() {
        return MDC.get(CorrelationIdFilter.MDC_KEY);
    }
}
