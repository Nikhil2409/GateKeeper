package in.code.gatekeeper.gateway.util;

import in.code.gatekeeper.gateway.check.RequestGuard.PublicPath;
import in.code.gatekeeper.gateway.exception.GatewayException;
import in.code.gatekeeper.gateway.filter.TrafficEventFilter;
import in.code.gatekeeper.gateway.model.RouteConfig;
import in.code.gatekeeper.gateway.registry.OrgRoutes;
import in.code.gatekeeper.gateway.registry.RouteRegistry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Finds what a request is for: the active organization by slug, then the exact route template, then checks the
 * method. Unknown, suspended and deleted organizations and unknown paths answer 404; a known path with a method the
 * route doesn't allow answers 405 (no fall-through to another route). Organization and route are left on the
 * request for the traffic event.
 */
@Component
@RequiredArgsConstructor
public class RouteResolver {

    private final RouteRegistry routeRegistry;

    public record ResolvedRoute(OrgRoutes organization, RouteConfig route) {
    }

    public ResolvedRoute resolve(PublicPath path, HttpServletRequest request) {
        OrgRoutes organization = routeRegistry.find(path.slug())
                .filter(OrgRoutes::isActive)
                .orElseThrow(GatewayException::notFound);
        request.setAttribute(TrafficEventFilter.ORGANIZATION_ID, organization.organizationId());
        request.setAttribute(TrafficEventFilter.ORGANIZATION_SLUG, organization.slug());

        RouteConfig route = organization.matcher().match(path.segments())
                .orElseThrow(GatewayException::notFound);
        request.setAttribute(TrafficEventFilter.ROUTE_ID, route.routeId());

        if (!route.allowedMethods().contains(request.getMethod())) {
            throw GatewayException.methodNotAllowed(route.allowedMethods());
        }
        return new ResolvedRoute(organization, route);
    }
}
