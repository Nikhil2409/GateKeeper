package in.code.gatekeeper.gateway.check;

import in.code.gatekeeper.gateway.check.RequestGuard.PublicPath;
import in.code.gatekeeper.gateway.exception.GatewayException;
import in.code.gatekeeper.gateway.model.RouteConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The last check before connecting: the same policy api-management applies when a route is saved, but on the
 * addresses the host resolves to NOW, which also catches a DNS record changed after the route was saved.
 * Always blocked: the platform's own hosts, link-local (169.254.x, incl. cloud metadata), 0.0.0.0, multicast.
 * Loopback is blocked unless allowed for local development. Private ranges stay allowed: tenant services live there.
 * Known gap: DNS could still change between this check and the connect (closing it means pinning the IP).
 */
@Slf4j
@Component
public class UpstreamTargetGuard {

    private static final String NOT_ALLOWED = "Upstream target is not allowed";

    private final Set<String> blockedHosts;
    private final boolean allowLoopback;

    public UpstreamTargetGuard(@Value("${gateway.upstream.blocked-hosts}") List<String> blockedHosts,
                               @Value("${gateway.upstream.allow-loopback}") boolean allowLoopback) {
        this.blockedHosts = blockedHosts.stream().map(host -> host.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        this.allowLoopback = allowLoopback;
    }

    // targetBaseUrl + targetPathPrefix + the request path exactly as received (+ query), checked before it is used
    public URI resolve(RouteConfig route, PublicPath path) {
        String target = route.targetBaseUrl() + route.targetPathPrefix() + path.path()
                + (path.rawQuery() == null ? "" : "?" + path.rawQuery());
        URI uri;
        try {
            uri = URI.create(target);
        } catch (IllegalArgumentException e) {
            throw GatewayException.badRequest("Invalid characters in path or query");
        }
        check(uri);
        return uri;
    }

    public void check(URI target) {
        String host = target.getHost() == null ? "" : target.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty() || blockedHosts.contains(host)) {
            log.warn("Blocked upstream host {}", host);
            throw GatewayException.badGateway(NOT_ALLOWED);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            log.warn("Upstream host {} could not be resolved", host);
            throw GatewayException.badGateway("Upstream service is not reachable");
        }
        for (InetAddress address : addresses) {
            if (address.isLinkLocalAddress() || address.isAnyLocalAddress() || address.isMulticastAddress()
                    || (address.isLoopbackAddress() && !allowLoopback)) {
                log.warn("Blocked upstream {} resolving to {}", host, address.getHostAddress());
                throw GatewayException.badGateway(NOT_ALLOWED);
            }
        }
    }
}
