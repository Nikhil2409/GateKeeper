package in.code.gatekeeper.gateway.check;

import in.code.gatekeeper.gateway.client.RedisWindowCounter;
import in.code.gatekeeper.gateway.exception.GatewayException;
import in.code.gatekeeper.gateway.model.RouteConfig;
import in.code.gatekeeper.gateway.registry.OrgRoutes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Per-route rate limit: requestsPerMinute across ALL callers and all gateway instances (the counter lives in Redis),
 * protecting the tenant's upstream service. No limit configured = unlimited.
 * Fails open like the quota: if Redis is down the request passes.
 */
@Component
@RequiredArgsConstructor
public class RateLimitCheck {

    private final RedisWindowCounter counter;

    public void check(OrgRoutes organization, RouteConfig route) {
        Integer limit = route.requestsPerMinute();
        if (limit == null) {
            return;
        }
        counter.hit("rate:" + organization.organizationId() + ":" + route.routeId())
                .filter(window -> window.count() > limit)
                .ifPresent(window -> {
                    throw GatewayException.tooManyRequests("Rate limit for this route exceeded", window.secondsLeft());
                });
    }
}
