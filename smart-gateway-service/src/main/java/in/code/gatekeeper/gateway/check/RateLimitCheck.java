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
 * While Redis is down each instance enforces its share of the limit on its own (RedisWindowCounter).
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
        RedisWindowCounter.Window window = counter.hit("rate:" + organization.organizationId() + ":" + route.routeId());
        if (counter.exceeds(window, limit)) {
            throw GatewayException.tooManyRequests("RATE_LIMITED", "Rate limit for this route exceeded",
                    window.secondsLeft());
        }
    }
}
