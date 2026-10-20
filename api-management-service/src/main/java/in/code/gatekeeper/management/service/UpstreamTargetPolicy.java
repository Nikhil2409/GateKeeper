package in.code.gatekeeper.management.service;

import in.code.gatekeeper.management.exception.ApiException;
import in.code.gatekeeper.management.util.Cidr;
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
 * Stops a tenant from pointing a route at something the gateway must never call on their behalf: the platform's
 * own services, the cloud metadata endpoint (it hands out credentials) and, unless explicitly allowed for local
 * development, loopback. Checked on save for a clear error; the gateway checks the resolved IP again at forward
 * time, because a harmless-looking hostname can later resolve to a blocked address (DNS rebinding).
 * Private ranges (10.x, 192.168.x) stay allowed: tenant backends often live on private networks, except the
 * platform's own network (blocked-cidrs), so a tenant can't reach a platform container by its IP.
 */
@Component
public class UpstreamTargetPolicy {

    private final Set<String> blockedHosts;
    private final boolean allowLoopback;
    private final List<Cidr> blockedCidrs;

    public UpstreamTargetPolicy(@Value("${gatekeeper.routes.blocked-hosts}") List<String> blockedHosts,
                                @Value("${gatekeeper.routes.allow-loopback}") boolean allowLoopback,
                                @Value("${gatekeeper.routes.blocked-cidrs:}") List<String> blockedCidrs) {
        this.blockedHosts = blockedHosts.stream().map(host -> host.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.allowLoopback = allowLoopback;
        this.blockedCidrs = Cidr.parseAll(blockedCidrs);
    }

    public void check(String targetBaseUrl) {
        String host = URI.create(targetBaseUrl).getHost();
        if (host == null) {
            throw ApiException.badRequest("Target base URL has no host");
        }
        host = host.toLowerCase(Locale.ROOT);
        if (blockedHosts.contains(host) || isLinkLocal(host)) {
            throw ApiException.badRequest("Target host '" + host + "' is not allowed: it is a platform service "
                    + "or the cloud metadata address");
        }
        if (inBlockedCidr(host)) {
            throw ApiException.badRequest("Target host '" + host + "' is not allowed: it is on the platform's own network");
        }
        if (!allowLoopback && isLoopback(host)) {
            throw ApiException.badRequest("Target host '" + host + "' is not allowed: loopback addresses point at "
                    + "the gateway's own machine");
        }
    }

    // Literal IPs only: names are checked again by the gateway on the address they resolve to when called
    private boolean inBlockedCidr(String host) {
        if (blockedCidrs.isEmpty() || !host.matches("[0-9.]+|.*:.*")) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            return blockedCidrs.stream().anyMatch(cidr -> cidr.contains(address));
        } catch (UnknownHostException e) {
            return false;
        }
    }

    // 169.254.0.0/16 link-local: the cloud metadata service lives at 169.254.169.254
    private static boolean isLinkLocal(String host) {
        int[] octets = ipv4(host);
        return octets != null && octets[0] == 169 && octets[1] == 254;
    }

    // localhost, *.localhost, 127.0.0.0/8 and 0.0.0.0/8
    private static boolean isLoopback(String host) {
        if (host.equals("localhost") || host.endsWith(".localhost")) {
            return true;
        }
        int[] octets = ipv4(host);
        return octets != null && (octets[0] == 127 || octets[0] == 0);
    }

    private static int[] ipv4(String host) {
        if (!host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return null;
        }
        String[] parts = host.split("\\.");
        return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }
}
