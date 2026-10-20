package in.code.gatekeeper.gateway.check;

import in.code.gatekeeper.gateway.client.RedisWindowCounter;
import in.code.gatekeeper.gateway.exception.GatewayException;
import in.code.gatekeeper.gateway.model.RouteConfig;
import in.code.gatekeeper.gateway.registry.OrgRoutes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Per-user quota on a route: perUserRequestsPerMinute (a tenth of the route limit), counted per user in the same
 * 1-minute window as the route's rate limit, so one user can't use up the whole route.
 * While Redis is down each instance enforces its share of the quota on its own (RedisWindowCounter): approximate,
 * but a client can't take a backend down just because Redis is.
 */
@Component
@RequiredArgsConstructor
public class QuotaCheck {

    private final RedisWindowCounter counter;

    // userId comes from the verified user token (step 4); without one there is nobody to count
    public void check(OrgRoutes organization, RouteConfig route, String userId) {
        Integer limit = route.perUserRequestsPerMinute();
        if (limit == null || userId == null) {
            return;
        }
        RedisWindowCounter.Window window = counter.hit(
                "quota:" + organization.organizationId() + ":" + route.routeId() + ":" + userId);
        if (counter.exceeds(window, limit)) {
            throw GatewayException.tooManyRequests("QUOTA_EXCEEDED", "Request quota for this user exceeded",
                    window.secondsLeft());
        }
    }
}
