package in.code.gatekeeper.management.service;

import in.code.gatekeeper.management.dto.requestDto.HeaderRuleRequest;
import in.code.gatekeeper.management.dto.requestDto.RouteRequest;
import in.code.gatekeeper.management.dto.responseDto.RouteResponse;
import in.code.gatekeeper.management.entity.ApiRoute;
import in.code.gatekeeper.management.entity.Organization;
import in.code.gatekeeper.management.entity.RouteHeaderRule;
import in.code.gatekeeper.management.enums.HeaderAction;
import in.code.gatekeeper.management.exception.ApiException;
import in.code.gatekeeper.management.repository.ApiRouteRepository;
import in.code.gatekeeper.management.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Every method takes the organizationId from the caller's JWT, never from the request.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class TenantService {

    private final ApiRouteRepository apiRouteRepository;
    private final OrganizationRepository organizationRepository;

    @Transactional(readOnly = true)
    public List<RouteResponse> listRoutes(Long organizationId) {
        return apiRouteRepository.findAllByOrganizationIdOrderByRouteId(organizationId).stream()
                .map(RouteResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public RouteResponse getRoute(Long organizationId, String routeId) {
        return RouteResponse.from(findRoute(organizationId, routeId));
    }

    public RouteResponse createRoute(Long organizationId, RouteRequest request) {
        Organization organization = lockOrganization(organizationId);
        if (apiRouteRepository.existsByOrganizationIdAndRouteId(organizationId, request.routeId())) {
            throw ApiException.conflict("Route '" + request.routeId() + "' already exists (reactivate it if it was deleted)");
        }
        if (apiRouteRepository.existsByOrganizationIdAndPathPattern(organizationId, request.pathPattern())) {
            throw ApiException.conflict("Path '" + request.pathPattern() + "' is already mapped by another route");
        }

        ApiRoute route = new ApiRoute(organization, request.routeId());
        apply(route, request);
        bumpVersion(organization);
        return RouteResponse.from(apiRouteRepository.save(route));
    }

    public RouteResponse updateRoute(Long organizationId, String routeId, RouteRequest request) {
        if (!routeId.equals(request.routeId())) {
            throw ApiException.badRequest("routeId cannot be changed");
        }
        Organization organization = lockOrganization(organizationId);
        ApiRoute route = findRoute(organizationId, routeId);
        if (!route.getPathPattern().equals(request.pathPattern())
                && apiRouteRepository.existsByOrganizationIdAndPathPattern(organizationId, request.pathPattern())) {
            throw ApiException.conflict("Path '" + request.pathPattern() + "' is already mapped by another route");
        }

        apply(route, request);
        bumpVersion(organization);
        return RouteResponse.from(apiRouteRepository.saveAndFlush(route));
    }

    public RouteResponse setRouteActive(Long organizationId, String routeId, boolean active) {
        Organization organization = lockOrganization(organizationId);
        ApiRoute route = findRoute(organizationId, routeId);
        if (route.isActive() != active) {
            route.setActive(active);
            bumpVersion(organization);
            apiRouteRepository.saveAndFlush(route);
        }
        return RouteResponse.from(route);
    }

    // Taken first by every write, so changes inside one organization are serialized and versioned in order
    private Organization lockOrganization(Long organizationId) {
        return organizationRepository.findByIdForUpdate(organizationId)
                .orElseThrow(() -> ApiException.notFound("Organization " + organizationId + " not found"));
    }

    // Every configuration change inside an organization bumps its single version
    private void bumpVersion(Organization organization) {
        organization.incrementVersion();
    }

    private ApiRoute findRoute(Long organizationId, String routeId) {
        return apiRouteRepository.findByOrganizationIdAndRouteId(organizationId, routeId)
                .orElseThrow(() -> ApiException.notFound("Route '" + routeId + "' not found"));
    }

    private void apply(ApiRoute route, RouteRequest request) {
        route.setName(request.name().trim());
        route.setPathPattern(request.pathPattern());
        route.setTargetBaseUrl(request.targetBaseUrl());
        route.setTargetPathPrefix(request.targetPathPrefix() == null ? "" : request.targetPathPrefix());
        route.setRequestsPerMinute(request.requestsPerMinute());
        route.setResponseTimeoutMs(request.responseTimeoutMs());
        route.setIdempotencyEnabled(Boolean.TRUE.equals(request.idempotencyEnabled()));

        route.getAllowedMethods().clear();
        route.getAllowedMethods().addAll(request.allowedMethods());

        route.clearHeaderRules();
        List<HeaderRuleRequest> headerRules = request.headerRules() == null ? List.of() : request.headerRules();
        for (HeaderRuleRequest rule : headerRules) {
            boolean remove = rule.action() == HeaderAction.REMOVE;
            if (!remove && !StringUtils.hasText(rule.headerValue())) {
                throw ApiException.badRequest("Header '" + rule.headerName() + "' needs a value for " + rule.action());
            }
            route.addHeaderRule(new RouteHeaderRule(rule.direction(), rule.action(), rule.headerName(),
                    remove ? null : rule.headerValue()));
        }
    }
}
