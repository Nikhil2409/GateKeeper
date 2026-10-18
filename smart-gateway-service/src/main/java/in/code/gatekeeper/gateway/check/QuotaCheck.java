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
 * Fails open: if Redis is down the request passes, because blocking all traffic is worse than a short unlimited spell.
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
        counter.hit("quota:" + organization.organizationId() + ":" + route.routeId() + ":" + userId)
                .filter(window -> window.count() > limit)
                .ifPresent(window -> {
                    throw GatewayException.tooManyRequests("Request quota for this user exceeded",
                            window.secondsLeft());
                });
    }
}
