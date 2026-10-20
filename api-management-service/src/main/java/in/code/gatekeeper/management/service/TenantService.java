package in.code.gatekeeper.management.service;

import in.code.gatekeeper.management.dto.ConfigSnapshot;
import in.code.gatekeeper.management.dto.ConfigSnapshot.RouteSnapshot;
import in.code.gatekeeper.management.dto.requestDto.HeaderRuleRequest;
import in.code.gatekeeper.management.dto.requestDto.RouteRequest;
import in.code.gatekeeper.management.dto.requestDto.TokenSettingsRequest;
import in.code.gatekeeper.management.dto.responseDto.ConfigPreviewResponse;
import in.code.gatekeeper.management.dto.responseDto.ConfigVersionResponse;
import in.code.gatekeeper.management.dto.responseDto.RollbackResponse;
import in.code.gatekeeper.management.dto.responseDto.RouteResponse;
import in.code.gatekeeper.management.dto.responseDto.TokenSettingsResponse;
import in.code.gatekeeper.management.entity.ApiRoute;
import in.code.gatekeeper.management.entity.Organization;
import in.code.gatekeeper.management.entity.OrganizationTokenSettings;
import in.code.gatekeeper.management.entity.RouteHeaderRule;
import in.code.gatekeeper.management.enums.ConfigChangeType;
import in.code.gatekeeper.management.enums.HeaderAction;
import in.code.gatekeeper.management.exception.ApiException;
import in.code.gatekeeper.management.kafka.OrganizationConfigChangedEvent;
import in.code.gatekeeper.management.repository.ApiRouteRepository;
import in.code.gatekeeper.management.repository.OrganizationRepository;
import in.code.gatekeeper.management.repository.OrganizationTokenSettingsRepository;
import in.code.gatekeeper.management.util.RequestContext;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Every method takes the organizationId from the caller's JWT, never from the request.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class TenantService {

    private final ApiRouteRepository apiRouteRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationTokenSettingsRepository tokenSettingsRepository;
    private final UpstreamTargetPolicy upstreamTargetPolicy;
    private final ApplicationEventPublisher eventPublisher;
    private final ConfigHistoryService configHistoryService;

    // Set by the gateway itself (or hop-by-hop), so a tenant can never allowlist them
    private static final Set<String> GATEWAY_MANAGED_HEADERS = Set.of(
            "host", "connection", "keep-alive", "proxy-authorization", "proxy-authenticate", "te", "trailer",
            "transfer-encoding", "upgrade", "content-length", "x-forwarded-for", "x-forwarded-host",
            "x-forwarded-proto", "x-forwarded-port", "forwarded", "x-real-ip", "x-user-id", "x-request-id",
            "x-correlation-id");
    // Always forwarded by the gateway, so they are not stored per route
    private static final Set<String> ALWAYS_FORWARDED_HEADERS = Set.of("content-type", "accept");
    // {name} segments of a path template
    private static final Pattern PATH_PARAMETER = Pattern.compile("\\{([^/]+)}");

    // ---------- token settings: how the gateway verifies this organization's user tokens ----------

    @Transactional(readOnly = true)
    public TokenSettingsResponse getTokenSettings(Long organizationId) {
        return tokenSettingsRepository.findByOrganizationId(organizationId)
                .filter(OrganizationTokenSettings::isActive)
                .map(TokenSettingsResponse::from)
                .orElseThrow(() -> ApiException.notFound("Token settings are not configured yet"));
    }

    // Creates or replaces the settings; the gateway needs them, so this is a configuration change
    public TokenSettingsResponse saveTokenSettings(Long organizationId, TokenSettingsRequest request) {
        Organization organization = lockOrganization(organizationId);
        OrganizationTokenSettings settings = tokenSettingsRepository.findByOrganizationId(organizationId)
                .orElseGet(() -> new OrganizationTokenSettings(organization));
        settings.setIssuer(request.issuer().trim());
        settings.setJwksUri(request.jwksUri().trim());
        settings.setAudience(request.audience().trim());
        settings.setActive(true);
        bumpVersion(organization, ConfigChangeType.TOKEN_SETTINGS_SAVED, null);
        return TokenSettingsResponse.from(tokenSettingsRepository.save(settings));
    }

    // ---------- routes ----------

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
        checkPathTemplate(organizationId, request.routeId(), request.pathPattern());

        ApiRoute route = new ApiRoute(organization, request.routeId());
        apply(route, request);
        bumpVersion(organization, ConfigChangeType.ROUTE_CREATED, request.routeId());
        return RouteResponse.from(apiRouteRepository.save(route));
    }

    public RouteResponse updateRoute(Long organizationId, String routeId, RouteRequest request) {
        if (!routeId.equals(request.routeId())) {
            throw ApiException.badRequest("routeId cannot be changed");
        }
        Organization organization = lockOrganization(organizationId);
        ApiRoute route = findRoute(organizationId, routeId);
        checkPathTemplate(organizationId, routeId, request.pathPattern());

        apply(route, request);
        bumpVersion(organization, ConfigChangeType.ROUTE_UPDATED, routeId);
        return RouteResponse.from(apiRouteRepository.saveAndFlush(route));
    }

    public RouteResponse setRouteActive(Long organizationId, String routeId, boolean active) {
        Organization organization = lockOrganization(organizationId);
        ApiRoute route = findRoute(organizationId, routeId);
        if (route.isActive() != active) {
            route.setActive(active);
            bumpVersion(organization,
                    active ? ConfigChangeType.ROUTE_ACTIVATED : ConfigChangeType.ROUTE_DEACTIVATED, routeId);
            apiRouteRepository.saveAndFlush(route);
        }
        return RouteResponse.from(route);
    }

    // ---------- configuration history and rollback ----------

    public List<ConfigVersionResponse> configurationVersions(Long organizationId) {
        return configHistoryService.versions(organizationId);
    }

    public ConfigPreviewResponse previewVersion(Long organizationId, long version) {
        return configHistoryService.preview(organizationId, version);
    }

    // Re-applies a kept version as a new change: history stays append-only and the result goes through the
    // same checks as a normal save. Organization status is the platform admin's and is never touched.
    public RollbackResponse rollback(Long organizationId, long version) {
        Organization organization = lockOrganization(organizationId);
        ConfigSnapshot snapshot = configHistoryService.snapshotToRestore(organizationId, version);
        restoreTokenSettings(organization, snapshot.tokenSettings());
        restoreRoutes(organization, snapshot.routes());
        bumpVersion(organization, ConfigChangeType.ROLLED_BACK, "to version " + version);
        return new RollbackResponse(version, organization.getConfigurationVersion(), snapshot.activeRoutes(),
                snapshot.tokenSettings() != null);
    }

    private void restoreTokenSettings(Organization organization, TokenSettingsRequest saved) {
        Optional<OrganizationTokenSettings> current = tokenSettingsRepository.findByOrganizationId(organization.getId());
        if (saved == null) {
            current.ifPresent(settings -> settings.setActive(false));
            return;
        }
        OrganizationTokenSettings settings = current.orElseGet(() -> new OrganizationTokenSettings(organization));
        settings.setIssuer(saved.issuer());
        settings.setJwksUri(saved.jwksUri());
        settings.setAudience(saved.audience());
        settings.setActive(true);
        tokenSettingsRepository.save(settings);
    }

    // Routes created after that version are switched off; routes are never hard deleted, so all others still exist
    private void restoreRoutes(Organization organization, List<RouteSnapshot> saved) {
        Map<String, ApiRoute> existing = apiRouteRepository.findAllByOrganizationIdOrderByRouteId(organization.getId())
                .stream().collect(Collectors.toMap(ApiRoute::getRouteId, Function.identity()));
        Set<String> restored = saved.stream().map(route -> route.route().routeId()).collect(Collectors.toSet());
        existing.values().stream()
                .filter(route -> !restored.contains(route.getRouteId()))
                .forEach(route -> route.setActive(false));
        for (RouteSnapshot routeSnapshot : saved) {
            RouteRequest request = routeSnapshot.route();
            checkPathTemplate(organization.getId(), request.routeId(), request.pathPattern());
            ApiRoute route = existing.computeIfAbsent(request.routeId(), routeId -> new ApiRoute(organization, routeId));
            apply(route, request);
            route.setActive(routeSnapshot.active());
            apiRouteRepository.save(route);
        }
    }

    // Taken first by every write, so changes inside one organization are serialized and versioned in order
    private Organization lockOrganization(Long organizationId) {
        return organizationRepository.findByIdForUpdate(organizationId)
                .orElseThrow(() -> ApiException.notFound("Organization " + organizationId + " not found"));
    }

    // The version bump and the change event always go together; the event is handled before/after commit
    private void bumpVersion(Organization organization, ConfigChangeType changeType, String detail) {
        organization.incrementVersion();
        eventPublisher.publishEvent(new OrganizationConfigChangedEvent(organization.getId(), changeType, detail,
                RequestContext.username(), RequestContext.correlationId()));
    }

    private ApiRoute findRoute(Long organizationId, String routeId) {
        return apiRouteRepository.findByOrganizationIdAndRouteId(organizationId, routeId)
                .orElseThrow(() -> ApiException.notFound("Route '" + routeId + "' not found"));
    }

    // /orders/{id} and /orders/{orderId} match exactly the same requests, so templates are compared with the
    // parameter names removed. (A literal like /orders/new may coexist with /orders/{id}; the gateway prefers literals.)
    private void checkPathTemplate(Long organizationId, String routeId, String pathPattern) {
        List<String> parameters = PATH_PARAMETER.matcher(pathPattern).results()
                .map(match -> match.group(1)).toList();
        if (new HashSet<>(parameters).size() != parameters.size()) {
            throw ApiException.badRequest("Path '" + pathPattern + "' uses the same parameter name twice");
        }
        String shape = pathShape(pathPattern);
        apiRouteRepository.findAllByOrganizationIdOrderByRouteId(organizationId).stream()
                .filter(other -> !other.getRouteId().equals(routeId))
                .filter(other -> pathShape(other.getPathPattern()).equals(shape))
                .findFirst()
                .ifPresent(other -> {
                    throw ApiException.conflict("Path '" + pathPattern + "' matches the same requests as route '"
                            + other.getRouteId() + "' (" + other.getPathPattern() + ")");
                });
    }

    private static String pathShape(String pathPattern) {
        return PATH_PARAMETER.matcher(pathPattern).replaceAll("{}");
    }

    // Header names are case-insensitive: stored lowercase, gateway-managed ones rejected, always-forwarded ones skipped
    private static Set<String> normalizeAllowedHeaders(Set<String> requested) {
        Set<String> headers = (requested == null ? Set.<String>of() : requested).stream()
                .map(header -> header.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        headers.stream().filter(GATEWAY_MANAGED_HEADERS::contains).findFirst().ifPresent(header -> {
            throw ApiException.badRequest("Header '" + header + "' is set by the gateway and cannot be allowlisted");
        });
        headers.removeAll(ALWAYS_FORWARDED_HEADERS);
        return headers;
    }

    private void apply(ApiRoute route, RouteRequest request) {
        upstreamTargetPolicy.check(request.targetBaseUrl());
        route.setName(request.name().trim());
        route.setPathPattern(request.pathPattern());
        route.setTargetBaseUrl(request.targetBaseUrl());
        route.setTargetPathPrefix(request.targetPathPrefix() == null ? "" : request.targetPathPrefix());
        route.setRequestsPerMinute(request.requestsPerMinute());
        route.setResponseTimeoutMs(request.responseTimeoutMs());
        route.setIdempotencyEnabled(Boolean.TRUE.equals(request.idempotencyEnabled()));

        route.getAllowedMethods().clear();
        route.getAllowedMethods().addAll(request.allowedMethods());

        route.getAllowedHeaders().clear();
        route.getAllowedHeaders().addAll(normalizeAllowedHeaders(request.allowedHeaders()));

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
