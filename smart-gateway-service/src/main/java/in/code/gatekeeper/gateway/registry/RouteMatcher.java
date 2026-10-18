package in.code.gatekeeper.gateway.registry;

import in.code.gatekeeper.gateway.model.RouteConfig;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Finds the route for a request path among one organization's exact templates. Built once per configuration version.
 *
 * A template like /orders/{id} matches only paths with the same number of segments: literal segments must be equal,
 * a {name} segment matches any one segment. When several templates match, the one with a literal at the first
 * differing position wins, so /orders/new beats /orders/{id}. api-management rejects two templates of the same shape,
 * so there is never a tie.
 */
public final class RouteMatcher {

    // Candidates grouped by segment count, most specific first, so the first match is the winner
    private final Map<Integer, List<CompiledRoute>> routesBySegmentCount;

    public RouteMatcher(List<RouteConfig> routes) {
        this.routesBySegmentCount = routes.stream()
                .map(CompiledRoute::compile)
                .sorted(CompiledRoute.MOST_SPECIFIC_FIRST)
                .collect(Collectors.groupingBy(route -> route.segments().size(), Collectors.toUnmodifiableList()));
    }

    // segments: the raw path segments after the organization slug, e.g. ["orders", "42"]
    public Optional<RouteConfig> match(List<String> segments) {
        return routesBySegmentCount.getOrDefault(segments.size(), List.of()).stream()
                .filter(route -> route.matches(segments))
                .map(CompiledRoute::route)
                .findFirst();
    }

    // A null segment stands for a {name} parameter
    private record CompiledRoute(RouteConfig route, List<String> segments) {

        static final Comparator<CompiledRoute> MOST_SPECIFIC_FIRST = (a, b) -> {
            for (int i = 0; i < Math.min(a.segments.size(), b.segments.size()); i++) {
                boolean aLiteral = a.segments.get(i) != null;
                boolean bLiteral = b.segments.get(i) != null;
                if (aLiteral != bLiteral) {
                    return aLiteral ? -1 : 1;
                }
            }
            return 0;
        };

        static CompiledRoute compile(RouteConfig route) {
            List<String> segments = Arrays.stream(route.pathPattern().substring(1).split("/"))
                    .map(segment -> segment.startsWith("{") ? null : segment)
                    .toList();
            return new CompiledRoute(route, segments);
        }

        boolean matches(List<String> path) {
            for (int i = 0; i < segments.size(); i++) {
                String literal = segments.get(i);
                if (literal != null && !literal.equals(path.get(i))) {
                    return false;
                }
            }
            return true;
        }
    }
}
